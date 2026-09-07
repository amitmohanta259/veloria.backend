package com.app.master.service.service.admin.gstr2b;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.service.admin.Gstr2bService.ParsedRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One version of the government GSTR-2B JSON structure.
 *
 * Why an interface rather than another branch in the service: GSTN versions the
 * 2B payload, and a file from a later version must be *refused* rather than
 * parsed by rules written for an earlier one. Silently mis-reading a tax head is
 * worse than declining the file.
 *
 * Every implementation produces {@link ParsedRow} — the same normalized model
 * the CSV and flat-JSON readers already produce — so the reconciliation and ITC
 * engines are untouched by anything here. This layer only turns bytes into rows.
 */
public interface Gstr2bParser {

    /** Identifier recorded against the import, e.g. {@code gstn-2b-v1.0}. */
    String schemaVersion();

    /**
     * Whether this parser recognises the payload.
     *
     * Implementations must inspect structure, not guess. Returning true for a
     * shape the parser cannot actually read is the failure mode this whole
     * abstraction exists to prevent.
     */
    boolean supports(JsonNode root);

    /**
     * Reads every supply line in the payload into normalized rows.
     *
     * @throws VeloriaException if the payload is recognised but malformed — a
     *         missing GSTIN, an unreadable date, or a section this version does
     *         not know how to read. Never returns a partial result silently.
     */
    List<ParsedRow> parse(JsonNode root) throws VeloriaException;
}
