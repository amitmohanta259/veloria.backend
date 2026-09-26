package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.FinanceApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.*;

public class FinanceSteps {

    private final ScenarioContext ctx;

    public FinanceSteps(ScenarioContext ctx) { this.ctx = ctx; }

    @When("the dashboard summary is requested")
    public void dashboardRequested() { ctx.lastResponse = FinanceApi.dashboardSummary(); }

    @When("the trial balance is requested")
    public void trialBalanceRequested() { ctx.lastResponse = FinanceApi.trialBalance(null); }

    @When("the reconciliation report is requested")
    public void reconciliationRequested() { ctx.lastResponse = FinanceApi.reconciliation(null); }

    @When("the indicators are requested")
    public void indicatorsRequested() { ctx.lastResponse = FinanceApi.indicators(null); }

    @Then("total orders is at least active orders")
    public void totalAtLeastActive() {
        JsonNode s = ctx.lastResponse.data().get("stats");
        assertTrue(s.get("totalOrders").asLong() >= s.get("activeOrders").asLong(), s.toString());
        assertTrue(s.get("activeOrders").asLong() >= 0, s.toString());
    }

    @Then("total debits equal total credits")
    public void debitsEqualCredits() {
        JsonNode tb = ctx.lastResponse.data();
        assertEquals(tb.get("totalDebitPaise").asLong(), tb.get("totalCreditPaise").asLong(),
                "trial balance is out by " + tb.path("differencePaise").asLong() + " paise");
        assertTrue(tb.get("balanced").asBoolean());
    }

    @Then("no reconciliation check is FAIL")
    public void noCheckIsFail() {
        JsonNode rec = ctx.lastResponse.data();
        StringBuilder failing = new StringBuilder();
        for (JsonNode c : rec.get("checks")) {
            if ("FAIL".equals(c.get("status").asText())) failing.append(c.get("name").asText()).append("; ");
        }
        assertEquals("", failing.toString(), "failing checks: " + failing);
        assertEquals(0, rec.get("failed").asInt());
    }

    @Then("every indicator marked not available or not meaningful has no value")
    public void unsupportedIndicatorsHaveNoValue() {
        for (JsonNode g : ctx.lastResponse.data().get("groups")) {
            for (JsonNode i : g.get("indicators")) {
                String status = i.get("status").asText();
                if ("NOT_AVAILABLE".equals(status) || "NOT_MEANINGFUL".equals(status)) {
                    assertTrue(i.get("value").isNull(), i.get("label").asText() + " presents a number it cannot support");
                }
            }
        }
    }
}
