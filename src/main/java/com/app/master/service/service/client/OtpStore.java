package com.app.master.service.service.client;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OtpStore {

    private static final long OTP_TTL_SECONDS = 300; // 5 minutes
    private static final int OTP_LENGTH = 6;

    private record OtpEntry(String otp, Instant expiry) {}

    private final Map<String, OtpEntry> store = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public String generate(String identifier) {
        String otp = String.format("%0" + OTP_LENGTH + "d", random.nextInt((int) Math.pow(10, OTP_LENGTH)));
        store.put(identifier.toLowerCase(), new OtpEntry(otp, Instant.now().plusSeconds(OTP_TTL_SECONDS)));
        return otp;
    }

    /** Returns true and removes the entry if OTP is valid and not expired. */
    public boolean verify(String identifier, String otp) {
        OtpEntry entry = store.get(identifier.toLowerCase());
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            store.remove(identifier.toLowerCase());
            return false;
        }
        if (!entry.otp().equals(otp)) {
            return false;
        }
        store.remove(identifier.toLowerCase());
        return true;
    }

}
