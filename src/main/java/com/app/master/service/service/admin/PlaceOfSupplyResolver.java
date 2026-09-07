package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstStateMasterEntity;
import com.app.master.service.core.entity.UserAddressEntity;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import com.app.master.service.repository.client.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the place of supply for a customer order.
 *
 * The audit found buyer_state_code NULL on 100% of orders: the client sends the
 * delivery address as free text ("123 MG Road, Bhubaneswar, Odisha 751001") but
 * the resolver only accepted an address UUID, so UUID.fromString threw and the
 * caller defaulted to intra-state.
 *
 * Resolution now tries, in order:
 *   1. the address UUID (preferred — the client is being updated to send this)
 *   2. an exact match of the free text against the customer's saved addresses
 *   3. a state name appearing in the free text, against gst_state_master
 *   4. the leading digits of a 6-digit PIN code, via the postal-circle range
 *
 * If all four fail the result is unresolved and the caller must treat the order
 * as needing review rather than assuming intra-state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceOfSupplyResolver {

    private final UserAddressRepository userAddressRepository;
    private final GstStateMasterRepository stateMasterRepository;

    private static final Pattern PINCODE = Pattern.compile("\\b(\\d{6})\\b");

    public record Resolution(String stateCode, String source, boolean resolved) {
        static Resolution of(String code, String source) {
            return new Resolution(code, source, code != null);
        }
        static Resolution unresolved() {
            return new Resolution(null, "UNRESOLVED", false);
        }
    }

    public Resolution resolve(String deliveryLocation, String customerId) {
        if (deliveryLocation == null || deliveryLocation.isBlank()) {
            return Resolution.unresolved();
        }
        String raw = deliveryLocation.trim();

        Resolution byUuid = byAddressUuid(raw);
        if (byUuid.resolved()) return byUuid;

        Resolution bySaved = bySavedAddressText(raw, customerId);
        if (bySaved.resolved()) return bySaved;

        Resolution byName = byStateName(raw);
        if (byName.resolved()) return byName;

        Resolution byPin = byPincode(raw);
        if (byPin.resolved()) return byPin;

        log.warn("Could not resolve place of supply from delivery location: {}", raw);
        return Resolution.unresolved();
    }

    private Resolution byAddressUuid(String raw) {
        try {
            UUID addressUuid = UUID.fromString(raw);
            return userAddressRepository.findByUuid(addressUuid)
                    .map(UserAddressEntity::getStateCode)
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> Resolution.of(c, "ADDRESS_UUID"))
                    .orElse(Resolution.unresolved());
        } catch (IllegalArgumentException notAUuid) {
            return Resolution.unresolved();
        }
    }

    /** The client currently posts the formatted address text; match it back. */
    private Resolution bySavedAddressText(String raw, String customerId) {
        if (customerId == null) return Resolution.unresolved();
        try {
            List<UserAddressEntity> saved = userAddressRepository
                    .findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(customerId);
            return saved.stream()
                    .filter(a -> raw.equalsIgnoreCase(a.getAddress() == null ? null : a.getAddress().trim()))
                    .map(UserAddressEntity::getStateCode)
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .map(c -> Resolution.of(c, "SAVED_ADDRESS_TEXT"))
                    .orElse(Resolution.unresolved());
        } catch (Exception e) {
            return Resolution.unresolved();
        }
    }

    private Resolution byStateName(String raw) {
        String haystack = raw.toLowerCase(Locale.ROOT);
        return stateMasterRepository.findAllByActiveTrueOrderByStateName().stream()
                .filter(s -> s.getStateName() != null
                        && haystack.contains(s.getStateName().toLowerCase(Locale.ROOT)))
                // longest name wins: "Andhra Pradesh" must beat a bare "Pradesh" substring
                .max((a, b) -> Integer.compare(a.getStateName().length(), b.getStateName().length()))
                .map(GstStateMasterEntity::getStateCode)
                .map(c -> Resolution.of(c, "STATE_NAME"))
                .orElse(Resolution.unresolved());
    }

    /**
     * First two digits of an Indian PIN identify the postal circle, which maps
     * onto a state for all but a handful of shared circles. Used only as a last
     * resort, after the address book and state name have both failed.
     */
    private Resolution byPincode(String raw) {
        Matcher m = PINCODE.matcher(raw);
        if (!m.find()) return Resolution.unresolved();
        String prefix = m.group(1).substring(0, 2);
        return Optional.ofNullable(PIN_PREFIX_TO_STATE.get(prefix))
                .map(c -> Resolution.of(c, "PINCODE"))
                .orElse(Resolution.unresolved());
    }

    /** PIN prefix → GST state code. Unambiguous prefixes only. */
    private static final java.util.Map<String, String> PIN_PREFIX_TO_STATE = java.util.Map.ofEntries(
            java.util.Map.entry("11", "07"), // Delhi
            java.util.Map.entry("12", "06"), // Haryana
            java.util.Map.entry("13", "03"), // Punjab
            java.util.Map.entry("14", "03"),
            java.util.Map.entry("16", "03"),
            java.util.Map.entry("17", "02"), // Himachal Pradesh
            java.util.Map.entry("18", "01"), // Jammu & Kashmir
            java.util.Map.entry("19", "01"),
            java.util.Map.entry("30", "08"), // Rajasthan
            java.util.Map.entry("31", "08"),
            java.util.Map.entry("32", "08"),
            java.util.Map.entry("33", "08"),
            java.util.Map.entry("36", "24"), // Gujarat
            java.util.Map.entry("37", "24"),
            java.util.Map.entry("38", "24"),
            java.util.Map.entry("39", "24"),
            java.util.Map.entry("40", "27"), // Maharashtra
            java.util.Map.entry("41", "27"),
            java.util.Map.entry("42", "27"),
            java.util.Map.entry("43", "27"),
            java.util.Map.entry("44", "27"),
            java.util.Map.entry("45", "23"), // Madhya Pradesh
            java.util.Map.entry("46", "23"),
            java.util.Map.entry("47", "23"),
            java.util.Map.entry("48", "23"),
            java.util.Map.entry("49", "22"), // Chhattisgarh
            java.util.Map.entry("50", "36"), // Telangana
            java.util.Map.entry("51", "37"), // Andhra Pradesh
            java.util.Map.entry("52", "37"),
            java.util.Map.entry("53", "37"),
            java.util.Map.entry("56", "29"), // Karnataka
            java.util.Map.entry("57", "29"),
            java.util.Map.entry("58", "29"),
            java.util.Map.entry("59", "29"),
            java.util.Map.entry("60", "33"), // Tamil Nadu
            java.util.Map.entry("61", "33"),
            java.util.Map.entry("62", "33"),
            java.util.Map.entry("63", "33"),
            java.util.Map.entry("64", "33"),
            java.util.Map.entry("67", "32"), // Kerala
            java.util.Map.entry("68", "32"),
            java.util.Map.entry("69", "32"),
            java.util.Map.entry("70", "19"), // West Bengal
            java.util.Map.entry("71", "19"),
            java.util.Map.entry("72", "19"),
            java.util.Map.entry("73", "19"),
            java.util.Map.entry("74", "19"),
            java.util.Map.entry("75", "21"), // Odisha
            java.util.Map.entry("76", "21"),
            java.util.Map.entry("77", "21"),
            java.util.Map.entry("78", "18"), // Assam
            java.util.Map.entry("80", "10"), // Bihar
            java.util.Map.entry("81", "10"),
            java.util.Map.entry("82", "10"),
            java.util.Map.entry("83", "10"),
            java.util.Map.entry("84", "10"),
            java.util.Map.entry("85", "10"),
            java.util.Map.entry("20", "09"), // Uttar Pradesh
            java.util.Map.entry("21", "09"),
            java.util.Map.entry("22", "09"),
            java.util.Map.entry("23", "09"),
            java.util.Map.entry("25", "09"),
            java.util.Map.entry("26", "09"),
            java.util.Map.entry("27", "09"),
            java.util.Map.entry("28", "09"),
            java.util.Map.entry("29", "09"),
            java.util.Map.entry("79", "18"), // Assam / North East
            java.util.Map.entry("34", "08"), // Rajasthan
            java.util.Map.entry("35", "24")  // Gujarat
    );
}
