package com.app.master.service.payment;

import com.app.master.service.core.payment.GatewayFeeSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The approved 50/50 division of a gateway fee.
 *
 * <p>The arithmetic is trivial; what these pin down is that the fee is taken from
 * the gateway and never derived from a rate, which is the part a later change
 * could quietly break.
 */
class GatewayFeeSplitTest {

    @Test
    @DisplayName("the approved example: a ₹100 fee is ₹50 expense and ₹50 passed on")
    void approvedExample() {
        GatewayFeeSplit s = GatewayFeeSplit.of(1_000_000L, 10_000L);

        assertEquals(5_000L, s.businessPaise(), "half the fee is the business's expense");
        assertEquals(5_000L, s.customerPaise(), "and half is passed to the customer");
        assertEquals(10_000L, s.businessPaise() + s.customerPaise(), "together, the whole fee");
        assertEquals(990_000L, s.netSettlementPaise(), "the settlement is gross less the fee");
    }

    @Test
    @DisplayName("an odd fee gives the extra paisa to the business, never the customer")
    void oddFeeRoundsTowardsTheBusiness() {
        GatewayFeeSplit s = GatewayFeeSplit.of(100_000L, 2_361L);

        assertEquals(1_181L, s.businessPaise());
        assertEquals(1_180L, s.customerPaise(), "the customer is never charged the odd paisa");
        assertEquals(2_361L, s.businessPaise() + s.customerPaise(), "and nothing is lost");
    }

    @Test
    @DisplayName("every split accounts for the whole fee and nothing more")
    void splitsAreExhaustive() {
        for (long fee = 0; fee <= 5_000; fee++) {
            GatewayFeeSplit s = GatewayFeeSplit.of(1_000_000L, fee);
            assertEquals(fee, s.businessPaise() + s.customerPaise(), "fee " + fee + " must divide exactly");
            assertEquals(1_000_000L - fee, s.netSettlementPaise(), "fee " + fee);
            assertTrue(s.businessPaise() >= s.customerPaise(), "fee " + fee + " may not favour the business less");
        }
    }

    @Test
    @DisplayName("a zero fee splits to nothing rather than to a guess")
    void zeroFee() {
        GatewayFeeSplit s = GatewayFeeSplit.of(50_000L, 0L);
        assertEquals(0L, s.businessPaise());
        assertEquals(0L, s.customerPaise());
        assertEquals(50_000L, s.netSettlementPaise());
    }

    @Test
    @DisplayName("a fee larger than the payment is refused, not divided")
    void feeCannotExceedThePayment() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GatewayFeeSplit.of(10_000L, 10_001L));
        assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
    }

    @Test
    @DisplayName("negative figures are refused")
    void negativesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> GatewayFeeSplit.of(10_000L, -1L));
        assertThrows(IllegalArgumentException.class, () -> GatewayFeeSplit.of(-1L, 0L));
    }

    /**
     * The fee must come from the gateway, so no percentage may appear in the code
     * that handles it.
     *
     * <p>Reads the source rather than the behaviour, because the behaviour of a
     * hardcoded 2% is indistinguishable from a correct split until the day the
     * gateway charges something else. Scanned for the shapes a rate takes: a
     * division or multiplication by a scale factor, and the specific figures the
     * brief forbids.
     */
    @Test
    @DisplayName("no gateway percentage is hardcoded anywhere in the fee path")
    void noHardcodedGatewayPercentage() throws IOException {
        List<Path> feePath = Stream.of(
                        "src/main/java/com/app/master/service/core/payment/GatewayFeeSplit.java",
                        "src/main/java/com/app/master/service/service/payment/PaymentService.java",
                        "src/main/java/com/app/master/service/service/payment/RazorpayGateway.java")
                .map(Path::of).toList();

        for (Path p : feePath) {
            assertTrue(Files.exists(p), p + " must exist for this check to mean anything");
            String source = Files.readString(p);

            // The whole-file scan would trip on unrelated arithmetic, so narrow it
            // to lines that actually mention the fee.
            for (String line : source.split("\n")) {
                String l = line.trim();
                if (l.startsWith("*") || l.startsWith("//")) continue;   // prose, not code
                if (!l.toLowerCase().contains("fee")) continue;
                for (String forbidden : List.of("0.02", "2.36", "0.18", "* 2 /", "/ 100", "* 236")) {
                    assertFalse(l.contains(forbidden),
                            p + " applies a rate to a fee — the fee must come from the gateway: " + l);
                }
            }
        }
    }
}
