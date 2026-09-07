package com.app.master.service.service.admin.report;

/**
 * Turns a {@link GstReport} into bytes. One implementation per format; none of
 * them contain business logic.
 */
public interface GstReportWriter {

    /** CSV, XLSX, PDF, JSON */
    String format();

    String contentType();

    String fileExtension();

    byte[] write(GstReport report) throws Exception;
}
