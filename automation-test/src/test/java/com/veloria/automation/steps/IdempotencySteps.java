package com.veloria.automation.steps;

import com.veloria.automation.api.Http;
import com.veloria.automation.api.OrderApi;
import com.veloria.automation.db.Db;
import com.veloria.automation.db.TestData;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkout idempotency through the real API.
 *
 * A "checkout reference" is what the browser generates once per attempt to pay
 * and resends unchanged when it retries. These steps hold on to the reference
 * and to each response, so a scenario can say plainly whether two submissions
 * produced one order or two.
 *
 * Products are seeded by {@link InventorySteps} and read back through the same
 * "units left" assertion, so stock arithmetic is stated in one place only.
 */
public class IdempotencySteps {

    private static final String DELIVERY = "12 MG Road, Bengaluru, Karnataka 560001";

    private final ScenarioContext ctx;

    public IdempotencySteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── submitting ───────────────────────────────────────────────────────────

    @When("the buyer checks out {int} of that product under a new checkout reference")
    public void checkOutUnderNewReference(int quantity) {
        String reference = "auto-chk-" + UUID.randomUUID();
        ctx.vars.put("checkoutReference", reference);
        submit(ctx.clientToken, reference, quantity);
    }

    @When("the buyer checks out {int} of that product with no checkout reference")
    public void checkOutWithoutReference(int quantity) {
        submit(ctx.clientToken, null, quantity);
    }

    @When("the buyer submits the same checkout again")
    public void submitSameCheckoutAgain() {
        submit(ctx.clientToken, ctx.var("checkoutReference"), lastQuantity());
    }

    @When("the buyer submits the same checkout {int} more times")
    public void submitSameCheckoutMoreTimes(int times) {
        for (int i = 0; i < times; i++) {
            submit(ctx.clientToken, ctx.var("checkoutReference"), lastQuantity());
        }
    }

    @When("the buyer submits that reference with {int} of that product instead")
    public void submitSameReferenceDifferentBag(int quantity) {
        submit(ctx.clientToken, ctx.var("checkoutReference"), quantity);
    }

    @When("the second buyer submits that same checkout reference")
    public void secondBuyerSubmitsSameReference() {
        assertNotNull(ctx.otherClientToken, "the scenario must seed a second buyer first");
        submit(ctx.otherClientToken, ctx.var("checkoutReference"), lastQuantity());
    }

    // ── assertions ───────────────────────────────────────────────────────────

    @Then("both responses name the same order")
    public void bothResponsesNameTheSameOrder() {
        assertEquals(orderCodeAt(0), orderCodeAt(1),
                "a retry must be given the order the first attempt created");
    }

    @Then("the two responses name different orders")
    public void theTwoResponsesNameDifferentOrders() {
        assertNotEquals(orderCodeAt(0), orderCodeAt(1),
                "two separate checkouts are two separate orders");
    }

    @Then("exactly {int} order exists for that checkout reference")
    public void exactlyOrdersExistForReference(int expected) {
        long rows = Db.count("SELECT count(*) FROM customer_order WHERE client_order_reference = ?",
                ctx.var("checkoutReference"));
        assertEquals(expected, rows,
                "one reference must mean one order row; the unique index is what guarantees it");
    }

    @Then("the refusal reveals nothing about the other shopper's order")
    public void refusalRevealsNothing() {
        String body = ctx.lastResponse.raw();
        String ownersCode = orderCodeAt(0);
        assertNotNull(ownersCode, "the scenario must place an order first");
        assertFalse(body.contains(ownersCode), "the refusal disclosed the order code: " + body);

        Map<String, Object> owner = Db.one(
                "SELECT uuid::text AS uuid, customer_email FROM customer_order WHERE order_code = ?", ownersCode);
        assertFalse(body.contains(String.valueOf(owner.get("uuid"))), "it disclosed the order uuid: " + body);
        assertFalse(body.toLowerCase().contains(String.valueOf(owner.get("customer_email")).toLowerCase()),
                "it disclosed the owner: " + body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Sends one checkout and records the response so later steps can compare them. */
    private void submit(String token, String reference, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryLocation", DELIVERY);
        body.put("currency", "INR");
        body.put("items", List.of(Map.of("productUuid", ctx.var("productUuid"), "quantity", quantity)));
        if (reference != null) body.put("clientOrderReference", reference);

        ctx.lastResponse = OrderApi.place(token, body);
        ctx.vars.put("lastQuantity", quantity);
        responses().add(ctx.lastResponse);
        rememberOrder(ctx.lastResponse);
    }

    private int lastQuantity() {
        return Integer.parseInt(ctx.var("lastQuantity"));
    }

    @SuppressWarnings("unchecked")
    private List<Http.Response> responses() {
        return (List<Http.Response>) ctx.vars.computeIfAbsent(
                "checkoutResponses", k -> new java.util.ArrayList<Http.Response>());
    }

    /** The order code from the nth submission of the scenario, or null if it was refused. */
    private String orderCodeAt(int index) {
        List<Http.Response> all = responses();
        assertTrue(all.size() > index,
                "the scenario made only " + all.size() + " submission(s), wanted at least " + (index + 1));
        Http.Response r = all.get(index);
        return r.ok() && r.data() != null && r.data().hasNonNull("orderCode")
                ? r.data().get("orderCode").asText() : null;
    }

    /**
     * Anything actually created is removed, whether or not the scenario expected it.
     *
     * Registered once per distinct code: a retry returns the code an earlier
     * submission already produced, and cleaning it up twice would be noise.
     */
    @SuppressWarnings("unchecked")
    private void rememberOrder(Http.Response r) {
        if (r.ok() && r.data() != null && r.data().hasNonNull("orderCode")) {
            String code = r.data().get("orderCode").asText();
            Set<String> registered = (Set<String>) ctx.vars.computeIfAbsent(
                    "checkoutOrderCodes", k -> new java.util.LinkedHashSet<String>());
            if (registered.add(code)) {
                ctx.onCleanup(() -> TestData.removeOrderGraph(code));
            }
            ctx.vars.put("orderCode", code);
        }
    }
}
