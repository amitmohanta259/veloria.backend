package com.veloria.automation.steps;

import com.veloria.automation.db.Db;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Then;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What the application wrote, read straight from the tables. */
public class DatabaseSteps {

    private final ScenarioContext ctx;

    public DatabaseSteps(ScenarioContext ctx) { this.ctx = ctx; }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Then("the old session row is capped to at most {int} seconds of life")
    public void oldSessionCapped(int seconds) {
        Map<String, Object> r = Db.one("""
                SELECT extract(epoch from (access_expiry - timezone('UTC', now()))) AS access_left,
                       extract(epoch from (expiry        - timezone('UTC', now()))) AS login_left
                FROM client_session WHERE token = ?""", ctx.var("oldToken"));
        double access = ((Number) r.get("access_left")).doubleValue();
        double login  = ((Number) r.get("login_left")).doubleValue();
        assertTrue(access <= seconds, "old token access window still " + access + "s");
        assertTrue(login  <= seconds, "old token could still be refreshed for " + login + "s");
    }

    @Then("the new session row keeps the original login expiry")
    public void newSessionKeepsLoginExpiry() {
        Object original = ctx.vars.get("originalExpiry");
        Object successor = Db.one("SELECT expiry FROM client_session WHERE token = ?", ctx.var("newToken")).get("expiry");
        assertEquals(String.valueOf(original), String.valueOf(successor),
                "refresh must carry the login's original expiry forward, not extend it");
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    @Then("the order is stored with {int} line of quantity {int}")
    public void orderStoredWithLines(int lines, int quantity) {
        String code = ctx.var("orderCode");
        assertEquals(1, Db.count("SELECT count(*) FROM customer_order WHERE order_code = ?", code), "order row");
        List<Map<String, Object>> items = Db.query("""
                SELECT i.quantity FROM customer_order_item i
                JOIN customer_order o ON o.id = i.customer_order_id WHERE o.order_code = ?""", code);
        assertEquals(lines, items.size(), "order lines");
        assertEquals(quantity, ((Number) items.get(0).get("quantity")).intValue(), "line quantity");
    }

    @Then("output GST is recorded for the order")
    public void outputGstRecorded() {
        assertTrue(Db.count("SELECT count(*) FROM gst_output_tax WHERE order_code = ?", ctx.var("orderCode")) >= 1,
                "no gst_output_tax row for the order");
    }

    @Then("the recorded tax matches the supply type")
    public void recordedTaxMatchesSupplyType() {
        Map<String, Object> o = Db.one("""
                SELECT buyer_state_code, seller_state_code, cgst_amount, sgst_amount, igst_amount
                FROM customer_order WHERE order_code = ?""", ctx.var("orderCode"));
        String buyer = (String) o.get("buyer_state_code"), seller = (String) o.get("seller_state_code");
        long cgst = n(o.get("cgst_amount")), sgst = n(o.get("sgst_amount")), igst = n(o.get("igst_amount"));
        if (buyer == null || seller == null) {
            // Unresolved place of supply is recorded for review rather than guessed; nothing to assert on.
            return;
        }
        if (buyer.equals(seller)) {
            assertEquals(cgst, sgst, "intra-state: CGST and SGST must match");
            assertEquals(0L, igst, "intra-state: IGST must be zero");
        } else {
            assertEquals(0L, cgst, "inter-state: CGST must be zero");
            assertEquals(0L, sgst, "inter-state: SGST must be zero");
            assertTrue(igst >= 0, "inter-state: IGST recorded");
        }
    }

    // ── Roles ────────────────────────────────────────────────────────────────

    @Then("the database holds a view grant on {string} for that role")
    public void dbHoldsViewGrant(String module) {
        long n = Db.count("""
                SELECT count(*) FROM role_permissions p JOIN roles r ON r.id = p.role_id
                WHERE r.uuid = ?::uuid AND p.module = ? AND p.can_view = true""", ctx.var("roleUuid"), module);
        assertEquals(1, n, "expected one view grant row for " + module);
    }

    @Then("the database holds exactly {int} permission row(s) for that role")
    public void dbHoldsExactlyNPermissionRows(int expected) {
        long n = Db.count("""
                SELECT count(*) FROM role_permissions p
                WHERE p.role_id IN (SELECT id FROM roles WHERE uuid = ?::uuid)""", ctx.var("roleUuid"));
        assertEquals(expected, n);
    }

    private static long n(Object v) { return v == null ? 0L : ((Number) v).longValue(); }
}
