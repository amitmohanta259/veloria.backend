package com.veloria.automation.steps;

import com.veloria.automation.api.BagApi;
import com.veloria.automation.api.ClientAuthApi;
import com.veloria.automation.api.GstApi;
import com.veloria.automation.api.ProductApi;
import com.veloria.automation.db.Db;
import com.veloria.automation.db.TestData;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AuthenticationSteps {

    private final ScenarioContext ctx;

    public AuthenticationSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── Buyers and sessions ──────────────────────────────────────────────────

    @Given("a buyer account exists")
    public void aBuyerAccountExists() {
        ctx.vars.put("buyer", TestData.seedBuyer(ctx));
    }

    @Given("the buyer has a live session")
    public void theBuyerHasALiveSession() {
        ctx.clientToken = TestData.seedLiveSession(ctx, buyer());
    }

    @Given("the buyer has a session whose access lapsed {long} seconds ago and whose login ends in {long} seconds")
    public void sessionLapsedAccessLoginAlive(long lapsedAgo, long loginIn) {
        ctx.clientToken = TestData.seedSession(ctx, buyer(), -lapsedAgo, loginIn);
    }

    @Given("the buyer has a session whose access lapsed {long} seconds ago and whose login ended {long} seconds ago")
    public void sessionEnded(long lapsedAgo, long endedAgo) {
        ctx.clientToken = TestData.seedSession(ctx, buyer(), -lapsedAgo, -endedAgo);
    }

    @Given("a second buyer with a live session exists")
    public void aSecondBuyerWithALiveSessionExists() {
        TestData.Buyer other = TestData.seedBuyer(ctx);
        ctx.otherClientToken = TestData.seedLiveSession(ctx, other);
    }

    @Given("the shopper holds a token the server has never issued")
    public void shopperHoldsUnknownToken() {
        ctx.clientToken = "never-issued-" + UUID.randomUUID();
    }

    // ── Sign-in ──────────────────────────────────────────────────────────────

    @When("a shopper signs in with an unknown email and a wrong password")
    public void signInWithWrongPassword() {
        ctx.lastResponse = ClientAuthApi.loginWithPassword(
                "nobody-" + UUID.randomUUID() + "@automation.veloria.test", "not-the-password");
    }

    // ── Requests with / without a session ────────────────────────────────────

    @When("the shopper's bag is requested without a session")
    public void bagRequestedWithoutSession() { ctx.lastResponse = BagApi.get(null); }

    @When("the shopper's bag is requested")
    public void bagRequested() { ctx.lastResponse = BagApi.get(ctx.clientToken); }

    @When("the shopper's bag is requested with the new token")
    public void bagRequestedWithNewToken() { ctx.lastResponse = BagApi.get(ctx.var("newToken")); }

    @When("the new-in products are requested without a session")
    public void newInWithoutSession() { ctx.lastResponse = ProductApi.newIn(null); }

    @When("the new-in products are requested")
    public void newInWithSession() { ctx.lastResponse = ProductApi.newIn(ctx.clientToken); }

    @When("the categories are requested without a session")
    public void categoriesWithoutSession() { ctx.lastResponse = ProductApi.categories(null); }

    @When("the GST rules are requested without a token")
    public void gstRulesWithoutToken() { ctx.lastResponse = GstApi.rules(null); }

    // ── Refresh ──────────────────────────────────────────────────────────────

    @When("the session is refreshed")
    public void theSessionIsRefreshed() {
        ctx.vars.put("oldToken", ctx.clientToken);
        ctx.vars.put("originalExpiry", Db.one(
                "SELECT expiry FROM client_session WHERE token = ?", ctx.clientToken).get("expiry"));
        ctx.lastResponse = ClientAuthApi.refresh(ctx.clientToken);
        if (ctx.lastResponse.ok() && ctx.lastResponse.data() != null) {
            String fresh = ctx.lastResponse.data().get("token").asText();
            ctx.vars.put("newToken", fresh);
            // The application created this row; make sure it goes when the buyer does.
            TestData.Buyer b = buyer();
            ctx.onCleanup(() -> TestData.forgetSessionsOf(b));
        }
    }

    @Then("a new token different from the old one is returned")
    public void aNewTokenIsReturned() {
        assertNotEquals(ctx.var("oldToken"), ctx.var("newToken"));
    }

    @Then("the old token is still accepted for the bag")
    public void oldTokenStillAccepted() {
        assertEquals(200, BagApi.get(ctx.var("oldToken")).status(), "old token should be in its grace period");
    }

    private TestData.Buyer buyer() {
        Object b = ctx.vars.get("buyer");
        assertNotNull(b, "No buyer seeded — add 'Given a buyer account exists'");
        return (TestData.Buyer) b;
    }
}
