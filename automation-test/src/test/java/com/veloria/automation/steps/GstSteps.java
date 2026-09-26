package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.GstApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class GstSteps {

    private final ScenarioContext ctx;

    public GstSteps(ScenarioContext ctx) { this.ctx = ctx; }

    @When("GST is calculated for HSN {string} at {long} paise from state {string} to state {string}")
    public void gstCalculated(String hsn, long pricePaise, String sellerState, String buyerState) {
        // "from seller to buyer": the first state is where the goods leave, the second where they arrive.
        ctx.lastResponse = GstApi.calculate(ctx.gstToken, hsn, pricePaise, buyerState, sellerState,
                LocalDate.now().toString());
    }

    @When("the GST rules are requested")
    public void rulesRequested() { ctx.lastResponse = GstApi.rules(ctx.gstToken); }

    @When("the HSN master is searched")
    public void hsnSearched() { ctx.lastResponse = GstApi.hsnSearch(ctx.gstToken); }

    @Then("the supply is intra-state")
    public void supplyIsIntraState() { assertFalse(calc().get("interState").asBoolean(), "expected intra-state"); }

    @Then("the supply is inter-state")
    public void supplyIsInterState() { assertTrue(calc().get("interState").asBoolean(), "expected inter-state"); }

    @Then("CGST and SGST are equal and IGST is zero")
    public void cgstSgstEqualIgstZero() {
        JsonNode c = calc();
        long cgst = c.get("cgstAmount").asLong(), sgst = c.get("sgstAmount").asLong();
        assertTrue(cgst > 0, "CGST should be charged: " + c);
        assertEquals(cgst, sgst, "CGST and SGST must match: " + c);
        assertEquals(0L, c.get("igstAmount").asLong(), "IGST must be zero intra-state: " + c);
    }

    @Then("IGST is charged and CGST and SGST are zero")
    public void igstChargedOnly() {
        JsonNode c = calc();
        assertTrue(c.get("igstAmount").asLong() > 0, "IGST should be charged: " + c);
        assertEquals(0L, c.get("cgstAmount").asLong(), "CGST must be zero inter-state: " + c);
        assertEquals(0L, c.get("sgstAmount").asLong(), "SGST must be zero inter-state: " + c);
    }

    @Then("total tax equals CGST plus SGST plus IGST")
    public void totalTaxIsSum() {
        JsonNode c = calc();
        long sum = c.get("cgstAmount").asLong() + c.get("sgstAmount").asLong() + c.get("igstAmount").asLong();
        assertEquals(sum, c.get("totalTax").asLong(), "totalTax must be the sum of components: " + c);
    }

    @Then("the IGST rate is {int} basis points")
    public void igstRateIs(int bp) { assertEquals(bp, calc().get("igstRateBp").asInt(), calc().toString()); }

    @Then("the CGST rate is {int} basis points")
    public void cgstRateIs(int bp) { assertEquals(bp, calc().get("cgstRateBp").asInt(), calc().toString()); }

    @Then("the SGST rate is {int} basis points")
    public void sgstRateIs(int bp) { assertEquals(bp, calc().get("sgstRateBp").asInt(), calc().toString()); }

    @Then("the IGST amount is {long} paise")
    public void igstAmountIs(long paise) { assertEquals(paise, calc().get("igstAmount").asLong(), calc().toString()); }

    @Then("at least {int} active rules are returned")
    public void atLeastNActiveRules(int n) {
        long active = 0;
        for (JsonNode r : ctx.lastResponse.data()) if (r.path("active").asBoolean()) active++;
        assertTrue(active >= n, "only " + active + " active rules");
    }

    @Then("the HSN list is not empty")
    public void hsnListNotEmpty() {
        JsonNode d = ctx.lastResponse.data();
        assertTrue(d != null && d.isArray() && d.size() > 0, ctx.lastResponse.raw());
    }

    private JsonNode calc() {
        JsonNode d = ctx.lastResponse.data();
        assertNotNull(d, "no calculation in: " + ctx.lastResponse.raw());
        return d;
    }
}
