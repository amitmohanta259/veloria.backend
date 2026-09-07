package com.app.master.service.service.admin.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * PDF via PDFBox, already a project dependency.
 *
 * Landscape A4 with a repeating header. Wide reports are truncated by column
 * rather than overflowing the page, and the footer says so, so a reader is
 * never shown a silently clipped figure.
 */
@Component
public class PdfReportWriter implements GstReportWriter {

    private static final float MARGIN = 32f;
    private static final float ROW_HEIGHT = 15f;
    private static final float FONT_SIZE = 8f;

    @Override public String format() { return "PDF"; }
    @Override public String contentType() { return "application/pdf"; }
    @Override public String fileExtension() { return "pdf"; }

    @Override
    public byte[] write(GstReport report) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float usableWidth = size.getWidth() - 2 * MARGIN;

            // Fit as many columns as the page allows; report the rest as omitted.
            int maxColumns = Math.max(1, (int) (usableWidth / 70f));
            List<GstReport.Column> columns = report.columns().size() > maxColumns
                    ? report.columns().subList(0, maxColumns)
                    : report.columns();
            int omitted = report.columns().size() - columns.size();
            float colWidth = usableWidth / columns.size();

            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            int rowIndex = 0;
            int pageNo = 1;
            while (rowIndex < report.rows().size() || pageNo == 1) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = size.getHeight() - MARGIN;

                    cs.beginText();
                    cs.setFont(bold, 13f);
                    cs.newLineAtOffset(MARGIN, y);
                    cs.showText(sanitise(report.title()
                            + (report.taxPeriod() != null ? "   " + report.taxPeriod() : "")));
                    cs.endText();
                    y -= 22f;

                    cs.beginText();
                    cs.setFont(bold, FONT_SIZE);
                    float x = MARGIN;
                    for (GstReport.Column c : columns) {
                        cs.newLineAtOffset(x == MARGIN ? MARGIN : colWidth, x == MARGIN ? y : 0);
                        cs.showText(sanitise(truncate(c.label(), colWidth)));
                        x += colWidth;
                    }
                    cs.endText();
                    y -= 4f;
                    cs.moveTo(MARGIN, y);
                    cs.lineTo(size.getWidth() - MARGIN, y);
                    cs.stroke();
                    y -= ROW_HEIGHT;

                    cs.setFont(regular, FONT_SIZE);
                    while (rowIndex < report.rows().size() && y > MARGIN + 30f) {
                        Map<String, Object> row = report.rows().get(rowIndex++);
                        cs.beginText();
                        float rx = MARGIN;
                        for (GstReport.Column c : columns) {
                            cs.newLineAtOffset(rx == MARGIN ? MARGIN : colWidth, rx == MARGIN ? y : 0);
                            cs.showText(sanitise(truncate(ReportValues.render(c, row), colWidth)));
                            rx += colWidth;
                        }
                        cs.endText();
                        y -= ROW_HEIGHT;
                    }

                    cs.beginText();
                    cs.setFont(regular, 7f);
                    cs.newLineAtOffset(MARGIN, MARGIN - 12f);
                    String footer = "Page " + pageNo + "   ·   " + report.rowCount() + " rows"
                            + (omitted > 0 ? "   ·   " + omitted + " column(s) omitted — use CSV or XLSX for the full set" : "")
                            + "   ·   Prepared by Veloria. Not a filed GST return.";
                    cs.showText(sanitise(footer));
                    cs.endText();
                }
                pageNo++;
                if (rowIndex >= report.rows().size()) break;
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The Standard-14 fonts are WinAnsi; drop anything they cannot encode. */
    private String sanitise(String s) {
        if (s == null) return "";
        return s.replace("\u20b9", "Rs ").replaceAll("[^\\x20-\\xFF]", "");
    }

    private String truncate(String s, float width) {
        if (s == null) return "";
        int max = Math.max(4, (int) (width / 4.4f));
        return s.length() > max ? s.substring(0, max - 1) + "." : s;
    }
}
