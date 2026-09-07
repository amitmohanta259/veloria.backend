package com.app.master.service.service.admin.gstr2b;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.Gstr2bService.ParsedRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chooses the parser for a government GSTR-2B payload.
 *
 * Adding a future schema version means adding a {@link Gstr2bParser} bean — this
 * class and the reconciliation engine stay unchanged. When nothing recognises a
 * payload it is refused with the shape it actually had, rather than being handed
 * to whichever parser happens to be first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Gstr2bParserRegistry {

    private final List<Gstr2bParser> parsers;
    private final ObjectMapper objectMapper;

    /** The parser that recognises this payload. */
    public Gstr2bParser select(JsonNode root) throws VeloriaException {
        for (Gstr2bParser p : parsers) {
            if (p.supports(root)) return p;
        }
        throw new VeloriaException(ResponseCode.UNSUPPORTED_MEDIA_TYPE,
                "This does not match any known government GSTR-2B structure "
                + "(expected an object with data.docdata). Top-level fields found: "
                + describe(root) + ". Supported versions: " + supportedVersions()
                + ". For a flat export, import it as JSON instead.");
    }

    /** Parses a government payload into the same rows the CSV reader produces. */
    public List<ParsedRow> parse(byte[] content) throws VeloriaException {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Could not read the GSTR-2B JSON: " + e.getMessage());
        }
        if (root == null || root.isNull()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "The GSTR-2B file is empty");
        }
        Gstr2bParser parser = select(root);
        List<ParsedRow> rows = parser.parse(root);
        log.info("GSTR-2B parsed by {} → {} row(s)", parser.schemaVersion(), rows.size());
        return rows;
    }

    public List<String> supportedVersions() {
        return parsers.stream().map(Gstr2bParser::schemaVersion).sorted().toList();
    }

    private String describe(JsonNode root) {
        if (root == null || !root.isObject()) return "not a JSON object";
        List<String> names = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "none" : names.toString();
    }
}
