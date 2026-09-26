package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.AddressApi;
import com.veloria.automation.api.BagApi;
import com.veloria.automation.api.FavouriteApi;
import com.veloria.automation.api.Http;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.*;

public class CartSteps {

    private final ScenarioContext ctx;

    public CartSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── Addresses ────────────────────────────────────────────────────────────

    @Given("the buyer has a default delivery address in state {string} with PIN {string}")
    public void buyerHasDefaultAddress(String stateCode, String pincode) {
        Http.Response r = AddressApi.save(ctx.clientToken, stateCode, pincode, true);
        assertTrue(r.ok(), "could not save the buyer's address: " + r.raw());
    }

    // ── Bag ──────────────────────────────────────────────────────────────────

    @When("the chosen product is added to the bag without a session")
    public void addWithoutSession() {
        ctx.lastResponse = BagApi.add(null, ctx.var("productUuid"), 1, null);
    }

    @When("the chosen product is added to the bag with quantity {int}")
    public void addWithQuantity(int qty) {
        ctx.lastResponse = BagApi.add(ctx.clientToken, ctx.var("productUuid"), qty, null);
    }

    @Given("the chosen product is in the bag with quantity {int}")
    public void productInBagWithQuantity(int qty) {
        Http.Response r = BagApi.add(ctx.clientToken, ctx.var("productUuid"), qty, null);
        assertEquals(200, r.status(), "could not seed the bag: " + r.raw());
    }

    @When("the bag quantity of the chosen product is set to {int}")
    public void setQuantity(int qty) {
        ctx.lastResponse = BagApi.updateQuantity(ctx.clientToken, ctx.var("productUuid"), qty);
    }

    @When("the chosen product is removed from the bag")
    public void removeFromBag() {
        ctx.lastResponse = BagApi.remove(ctx.clientToken, ctx.var("productUuid"));
    }

    @When("the second buyer's bag is requested")
    public void secondBuyersBagRequested() {
        ctx.lastResponse = BagApi.get(ctx.otherClientToken);
    }

    @When("the bag GST preview is requested")
    public void bagGstPreview() {
        ctx.lastResponse = BagApi.gstPreview(ctx.clientToken);
    }

    @Then("the bag contains the chosen product with quantity {int}")
    public void bagContainsWithQuantity(int qty) {
        JsonNode line = lineFor(BagApi.get(ctx.clientToken), ctx.var("productUuid"));
        assertNotNull(line, "chosen product not in bag");
        assertEquals(qty, line.get("quantity").asInt());
    }

    @Then("the bag no longer contains the chosen product")
    public void bagNoLongerContains() {
        assertNull(lineFor(BagApi.get(ctx.clientToken), ctx.var("productUuid")), "product still in bag");
    }

    @Then("that bag does not contain the chosen product")
    public void thatBagDoesNotContain() {
        assertNull(lineFor(ctx.lastResponse, ctx.var("productUuid")), "other buyer can see the product");
    }

    // ── Favourites ───────────────────────────────────────────────────────────

    @When("the favourites are requested without a session")
    public void favouritesWithoutSession() { ctx.lastResponse = FavouriteApi.list(null); }

    @When("the chosen product is marked as a favourite")
    public void markFavourite() { ctx.lastResponse = FavouriteApi.add(ctx.clientToken, ctx.var("productUuid")); }

    @When("the chosen product is unmarked as a favourite")
    public void unmarkFavourite() { ctx.lastResponse = FavouriteApi.remove(ctx.clientToken, ctx.var("productUuid")); }

    @Then("the favourites contain the chosen product")
    public void favouritesContain() {
        assertTrue(favouriteUuids().contains(ctx.var("productUuid")), "favourite missing");
    }

    @Then("the favourites do not contain the chosen product")
    public void favouritesDoNotContain() {
        assertFalse(favouriteUuids().contains(ctx.var("productUuid")), "favourite still present");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JsonNode lineFor(Http.Response bag, String productUuid) {
        assertEquals(200, bag.status(), "bag unreadable: " + bag.raw());
        for (JsonNode line : bag.data()) {
            if (productUuid.equals(line.path("productUuid").asText())) return line;
        }
        return null;
    }

    private String favouriteUuids() {
        Http.Response r = FavouriteApi.uuids(ctx.clientToken);
        assertEquals(200, r.status(), r.raw());
        return r.data().toString();
    }
}
