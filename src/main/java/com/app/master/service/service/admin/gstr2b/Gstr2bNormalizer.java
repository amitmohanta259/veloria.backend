package com.app.master.service.service.admin.gstr2b;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.GstRoundingService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Field-level normalization shared by every GSTR-2B parser version.
 *
 * The government payload states money in rupees as decimals and dates as
 * {@code dd-MM-yyyy}. Everything downstream works in integer paise and
 * {@link LocalDate}, so the conversion happens exactly once, here, using the
 * same rounding authority the rest of the GST engine uses.
 */
@Component
@RequiredArgsConstructor
public class Gstr2bNormalizer {

    /** GSTN writes dates as dd-MM-yyyy; a few exports use dd/MM/yyyy. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final GstRoundingService rounding;

    /** Rupees (decimal) to paise, through the engine's single rounding authority. */
    public long paise(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0L;
        if (v.isNumber()) return rounding.rupeesToPaise(v.decimalValue());
        String s = v.asText("").trim();
        if (s.isEmpty()) return 0L;
        try {
            return rounding.rupeesToPaise(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** A required string, or a refusal naming what was missing and where. */
    public String required(JsonNode node, String field, String where) throws VeloriaException {
        String v = optional(node, field);
        if (v == null || v.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B " + where + " is missing the mandatory field '" + field + "'");
        }
        return v;
    }

    public String optional(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A required date, refused rather than defaulted when unreadable. */
    public LocalDate requiredDate(JsonNode node, String field, String where) throws VeloriaException {
        String raw = required(node, field, where);
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, f);
            } catch (Exception ignored) {
                // try the next known layout
            }
        }
        throw new VeloriaException(ResponseCode.BAD_REQUEST,
                "GSTR-2B " + where + " has an unreadable date '" + raw + "'. Expected dd-MM-yyyy.");
    }

    /**
     * Maps a note type flag to the invoice type the reconciliation engine records.
     * GSTN uses {@code C} for credit and {@code D} for debit notes.
     */
    public String noteType(String flag, String where) throws VeloriaException {
        if (flag == null) return "CREDIT_NOTE";
        return switch (flag.trim().toUpperCase()) {
            case "C" -> "CREDIT_NOTE";
            case "D" -> "DEBIT_NOTE";
            default -> throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B " + where + " has an unknown note type '" + flag + "'. Expected C or D.");
        };
    }
}
