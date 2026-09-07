package com.app.master.service.service.admin.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON, keeping money as integer paise so downstream systems do not lose precision. */
@Component
public class JsonReportWriter implements GstReportWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String format() { return "JSON"; }
    @Override public String contentType() { return "application/json"; }
    @Override public String fileExtension() { return "json"; }

    @Override
    public byte[] write(GstReport report) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("reportType", report.reportType());
        doc.put("title", report.title());
        doc.put("taxPeriod", report.taxPeriod());
        doc.put("meta", report.meta());
        doc.put("columns", report.columns().stream().map(c -> Map.of(
                "key", c.key(), "label", c.label(), "type", c.type().name())).toList());
        doc.put("rows", report.rows());
        doc.put("summary", report.summary());
        doc.put("rowCount", report.rowCount());
        doc.put("note", "Monetary values are integer paise. Divide by 100 for rupees.");
        return mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(doc).getBytes(StandardCharsets.UTF_8);
    }
}
