package com.app.master.service.service.admin.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rendered GST report, independent of output format (spec phase 18).
 *
 * Business logic produces one of these; the format writers only turn it into
 * bytes. That keeps the accounting rules in one place instead of being
 * reimplemented per export type.
 */
public record GstReport(
        String reportType,
        String title,
        String taxPeriod,
        List<Column> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> summary,
        Map<String, String> meta
) {

    /**
     * A column. {@code type} drives formatting: MONEY values are stored as
     * integer paise and rendered as rupees, so no writer re-derives the amount.
     */
    public record Column(String key, String label, Type type) {
        public enum Type { TEXT, NUMBER, MONEY, DATE }

        public static Column text(String key, String label)   { return new Column(key, label, Type.TEXT); }
        public static Column number(String key, String label) { return new Column(key, label, Type.NUMBER); }
        public static Column money(String key, String label)  { return new Column(key, label, Type.MONEY); }
        public static Column date(String key, String label)   { return new Column(key, label, Type.DATE); }
    }

    public int rowCount() { return rows == null ? 0 : rows.size(); }

    /** Builder used by the report service to keep call sites readable. */
    public static Builder of(String reportType, String title) {
        return new Builder(reportType, title);
    }

    public static final class Builder {
        private final String reportType;
        private final String title;
        private String taxPeriod;
        private final List<Column> columns = new java.util.ArrayList<>();
        private final List<Map<String, Object>> rows = new java.util.ArrayList<>();
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final Map<String, String> meta = new LinkedHashMap<>();

        Builder(String reportType, String title) {
            this.reportType = reportType;
            this.title = title;
        }

        public Builder period(String p) { this.taxPeriod = p; return this; }
        public Builder column(Column c) { columns.add(c); return this; }
        public Builder row(Map<String, Object> r) { rows.add(r); return this; }
        public Builder summary(String k, Object v) { summary.put(k, v); return this; }
        public Builder meta(String k, String v) { meta.put(k, v); return this; }

        public GstReport build() {
            return new GstReport(reportType, title, taxPeriod,
                    List.copyOf(columns), List.copyOf(rows),
                    Map.copyOf(summary), Map.copyOf(meta));
        }
    }
}
