package com.app.master.service.service.admin.report;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** RFC 4180 CSV. Money is written in rupees so a spreadsheet totals correctly. */
@Component
public class CsvReportWriter implements GstReportWriter {

    @Override public String format() { return "CSV"; }
    @Override public String contentType() { return "text/csv"; }
    @Override public String fileExtension() { return "csv"; }

    @Override
    public byte[] write(GstReport report) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // A leading BOM so Excel opens UTF-8 correctly.
            w.write('\ufeff');

            for (int i = 0; i < report.columns().size(); i++) {
                if (i > 0) w.write(',');
                w.write(escape(report.columns().get(i).label()));
            }
            w.write("\r\n");

            for (Map<String, Object> row : report.rows()) {
                for (int i = 0; i < report.columns().size(); i++) {
                    if (i > 0) w.write(',');
                    w.write(escape(ReportValues.render(report.columns().get(i), row)));
                }
                w.write("\r\n");
            }

            if (!report.summary().isEmpty()) {
                w.write("\r\n");
                for (Map.Entry<String, Object> e : report.summary().entrySet()) {
                    w.write(escape(e.getKey()));
                    w.write(',');
                    w.write(escape(String.valueOf(e.getValue())));
                    w.write("\r\n");
                }
            }
        }
        return out.toByteArray();
    }

    private String escape(String v) {
        if (v == null) return "";
        boolean needsQuotes = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String s = v.replace("\"", "\"\"");
        return needsQuotes ? '"' + s + '"' : s;
    }
}
