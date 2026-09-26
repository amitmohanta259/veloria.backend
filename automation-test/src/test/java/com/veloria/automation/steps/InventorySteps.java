package com.veloria.automation.steps;

import com.veloria.automation.api.OrderApi;
import com.veloria.automation.db.Db;
import com.veloria.automation.db.TestData;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stock at checkout, exercised through the real API and the real database.
 *
 * Products are seeded here rather than borrowed from development data, so a
 * scenario's arithmetic is deterministic. Everything seeded is removed by the
 * cleanup registered on the scenario.
 */
public class InventorySteps {

    private static final String DELIVERY = "12 MG Road, Bengaluru, Karnataka 560001";

    private final ScenarioContext ctx;

    public InventorySteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── seeding ──────────────────────────────────────────────────────────────

    private UUID seedProduct(long stock) {
        UUID uuid = UUID.randomUUID();
        Db.execute("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Inventory Product', ?, 100000, 100000, 'INR', ?, '6211', false, now())
                """, uuid.toString(), "AUTO-INV-" + uuid.toString().substring(0, 8), stock);
        Long id = ((Number) Db.one("SELECT id FROM inventory_product WHERE uuid = ?::uuid", uuid.toString())
                .get("id")).longValue();
        Db.execute("""
                INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
                VALUES (?, 'M', ?, false)
                """, id, stock);

        ctx.onCleanup(() -> {
            Db.execute("DELETE FROM inventory_product_size_stock WHERE product_id = ?", id);
            Db.execute("DELETE FROM inventory_product WHERE id = ?", id);
        });
        return uuid;
    }

    @Given("a product with {long} units in stock")
    public void aProductWithUnits(long stock) {
        ctx.vars.put("productUuid", seedProduct(stock).toString());
    }

    @Given("a product {string} with {long} units in stock")
    public void aNamedProductWithUnits(String name, long stock) {
        ctx.vars.put("product:" + name, seedProduct(stock).toString());
    }

    // ── ordering ─────────────────────────────────────────────────────────────

    @When("the buyer places an order for {int} of that product")
    public void placeOrderForThatProduct(int quantity) {
        ctx.lastResponse = OrderApi.place(ctx.clientToken,
                OrderApi.request(DELIVERY, ctx.var("productUuid"), quantity));
        rememberOrder();
    }

    @When("the buyer places an order for {int} of {string} and {int} of {string}")
    public void placeMultiItemOrder(int qtyA, String nameA, int qtyB, String nameB) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("productUuid", ctx.var("product:" + nameA), "quantity", qtyA));
        items.add(Map.of("productUuid", ctx.var("product:" + nameB), "quantity", qtyB));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryLocation", DELIVERY);
        body.put("currency", "INR");
        body.put("items", items);

        ctx.lastResponse = OrderApi.place(ctx.clientToken, body);
        rememberOrder();
    }

    // ── assertions ───────────────────────────────────────────────────────────

    @Then("the product has {long} units left")
    public void theProductHasUnitsLeft(long expected) {
        assertEquals(expected, available(ctx.var("productUuid")),
                "available = initial − sold + returned");
    }

    @Then("product {string} has {long} units left")
    public void namedProductHasUnitsLeft(String name, long expected) {
        assertEquals(expected, available(ctx.var("product:" + name)), name);
    }

    @Then("no order was recorded for the buyer")
    public void noOrderWasRecorded() {
        Object buyer = ctx.vars.get("buyer");
        assertNotNull(buyer, "no buyer seeded");
        String email = ((TestData.Buyer) buyer).email();
        long orders = Db.count("SELECT count(*) FROM customer_order WHERE customer_email = ?", email);
        assertEquals(0, orders, "a refused order must leave nothing behind");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The application's own stock model, summing units rather than counting rows.
     *
     * <p>Mirrors {@code InventoryProductRepository.availableStock}. This module
     * cannot import the backend's {@code OrderStatus}, so the consuming list is
     * repeated here; it must be kept in step with that enum. Units are away from
     * the shelf for the whole outbound and return journey, and come back only
     * when a return condition has been recorded against the line — a return that
     * has merely been <em>requested</em> has brought nothing back.
     */
    private long available(String productUuid) {
        Object v = Db.one("""
                SELECT COALESCE(ip.initial_stock, 0)
                     - COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND co.status IN ('ORDER_PLACED','PACKED','IN_TRANSIT','OUT_FOR_DELIVERY',
                                                     'DISPATCHED','DONE','DELIVERED','READY_TO_PICKUP','PICKED_UP',
                                                     'IN_TRANSIT_TO_SELLER','RECEIVED','RETURNED','PARTIALLY_RETURNED')), 0)
                     + COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND coi.return_condition IS NOT NULL), 0)
                       AS available
                  FROM inventory_product ip WHERE ip.uuid = ?::uuid
                """, productUuid).get("available");
        return ((Number) v).longValue();
    }

    /** Registers cleanup for an order the scenario actually created. */
    private void rememberOrder() {
        if (ctx.lastResponse != null && ctx.lastResponse.ok()
                && ctx.lastResponse.data() != null && ctx.lastResponse.data().hasNonNull("orderCode")) {
            String code = ctx.lastResponse.data().get("orderCode").asText();
            ctx.vars.put("orderCode", code);
            ctx.onCleanup(() -> TestData.removeOrderGraph(code));
        }
    }
}
