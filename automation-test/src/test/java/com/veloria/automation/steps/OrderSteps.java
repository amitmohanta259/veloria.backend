package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.Http;
import com.veloria.automation.api.OrderApi;
import com.veloria.automation.db.TestData;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OrderSteps {

    private final ScenarioContext ctx;

    public OrderSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── Placing ──────────────────────────────────────────────────────────────

    @When("the buyer places an order for {int} of the chosen product delivered to {string}")
    public void placeOrder(int quantity, String deliveryLocation) {
        ctx.lastResponse = OrderApi.place(ctx.clientToken,
                OrderApi.request(deliveryLocation, ctx.var("productUuid"), quantity));
        captureOrderCode();
    }

    @Given("the buyer has placed an order for {int} of the chosen product delivered to {string}")
    public void hasPlacedOrder(int quantity, String deliveryLocation) {
        placeOrder(quantity, deliveryLocation);
        assertEquals(200, ctx.lastResponse.status(), "could not place the setup order: " + ctx.lastResponse.raw());
        assertTrue(ctx.vars.containsKey("orderCode"), "setup order returned no code");
    }

    @When("the buyer places an order with no items delivered to {string}")
    public void placeOrderNoItems(String deliveryLocation) {
        ctx.lastResponse = OrderApi.place(ctx.clientToken,
                Map.of("deliveryLocation", deliveryLocation, "currency", "INR", "items", List.of()));
        captureOrderCode();   // if the server accepts it, it must still be cleaned up
    }

    @When("the buyer places an order for {int} of the chosen product with no delivery location")
    public void placeOrderNoLocation(int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("currency", "INR");
        body.put("items", List.of(Map.of("productUuid", ctx.var("productUuid"), "quantity", quantity)));
        ctx.lastResponse = OrderApi.place(ctx.clientToken, body);
        captureOrderCode();
    }

    @Then("an order code is returned")
    public void anOrderCodeIsReturned() {
        assertTrue(ctx.vars.containsKey("orderCode"), "no orderCode in: " + ctx.lastResponse.raw());
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @When("the order history is requested")
    public void historyRequested() { ctx.lastResponse = OrderApi.history(ctx.clientToken); }

    @When("the placed order's detail is requested")
    public void detailRequested() { ctx.lastResponse = OrderApi.detail(ctx.clientToken, ctx.var("orderCode")); }

    @When("the second buyer requests the placed order's detail")
    public void detailRequestedByOther() { ctx.lastResponse = OrderApi.detail(ctx.otherClientToken, ctx.var("orderCode")); }

    @When("the buyer requests a return on the placed order")
    public void returnRequested() {
        ctx.lastResponse = OrderApi.requestReturn(ctx.clientToken, ctx.var("orderCode"),
                Map.of("returnType", "RETURN", "reason", "Automation: size did not fit"));
    }

    @Then("the history contains the placed order")
    public void historyContainsOrder() {
        assertTrue(ctx.lastResponse.raw().contains(ctx.var("orderCode")),
                "order " + ctx.var("orderCode") + " not in history: " + ctx.lastResponse.raw());
    }

    @Then("the detail shows the placed order")
    public void detailShowsOrder() {
        JsonNode d = ctx.lastResponse.data();
        assertNotNull(d, ctx.lastResponse.raw());
        assertEquals(ctx.var("orderCode"), d.path("orderCode").asText());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void captureOrderCode() {
        Http.Response r = ctx.lastResponse;
        if (r.ok() && r.data() != null && r.data().hasNonNull("orderCode")) {
            String code = r.data().get("orderCode").asText();
            ctx.vars.put("orderCode", code);
            ctx.onCleanup(() -> TestData.removeOrderGraph(code));
        }
    }
}
