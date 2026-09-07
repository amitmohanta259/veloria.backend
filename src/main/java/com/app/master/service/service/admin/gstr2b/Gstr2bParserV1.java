package com.app.master.service.service.admin.gstr2b;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.Gstr2bService.ParsedRow;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the nested GSTR-2B JSON structure published by GSTN.
 *
 * Shape (abbreviated):
 * <pre>
 * { "data": { "gstin", "rtnprd", "version",
 *     "docdata": {
 *       "b2b":  [ { "ctin", "trdnm", "inv": [ { "inum","dt","typ","pos",
 *                                              "items": [ { "rt","txval","igst","cgst","sgst","cess" } ] } ] } ],
 *       "cdnr": [ { "ctin", "trdnm", "nt":  [ { "ntnum","dt","typ","items": [ … ] } ] } ]
 *     } } }
 * </pre>
 *
 * Two deliberate design points:
 *
 * 1. <b>Items are summed to one row per document.</b> Reconciliation matches at
 *    document level against our purchase records, so the per-rate breakup is
 *    aggregated rather than carried through. Summing before conversion is not
 *    possible — each value is converted to paise individually so the totals
 *    agree with the engine's rounding everywhere else.
 *
 * 2. <b>An unrecognised section is refused, not skipped.</b> Silently dropping a
 *    section GSTN added later would understate available credit while looking
 *    like a clean import, which is the worst possible failure here.
 *
 * <b>EXTERNAL_DEPENDENCY.</b> This implements the publicly documented structure.
 * It has not been validated against a file downloaded from the GST portal,
 * because no such sample exists in this repository. Until one is supplied and
 * this parser is exercised against it, treat government-file import as
 * unproven — the abstraction and the version gate are what make that safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Gstr2bParserV1 implements Gstr2bParser {

    /** Sections this version knows how to read. */
    private static final Set<String> INVOICE_SECTIONS = Set.of("b2b", "b2ba");
    private static final Set<String> NOTE_SECTIONS    = Set.of("cdnr", "cdnra");

    /** Payload versions this parser accepts. A newer file must be refused. */
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1.0", "1.1");

    private final Gstr2bNormalizer normalizer;

    @Override
    public String schemaVersion() {
        return "gstn-2b-v1";
    }

    @Override
    public boolean supports(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        return data != null && data.has("docdata");
    }

    @Override
    public List<ParsedRow> parse(JsonNode root) throws VeloriaException {
        JsonNode data = root.get("data");
        assertVersionSupported(normalizer.optional(data, "version"));

        JsonNode docdata = data.get("docdata");
        if (docdata == null || !docdata.isObject()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B payload has no 'docdata' section to read");
        }

        assertNoUnreadableSections(docdata);

        List<ParsedRow> rows = new ArrayList<>();
        for (String section : orderedSections(docdata)) {
            JsonNode arr = docdata.get(section);
            if (arr == null || !arr.isArray() || arr.isEmpty()) continue;

            if (INVOICE_SECTIONS.contains(section)) {
                for (JsonNode supplier : arr) rows.addAll(readSupplier(supplier, section, "inv", false));
            } else if (NOTE_SECTIONS.contains(section)) {
                for (JsonNode supplier : arr) rows.addAll(readSupplier(supplier, section, "nt", true));
            }
        }

        log.info("Parsed {} GSTR-2B document(s) from the nested government structure", rows.size());
        return rows;
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private List<String> orderedSections(JsonNode docdata) {
        List<String> out = new ArrayList<>();
        docdata.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /**
     * A section carrying data that this version cannot read is a hard failure.
     * An empty unknown section is harmless and is allowed through.
     */
    private void assertNoUnreadableSections(JsonNode docdata) throws VeloriaException {
        Set<String> unreadable = new LinkedHashSet<>();
        for (String section : orderedSections(docdata)) {
            if (INVOICE_SECTIONS.contains(section) || NOTE_SECTIONS.contains(section)) continue;
            JsonNode arr = docdata.get(section);
            if (arr != null && arr.isArray() && !arr.isEmpty()) unreadable.add(section);
        }
        if (!unreadable.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B contains section(s) this parser cannot read: " + unreadable
                    + ". Importing would understate available credit, so the file is refused. "
                    + "Extend Gstr2bParserV1 to cover them.");
        }
    }

    // ── Documents ────────────────────────────────────────────────────────────

    private List<ParsedRow> readSupplier(JsonNode supplier, String section,
                                         String documentsField, boolean isNote)
            throws VeloriaException {
        String where = "section '" + section + "'";
        String gstin = normalizer.required(supplier, "ctin", where);
        String name  = normalizer.optional(supplier, "trdnm");

        JsonNode documents = supplier.get(documentsField);
        if (documents == null || !documents.isArray()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B " + where + " for " + gstin
                    + " has no '" + documentsField + "' array of documents");
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (JsonNode doc : documents) {
            rows.add(readDocument(doc, gstin, name, section, isNote));
        }
        return rows;
    }

    private ParsedRow readDocument(JsonNode doc, String gstin, String supplierName,
                                   String section, boolean isNote) throws VeloriaException {
        String numberField = isNote ? "ntnum" : "inum";
        String where = "section '" + section + "' for supplier " + gstin;

        String number  = normalizer.required(doc, numberField, where);
        LocalDate date = normalizer.requiredDate(doc, "dt", where + " document " + number);
        String type    = isNote
                ? normalizer.noteType(normalizer.optional(doc, "typ"), where + " document " + number)
                : "INVOICE";

        JsonNode items = doc.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B document " + number + " in " + where + " has no line items");
        }

        // Each value converts to paise individually, then sums — so the total
        // matches what the engine would compute line by line.
        long taxable = 0, cgst = 0, sgst = 0, igst = 0, cess = 0;
        for (JsonNode item : items) {
            taxable += normalizer.paise(item, "txval");
            cgst    += normalizer.paise(item, "cgst");
            sgst    += normalizer.paise(item, "sgst");
            igst    += normalizer.paise(item, "igst");
            cess    += normalizer.paise(item, "cess");
        }

        return new ParsedRow(gstin, supplierName, number, date, type,
                taxable, cgst, sgst, igst, cess);
    }

    // ── Version gate ─────────────────────────────────────────────────────────

    private void assertVersionSupported(String version) throws VeloriaException {
        if (version == null || version.isBlank()) return;   // older exports omit it
        if (!SUPPORTED_VERSIONS.contains(version.trim())) {
            throw new VeloriaException(ResponseCode.UNSUPPORTED_MEDIA_TYPE,
                    "GSTR-2B schema version '" + version + "' is not supported. "
                    + "Supported: " + SUPPORTED_VERSIONS + ". "
                    + "Parsing a newer layout with older rules risks misreading tax heads, "
                    + "so the file is refused.");
        }
    }
}
