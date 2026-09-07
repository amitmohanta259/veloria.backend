package com.app.master.service.gst;

import com.app.master.service.service.admin.GstRoundingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rounding policy and the return-share invariants from spec section 51.
 */
class GstRoundingServiceTest {

    private final GstRoundingService rounding = new GstRoundingService();

    @Test
    @DisplayName("2.5% of 1000 paise rounds HALF_UP, not truncated")
    void halfUpNotTruncated() {
        // 1000 * 250 / 10000 = 25 exactly
        assertEquals(25L, rounding.taxOn(1000L, 250));

        // 999 * 250 / 10000 = 24.975 -> 25 HALF_UP (old truncation gave 24)
        assertEquals(25L, rounding.taxOn(999L, 250));

        // 100 * 250 / 10000 = 2.5 -> 3 HALF_UP
        assertEquals(3L, rounding.taxOn(100L, 250));
    }

    @Test
    @DisplayName("Zero rate and zero value produce zero tax")
    void zeroCases() {
        assertEquals(0L, rounding.taxOn(100000L, 0));
        assertEquals(0L, rounding.taxOn(0L, 900));
    }

    @Test
    @DisplayName("Taxable value is quantity times unit price")
    void taxableValueUsesQuantity() {
        assertEquals(300000L, rounding.taxableValue(100000L, 3));
        assertEquals(100000L, rounding.taxableValue(100000L, 1));
    }

    @Test
    @DisplayName("Returning every unit reverses exactly the original amount")
    void fullReturnReversesExactly() {
        long original = 4444L;
        assertEquals(original, rounding.proportionalShare(original, 3, 3));
        assertEquals(original, rounding.remainingShare(original, 3, 3, 0, 0L));
    }

    @Test
    @DisplayName("Partial return reverses a proportional share")
    void partialReturnIsProportional() {
        // 1 of 3 units from 3000 paise -> 1000
        assertEquals(1000L, rounding.proportionalShare(3000L, 1, 3));
        // 2 of 3 -> 2000
        assertEquals(2000L, rounding.proportionalShare(3000L, 2, 3));
    }

    @Test
    @DisplayName("Repeat partial returns sum to exactly the original, never more")
    void repeatReturnsSumExactly() {
        // 10000 paise over 3 units does not divide evenly (3333.33)
        long original = 10000L;
        int qty = 3;

        long first = rounding.remainingShare(original, 1, qty, 0, 0L);
        long runningQty = 1, runningAmt = first;

        long second = rounding.remainingShare(original, 1, qty, (int) runningQty, runningAmt);
        runningQty += 1; runningAmt += second;

        // Final unit settles the remainder
        long third = rounding.remainingShare(original, 1, qty, (int) runningQty, runningAmt);
        runningAmt += third;

        assertEquals(original, runningAmt,
                "Sum of reversals must equal the original amount exactly");
        assertTrue(runningAmt <= original, "Reversals must never exceed the original");
    }

    @Test
    @DisplayName("A multi-unit final return also settles the remainder exactly")
    void multiUnitFinalReturnSettles() {
        long original = 10000L;
        long first = rounding.remainingShare(original, 1, 3, 0, 0L);
        long rest = rounding.remainingShare(original, 2, 3, 1, first);
        assertEquals(original, first + rest);
    }

    @Test
    @DisplayName("Rupees convert to paise with HALF_UP, not truncation")
    void rupeesToPaiseRounds() {
        assertEquals(100L, rounding.rupeesToPaise(new BigDecimal("1.00")));
        assertEquals(1235L, rounding.rupeesToPaise(new BigDecimal("12.345")));
        assertEquals(0L, rounding.rupeesToPaise(null));
    }

    @Test
    @DisplayName("Returning zero or negative units reverses nothing")
    void nonPositiveReturnsAreZero() {
        assertEquals(0L, rounding.proportionalShare(5000L, 0, 3));
        assertEquals(0L, rounding.proportionalShare(5000L, -1, 3));
        assertEquals(0L, rounding.proportionalShare(5000L, 1, 0));
    }
}
