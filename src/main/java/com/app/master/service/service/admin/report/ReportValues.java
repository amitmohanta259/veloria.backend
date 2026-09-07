package com.app.master.service.service.admin.report;

import java.util.Map;

/**
 * Shared value rendering, so every writer formats a column the same way.
 * Money is stored as integer paise and rendered as rupees exactly once, here.
 */
final class ReportValues {

    private ReportValues() {}

    static String render(GstReport.Column column, Map<String, Object> row) {
        Object v = row.get(column.key());
        if (v == null) return "";
        return switch (column.type()) {
            case MONEY -> rupees(v);
            case NUMBER, TEXT, DATE -> String.valueOf(v);
        };
    }

    /** Paise to rupees, two decimals, no thousands separator so CSV stays numeric. */
    static String rupees(Object paise) {
        if (paise == null) return "";
        long p = paise instanceof Number n ? n.longValue() : 0L;
        return String.format("%.2f", p / 100.0);
    }

    static double rupeesAsDouble(Object paise) {
        long p = paise instanceof Number n ? n.longValue() : 0L;
        return p / 100.0;
    }
}
