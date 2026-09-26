package com.veloria.automation.steps;

import com.veloria.automation.api.Http;
import com.veloria.automation.db.Db;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Moving an order through its lifecycle over the real API.
 *
 * <p>These steps deliberately talk to {@code /sales-order} rather than to the
 * service, because the point of P0-5A is that the rules hold for whoever calls
 * the endpoint — not only for the admin screen, which used to be the only thing
 * enforcing the sequence.
 *
 * <p>The token comes from {@link ScenarioContext#gstToken}, set by the existing
 * "an admin is signed in to the GST module as" step, so a scenario can choose
 * its authority; scenarios that set none exercise the anonymous case.
 */
public class OrderLifecycleSteps {

    private final ScenarioContext ctx;

    public OrderLifecycleSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── acting ───────────────────────────────────────────────────────────────

    @When("the order status is changed to {string}")
    public void statusChanged(String status) {
        ctx.lastResponse = patchStatus(status, ctx.gstToken);
    }

    @When("the order status is changed to {string} with no credentials")
    public void statusChangedAnonymously(String status) {
        ctx.lastResponse = patchStatus(status, null);
    }

    @When("the order is cancelled with reason {string}")
    public void orderCancelled(String reason) {
        ctx.lastResponse = Http.post(
                "/sales-order/order/" + ctx.var("orderCode") + "/cancel",
                Map.of("reason", reason), ctx.gstToken);
    }

    @When("the sales statistics are requested with no credentials")
    public void statsRequestedAnonymously() {
        ctx.lastResponse = Http.get("/sales-order/stats", null);
    }

    // ── asserting ────────────────────────────────────────────────────────────

    @Then("the order is still {string}")
    public void orderIsStill(String expected) {
        // Read from the database rather than the API: a refused request returns
        // no order, and the question is what actually persisted.
        Object actual = Db.one("SELECT status FROM customer_order WHERE order_code = ?",
                ctx.var("orderCode")).get("status");
        assertEquals(expected, String.valueOf(actual),
                "order " + ctx.var("orderCode") + " holds the wrong status");
    }

    private Http.Response patchStatus(String status, String token) {
        return Http.patch("/sales-order/order/" + ctx.var("orderCode") + "/status",
                Map.of("status", status), token);
    }
}
