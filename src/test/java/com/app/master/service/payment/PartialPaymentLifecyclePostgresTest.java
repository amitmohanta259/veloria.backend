package com.app.master.service.payment;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.payment.PaymentStatus;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.service.payment.PaymentService;
import com.app.master.service.service.payment.RazorpayGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A partially paid order, from the first collection to the last.
 *
 * <p>The first half was covered when it was built; the balance was not. That
 * gap matters more than it sounds: the second collection is the one that has to
 * work out what is genuinely still owed, and getting it wrong either
 * short-changes the business or charges a customer twice for the same goods.
 *
 * <p>Everything here follows the approved waterfall — GST, then transportation,
 * then other charges, then whatever remains to the product — and the approved
 * rule that the first collection is half the final invoice. No accounting is
 * posted: sale recognition timing is unresolved, so this proves the money is
 * tracked correctly, not that it has been booked.
 *
 * <p>The gateway is stubbed. What is under test is the arithmetic and the
 * outstanding balance, neither of which Razorpay has any part in.
 */
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        // One shared pool size across every integration test, on purpose.
        //
        // Spring caches a context per distinct property set, and each context
        // brings its own connection pool. Five different sizes meant five pools
        // — 160 connections against a server that allows 100, so a suite run
        // died with "too many clients" while each test passed alone. Identical
        // properties mean the contexts are shared, and 40 is enough for the
        // largest concurrency test now that placing an order also books its sale.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class PartialPaymentLifecyclePostgresTest {

    @MockBean private RazorpayGateway razorpay;

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentAttemptRepository attemptRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerToken;
    private Long orderId;
    private String orderCode;
    private Long productId;

    // The approved example, in paise: product 10,000.00 · GST 1,800.00 ·
    // transport 500.00 · other 200.00 → a 12,500.00 invoice.
    private static final long PRODUCT   = 1000000;
    private static final long GST       =  180000;
    private static final long TRANSPORT =   50000;
    private static final long OTHER     =   20000;
    private static final long INVOICE   = PRODUCT + GST + TRANSPORT + OTHER;   // 1,250,000
    private static final long FIRST     = INVOICE / 2;                          //   625,000

    @BeforeEach
    void seed() {
        buyerUuid = UUID.randomUUID().toString();
        String email = "part-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, email);

        buyerToken = "part-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, 'Automation Buyer', ?, '9000000000',
                        timezone('UTC', now()) + interval '1 hour',
                        timezone('UTC', now()) + interval '1 hour')
                """, buyerToken, buyerUuid, email);

        UUID productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Partial Product', ?, 1000000, 1000000, 'INR', 5, '6211', false, now())
                """, productUuid.toString(), "AUTO-PRT-" + productUuid.toString().substring(0, 8));
        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());

        orderCode = "VO-PRT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_name, customer_email,
                                            delivery_location, currency, total_value, taxable_value,
                                            cgst_amount, sgst_amount, igst_amount, total_tax_amount,
                                            shipping_value, cod_fee_paise, status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, 'Automation Buyer', ?, '12 MG Road, Bengaluru', 'INR',
                        ?, ?, ?, ?, 0, ?, ?, ?, 'ORDER_PLACED', timezone('UTC', now()), true, false)
                """, orderCode, buyerUuid, email,
                PRODUCT + GST, PRODUCT, GST / 2, GST / 2, GST, TRANSPORT, OTHER);
        orderId = jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, orderCode);
        jdbc.update("""
                INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, false)
                """, orderId, productUuid.toString());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_refund WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM payment_attempt WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM customer_order WHERE id = ?", orderId);
        jdbc.update("DELETE FROM inventory_product WHERE id = ?", productId);
        jdbc.update("DELETE FROM client_session WHERE user_id = ?", buyerUuid);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);
    }

    /** Drives an attempt to CAPTURED through the same path the gateway would. */
    private PaymentAttemptEntity capture(String idempotencyKey) throws Exception {
        PaymentService.CheckoutSession s = paymentService.initiate(
                buyerToken, orderCode, "ONLINE_PARTIAL", idempotencyKey);

        PaymentAttemptEntity attempt = attemptRepo.findByIdempotencyKey(idempotencyKey).orElseThrow();
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, attempt.getRazorpayOrderId(), "captured",
                attempt.getAmountPaise(), "INR", "card", null, null, null, null));

        paymentService.applyGatewayTruth(attempt, paymentId, "test");
        return attemptRepo.findById(attempt.getId()).orElseThrow();
    }

    /** initiate() needs a Razorpay order id; the stub supplies one. */
    private void stubOrderCreation() throws Exception {
        when(razorpay.createOrder(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenAnswer(i -> "order_" + UUID.randomUUID().toString().substring(0, 12));
    }

    // ── the full partial-payment cycle ───────────────────────────────────────

    @Test
    @DisplayName("first collection takes half the invoice and settles the charges before the goods")
    void firstCollectionFollowsTheWaterfall() throws Exception {
        stubOrderCreation();

        PaymentAttemptEntity first = capture("first-" + UUID.randomUUID());

        assertEquals(FIRST, first.getAmountPaise(), "half of a 12,500.00 invoice is 6,250.00");
        assertEquals(PaymentStatus.CAPTURED.name(), first.getStatus());

        // The approved priority: GST, transportation and other charges in full,
        // then whatever is left towards the product.
        assertEquals(GST,       first.getGstAllocatedPaise(),       "100% of GST");
        assertEquals(TRANSPORT, first.getTransportAllocatedPaise(), "100% of transportation");
        assertEquals(OTHER,     first.getOtherAllocatedPaise(),     "100% of other charges");
        assertEquals(375000,    first.getProductAllocatedPaise(),   "the remaining 3,750.00 to product");

        assertEquals(FIRST,
                first.getGstAllocatedPaise() + first.getTransportAllocatedPaise()
                        + first.getOtherAllocatedPaise() + first.getProductAllocatedPaise(),
                "the allocation must account for every paise collected");
    }

    @Test
    @DisplayName("the order is not treated as settled after the first half")
    void notFullyPaidAfterTheFirstHalf() throws Exception {
        stubOrderCreation();
        capture("first-" + UUID.randomUUID());

        Map<String, Object> summary = paymentService.summaryFor(buyerToken, orderCode);

        assertEquals(INVOICE, summary.get("finalInvoiceTotalPaise"));
        assertEquals(FIRST,   summary.get("amountPaidPaise"));
        assertEquals(INVOICE - FIRST, summary.get("amountOutstandingPaise"), "half remains owing");
        assertEquals(false,   summary.get("fullyPaid"),
                "a half-paid order must never report itself as paid");
    }

    @Test
    @DisplayName("the balance is exactly the product still owed, and it settles the order")
    void secondCollectionClearsTheBalance() throws Exception {
        stubOrderCreation();
        capture("first-" + UUID.randomUUID());

        // What is genuinely still owed: 10,000.00 product − 3,750.00 already
        // allocated = 6,250.00. Every other head is settled.
        long quoted = paymentService.quote(buyerToken, orderCode, "ONLINE_PARTIAL");
        assertEquals(625000, quoted, "the balance is the outstanding product, to the paise");

        PaymentAttemptEntity second = capture("second-" + UUID.randomUUID());

        assertEquals(625000, second.getAmountPaise());
        assertEquals(2, second.getSequenceNo(), "the balance is the second collection");
        assertEquals(0, second.getGstAllocatedPaise(),       "GST was already settled in full");
        assertEquals(0, second.getTransportAllocatedPaise(), "so was transportation");
        assertEquals(0, second.getOtherAllocatedPaise(),     "and other charges");
        assertEquals(625000, second.getProductAllocatedPaise(), "the balance is entirely product");

        Map<String, Object> summary = paymentService.summaryFor(buyerToken, orderCode);
        assertEquals(INVOICE, summary.get("amountPaidPaise"), "the two collections settle the invoice");
        assertEquals(0L,      summary.get("amountOutstandingPaise"));
        assertEquals(true,    summary.get("fullyPaid"));
    }

    @Test
    @DisplayName("across both collections each head receives exactly what the invoice said")
    void allocationsSumToTheInvoice() throws Exception {
        stubOrderCreation();
        capture("first-" + UUID.randomUUID());
        capture("second-" + UUID.randomUUID());

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(gst_allocated_paise), 0)       AS gst,
                       COALESCE(SUM(transport_allocated_paise), 0) AS transport,
                       COALESCE(SUM(other_allocated_paise), 0)     AS other,
                       COALESCE(SUM(product_allocated_paise), 0)   AS product,
                       COALESCE(SUM(amount_paise), 0)              AS total
                  FROM payment_attempt
                 WHERE customer_order_id = ? AND status = 'CAPTURED'
                """, orderId);

        assertEquals(GST,       num(totals.get("gst")),       "GST paid once, in full");
        assertEquals(TRANSPORT, num(totals.get("transport")), "transportation once, in full");
        assertEquals(OTHER,     num(totals.get("other")),     "other charges once, in full");
        assertEquals(PRODUCT,   num(totals.get("product")),   "and the whole product");
        assertEquals(INVOICE,   num(totals.get("total")),     "summing to the invoice exactly");
    }

    @Test
    @DisplayName("a third collection is refused: there is nothing left to pay")
    void nothingLeftToCollect() throws Exception {
        stubOrderCreation();
        capture("first-" + UUID.randomUUID());
        capture("second-" + UUID.randomUUID());

        VeloriaException e = assertThrows(VeloriaException.class, () -> paymentService.quote(
                buyerToken, orderCode, "ONLINE_PARTIAL"));
        assertTrue(e.getMessage().toLowerCase().contains("already been paid"), e.getMessage());

        // And no attempt may be opened for it either.
        assertThrows(VeloriaException.class, () -> paymentService.initiate(
                buyerToken, orderCode, "ONLINE_PARTIAL", "third-" + UUID.randomUUID()));

        assertEquals(INVOICE, (long) attemptRepo.capturedTotalPaise(orderId),
                "the customer must never be charged beyond the invoice");
    }

    @Test
    @DisplayName("the stored allocation is a snapshot, not something recomputed later")
    void allocationIsFrozen() throws Exception {
        stubOrderCreation();
        PaymentAttemptEntity first = capture("first-" + UUID.randomUUID());
        long gstAtCapture = first.getGstAllocatedPaise();

        // The order's figures change afterwards — a correction, a re-price.
        jdbc.update("""
                UPDATE customer_order SET taxable_value = 5000000, total_tax_amount = 900000,
                                          total_value = 5900000
                 WHERE id = ?
                """, orderId);

        PaymentAttemptEntity reread = attemptRepo.findById(first.getId()).orElseThrow();
        assertEquals(gstAtCapture, reread.getGstAllocatedPaise(),
                "what a customer was recorded as paying cannot change retrospectively");
        assertEquals(375000, reread.getProductAllocatedPaise());
        assertEquals(FIRST, reread.getAmountPaise());
    }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
}
