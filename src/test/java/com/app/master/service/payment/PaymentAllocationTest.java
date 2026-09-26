package com.app.master.service.payment;

import com.app.master.service.core.payment.PaymentAllocation;
import com.app.master.service.core.payment.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.app.master.service.core.payment.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The money rules, without a database.
 *
 * <p>Every figure here is integer paise. A test that passed with a rounded
 * rupee value would prove nothing about a system that must reconcile to the
 * paise against a payment provider.
 */
class PaymentAllocationTest {

    // ── the approved waterfall ───────────────────────────────────────────────

    @Test
    @DisplayName("the approved example allocates exactly as specified")
    void approvedExample() {
        // Product 10,000 · GST 1,800 · Transport 500 · Other 200 = 12,500 invoice.
        // First payment is half: 6,250.
        PaymentAllocation a = PaymentAllocation.waterfall(
                625000, 180000, 50000, 20000, 1000000);

        assertEquals(180000, a.gstPaise(),       "GST is settled in full first");
        assertEquals(50000,  a.transportPaise(), "then transportation, in full");
        assertEquals(20000,  a.otherPaise(),     "then other charges, in full");
        assertEquals(375000, a.productPaise(),   "the remainder goes to product");
        assertEquals(625000, a.amountPaise());
    }

    @Test
    @DisplayName("the balance of the approved example is the rest of the product")
    void approvedExampleBalance() {
        // After the first payment the product still owes 10,000 − 3,750 = 6,250,
        // and every other head is already settled.
        PaymentAllocation second = PaymentAllocation.waterfall(
                625000, 0, 0, 0, 625000);

        assertEquals(0,      second.gstPaise());
        assertEquals(0,      second.transportPaise());
        assertEquals(0,      second.otherPaise());
        assertEquals(625000, second.productPaise(), "the balance is entirely product");

        // And the two payments together settle the invoice exactly.
        assertEquals(1250000, 625000 + 625000);
    }

    @Test
    @DisplayName("a payment smaller than the charges settles them in priority order")
    void smallPaymentStopsAtThePriorities() {
        // 2,000 paise against 1,800 GST, 500 transport: GST is fully settled,
        // transport gets what is left, product gets nothing.
        PaymentAllocation a = PaymentAllocation.waterfall(2000, 1800, 500, 0, 100000);

        assertEquals(1800, a.gstPaise());
        assertEquals(200,  a.transportPaise(), "transport takes only what remains");
        assertEquals(0,    a.productPaise(),   "product waits its turn");
    }

    @Test
    @DisplayName("an allocation always adds up to the payment")
    void allocationAlwaysBalances() {
        for (long amount : new long[]{100, 999, 1000, 62501, 1250000}) {
            PaymentAllocation a = PaymentAllocation.waterfall(amount, 180000, 50000, 20000, 1000000);
            assertEquals(amount,
                    a.gstPaise() + a.transportPaise() + a.otherPaise() + a.productPaise(),
                    "the parts of a " + amount + " paise payment must sum to it");
        }
    }

    @Test
    @DisplayName("paying more than the order owes is refused rather than absorbed")
    void overpaymentIsRefused() {
        // Silently keeping the excess would be an unrecorded over-collection;
        // spreading it would misstate revenue and tax.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PaymentAllocation.waterfall(100000, 100, 0, 0, 100));
        assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
    }

    @Test
    @DisplayName("only product and its GST are refundable")
    void refundableExcludesTransportAndOtherCharges() {
        PaymentAllocation a = PaymentAllocation.waterfall(
                625000, 180000, 50000, 20000, 1000000);

        // Transportation and the COD handling charge are not returned.
        assertEquals(180000 + 375000, a.refundablePaise());
        assertNotEquals(a.amountPaise(), a.refundablePaise(),
                "a refund must be less than the payment when non-refundable charges were settled");
    }

    @Test
    @DisplayName("a negative allocation is impossible to construct")
    void negativeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PaymentAllocation.waterfall(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PaymentAllocation(100, -1, 0, 0, 101));
    }

    @Test
    @DisplayName("an allocation that does not add up cannot be constructed")
    void mismatchedAllocationIsRejected() {
        assertThrows(IllegalStateException.class, () -> new PaymentAllocation(1000, 100, 0, 0, 0));
    }

    // ── the payment state machine ────────────────────────────────────────────

    @Test
    @DisplayName("a payment reaches capture either directly or through authorization")
    void captureRoutes() {
        assertTrue(INITIATED.canMoveTo(PENDING));
        assertTrue(PENDING.canMoveTo(AUTHORIZED));
        assertTrue(AUTHORIZED.canMoveTo(CAPTURED));
        assertTrue(PENDING.canMoveTo(CAPTURED), "some methods capture without a separate authorization");
    }

    @Test
    @DisplayName("a captured payment cannot quietly become unpaid")
    void capturedCannotRegress() {
        for (PaymentStatus s : new PaymentStatus[]{INITIATED, PENDING, AUTHORIZED, FAILED, CANCELLED}) {
            assertFalse(CAPTURED.canMoveTo(s), "CAPTURED must not be able to become " + s);
        }
        assertTrue(CAPTURED.canMoveTo(REFUNDED), "but money can be given back");
        assertTrue(CAPTURED.canMoveTo(PARTIALLY_REFUNDED));
    }

    @Test
    @DisplayName("a failed payment is final for that attempt")
    void failedIsTerminal() {
        assertTrue(FAILED.isTerminal());
        assertTrue(FAILED.allowedNext().isEmpty());
        assertFalse(FAILED.canMoveTo(CAPTURED), "a failed payment cannot be revived into a capture");
    }

    @Test
    @DisplayName("only captured and partially refunded payments count as paid")
    void whatCountsAsPaid() {
        assertTrue(CAPTURED.isPaid());
        assertTrue(PARTIALLY_REFUNDED.isPaid(), "a balance remains with the business");
        for (PaymentStatus s : new PaymentStatus[]{INITIATED, PENDING, AUTHORIZED, FAILED, CANCELLED, REFUNDED}) {
            assertFalse(s.isPaid(), s + " must not count as paid");
        }
        assertFalse(AUTHORIZED.isPaid(), "authorized money is held, not taken");
    }
}
