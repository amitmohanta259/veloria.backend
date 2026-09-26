package com.app.master.service.payment;

import com.app.master.service.core.payment.RefundStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.app.master.service.core.payment.RefundStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The refund state machine.
 *
 * <p>The states exist so that a refund which is agreed but cannot yet be sent has
 * somewhere honest to sit. A cash-on-delivery refund is exactly that today: the
 * business accepts the money is owed, and there is no approved destination to send
 * it to. These pin down that {@link RefundStatus#APPROVED} is reachable and is not
 * mistaken for either success or failure.
 */
class RefundStatusTest {

    @Test
    @DisplayName("an online refund goes requested → processing → refunded")
    void theOnlinePath() {
        assertTrue(REQUESTED.canMoveTo(PROCESSING), "approved and sent in one call");
        assertTrue(PROCESSING.canMoveTo(REFUNDED));
        assertTrue(REFUNDED.isTerminal());
        assertTrue(REFUNDED.isAccountable(), "and only this state may be posted");
    }

    @Test
    @DisplayName("a refund that is agreed but cannot be sent rests at APPROVED")
    void theBlockedPath() {
        assertTrue(REQUESTED.canMoveTo(APPROVED));
        assertFalse(APPROVED.isTerminal(), "it is waiting, not finished");
        assertFalse(APPROVED.isCommitted(), "and no money has gone anywhere");
        assertFalse(APPROVED.isAccountable(),
                "nothing may be posted for a refund the customer has not received");
        assertTrue(APPROVED.canMoveTo(PROCESSING), "it can be sent once there is somewhere to send it");
    }

    @Test
    @DisplayName("money in flight counts as spent from PROCESSING onwards")
    void inFlightCountsAsSpent() {
        assertFalse(REQUESTED.isCommitted());
        assertFalse(APPROVED.isCommitted());
        assertTrue(PROCESSING.isCommitted(),
                "it may already have left the account; a second send would pay twice");
        assertTrue(REFUNDED.isCommitted());
        assertFalse(FAILED.isCommitted(), "a failure sent nothing");
    }

    @Test
    @DisplayName("only a confirmed refund may be accounted for")
    void onlyRefundedIsAccountable() {
        for (RefundStatus s : values()) {
            assertEquals(s == REFUNDED, s.isAccountable(),
                    s + " must " + (s == REFUNDED ? "" : "not ") + "be accountable");
        }
    }

    @Test
    @DisplayName("a terminal refund cannot change, in either direction")
    void terminalStatesAreFinal() {
        assertTrue(REFUNDED.allowedNext().isEmpty(), "a refunded refund is done");
        assertTrue(FAILED.allowedNext().isEmpty(), "and so is a failed one");
        assertFalse(FAILED.canMoveTo(REFUNDED), "a failure may not quietly become a success");
        assertFalse(REFUNDED.canMoveTo(FAILED));
    }

    @Test
    @DisplayName("a refund cannot go backwards, and cannot skip the gateway")
    void noBackwardsAndNoSkipping() {
        assertFalse(PROCESSING.canMoveTo(REQUESTED));
        assertFalse(PROCESSING.canMoveTo(APPROVED));
        assertFalse(APPROVED.canMoveTo(REQUESTED));
        assertFalse(REQUESTED.canMoveTo(REFUNDED),
                "a refund cannot be confirmed without having been sent");
        assertFalse(APPROVED.canMoveTo(REFUNDED),
                "being agreed is not the same as having been paid");
    }

    @Test
    @DisplayName("every state can fail, except the two that have already finished")
    void anythingUnfinishedCanFail() {
        for (RefundStatus s : EnumSet.of(REQUESTED, APPROVED, PROCESSING)) {
            assertTrue(s.canMoveTo(FAILED), s + " must be able to fail");
        }
    }

    @Test
    @DisplayName("statuses are read case-insensitively, and an unknown one is not invented")
    void parsing() {
        assertEquals(REFUNDED, RefundStatus.of("refunded").orElseThrow());
        assertEquals(PROCESSING, RefundStatus.of("  PROCESSING  ").orElseThrow());
        assertTrue(RefundStatus.of("COMPLETED").isEmpty(),
                "the old name is not silently accepted as the new one");
        assertTrue(RefundStatus.of(null).isEmpty());
    }
}
