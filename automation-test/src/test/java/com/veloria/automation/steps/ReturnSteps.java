package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.ReturnsApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.*;

public class ReturnSteps {

    private final ScenarioContext ctx;

    public ReturnSteps(ScenarioContext ctx) { this.ctx = ctx; }

    @When("the returns statistics are requested without a token")
    public void statsWithoutToken() { ctx.lastResponse = ReturnsApi.stats(null); }

    @When("the returns statistics are requested")
    public void statsRequested() { ctx.lastResponse = ReturnsApi.stats(ctx.gstToken); }

    @Then("the return rate equals returns divided by orders")
    public void returnRateIsConsistent() {
        JsonNode s = ctx.lastResponse.data();
        long returns = s.get("totalReturns").asLong(), orders = s.get("totalOrders").asLong();
        double rate = s.get("returnRate").asDouble();
        if (orders == 0) { assertEquals(0.0, rate, 0.0001); return; }
        double expected = returns * 100.0 / orders;
        assertEquals(expected, rate, 0.05, "rate " + rate + " vs " + returns + "/" + orders);
    }
}
