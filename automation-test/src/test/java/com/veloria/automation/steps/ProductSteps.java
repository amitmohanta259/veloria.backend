package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.ProductApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProductSteps {

    private final ScenarioContext ctx;

    public ProductSteps(ScenarioContext ctx) { this.ctx = ctx; }

    @When("popular products page {int} of size {int} are requested")
    public void popularRequested(int page, int size) {
        ctx.vars.put("pageSize", size);
        ctx.lastResponse = ProductApi.popular(page, size);
    }

    @When("that product's detail is requested")
    public void chosenProductDetailRequested() {
        ctx.lastResponse = ProductApi.byUuid(ctx.var("productUuid"));
    }

    @When("the detail of a product that does not exist is requested")
    public void unknownProductDetailRequested() {
        ctx.lastResponse = ProductApi.byUuid(UUID.randomUUID().toString());
    }

    @Then("the product list is not empty")
    public void productListNotEmpty() {
        JsonNode d = ctx.lastResponse.data();
        assertTrue(d != null && d.isArray() && d.size() > 0, "expected products, got: " + ctx.lastResponse.raw());
    }

    @Then("every product has a uuid, a name and a price")
    public void everyProductHasCardFields() {
        for (JsonNode p : ctx.lastResponse.data()) {
            assertTrue(p.hasNonNull("uuid"), "missing uuid: " + p);
            assertTrue(p.hasNonNull("name"), "missing name: " + p);
            assertTrue(p.hasNonNull("price") || p.hasNonNull("sellingPrice"), "missing price: " + p);
        }
    }

    @Then("at most {int} products are returned")
    public void atMostNProducts(int n) {
        assertTrue(ctx.lastResponse.data().size() <= n, "got " + ctx.lastResponse.data().size());
    }

    @Then("the detail is for the chosen product")
    public void detailIsForChosenProduct() {
        assertEquals(ctx.var("productUuid"), ctx.lastResponse.data().get("uuid").asText());
        assertTrue(ctx.lastResponse.data().hasNonNull("price") || ctx.lastResponse.data().hasNonNull("sellingPrice"));
    }

    @Then("the category list is not empty")
    public void categoryListNotEmpty() {
        JsonNode d = ctx.lastResponse.data();
        assertTrue(d != null && d.isArray() && d.size() > 0, "no categories: " + ctx.lastResponse.raw());
    }
}
