package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.GstAuthApi;
import com.veloria.automation.api.Http;
import com.veloria.automation.api.ProductApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Steps every feature leans on: response assertions, the GST admin sign-in,
 * and picking a real product to work with.
 */
public class CommonSteps {

    private final ScenarioContext ctx;

    public CommonSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── Responses ────────────────────────────────────────────────────────────

    @Then("the response status should be {int}")
    public void responseStatusShouldBe(int expected) {
        Http.Response r = last();
        assertEquals(expected, r.status(),
                () -> "Expected HTTP " + expected + " but got " + r.status() + ": " + r.raw());
    }

    @Then("the response status should not be {int}")
    public void responseStatusShouldNotBe(int notExpected) {
        assertNotEquals(notExpected, last().status(), () -> "Did not expect HTTP " + notExpected + ": " + last().raw());
    }

    @Then("the response message should be {string}")
    public void responseMessageShouldBe(String expected) {
        assertEquals(expected, last().message(), () -> "Body was: " + last().raw());
    }

    @Then("the response message should not be empty")
    public void responseMessageShouldNotBeEmpty() {
        assertFalse(last().message().isBlank(), () -> "No message in: " + last().raw());
    }

    // ── Shared preconditions ─────────────────────────────────────────────────

    @Given("an admin is signed in to the GST module as {string}")
    public void adminSignedInToGst(String role) {
        ctx.gstToken = GstAuthApi.token(role);
    }

    @Given("a product from the catalogue is chosen")
    public void aProductIsChosen() {
        // /products/all, not /products/new-in: new-in only returns products
        // created in the last 30 days, so a fixture built on it empties itself
        // as the seed data ages and every dependent scenario fails for a reason
        // unrelated to the behaviour under test.
        Http.Response r = ProductApi.all();
        assertEquals(200, r.status(), "catalogue must be reachable: " + r.raw());
        JsonNode list = r.data();
        assertTrue(list != null && list.isArray() && list.size() > 0, "catalogue is empty");
        JsonNode p = list.get(0);
        ctx.vars.put("productUuid", p.get("uuid").asText());
        ctx.vars.put("productName", p.get("name").asText());
    }

    private Http.Response last() {
        assertNotNull(ctx.lastResponse, "No request has been made yet");
        return ctx.lastResponse;
    }
}
