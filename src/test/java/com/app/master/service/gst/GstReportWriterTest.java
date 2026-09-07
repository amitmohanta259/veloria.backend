package com.app.master.service.gst;

import com.app.master.service.service.admin.report.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.app.master.service.service.admin.report.GstReport.Column.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The four export writers (spec phase 18).
 *
 * The central guarantee: all four render the same report from the same data, so
 * a CSV and a PDF of one report cannot disagree. Money is stored as integer
 * paise and converted to rupees in exactly one place.
 */
class GstReportWriterTest {

    private GstReport report;

    @BeforeEach
    void setUp() {
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("invoice", "FY26-27/INV/000001");
        row1.put("customer", "Acme, Traders");     // comma, to exercise CSV quoting
        row1.put("qty", 3);
        row1.put("taxable", 251860L);              // Rs 2,518.60 in paise
        row1.put("cgst", 22667L);

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("invoice", "FY26-27/INV/000002");
        row2.put("customer", "O\"Brien Ltd");      // quote, to exercise escaping
        row2.put("qty", 1);
        row2.put("taxable", 100000L);
        row2.put("cgst", 2500L);

        report = GstReport.of("GST_SALES", "GST Sales Report")
                .period("2026-08")
                .column(text("invoice", "Invoice"))
                .column(text("customer", "Customer"))
                .column(number("qty", "Qty"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .row(row1).row(row2)
                .summary("Total tax (Rs)", "251.67")
                .meta("disclaimer", "Not a filed GST return.")
                .build();
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV renders money as rupees and escapes commas and quotes")
    void csvRendersAndEscapes() throws Exception {
        String csv = new String(new CsvReportWriter().write(report), StandardCharsets.UTF_8);

        assertTrue(csv.contains("Invoice,Customer,Qty,Taxable,CGST"), "Header row");
        assertTrue(csv.contains("2518.60"), "Paise converted to rupees");
        assertTrue(csv.contains("\"Acme, Traders\""), "A comma forces quoting");
        assertTrue(csv.contains("\"O\"\"Brien Ltd\""), "A quote is doubled");
        assertTrue(csv.contains("Total tax (Rs)"), "Summary is appended");
    }

    @Test
    @DisplayName("CSV starts with a BOM so Excel reads UTF-8 correctly")
    void csvHasBom() throws Exception {
        byte[] out = new CsvReportWriter().write(report);
        String s = new String(out, StandardCharsets.UTF_8);
        assertEquals('﻿', s.charAt(0));
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON keeps money as integer paise so precision is not lost")
    void jsonKeepsPaise() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(new JsonReportWriter().write(report));

        assertEquals("GST_SALES", doc.get("reportType").asText());
        assertEquals("2026-08", doc.get("taxPeriod").asText());
        assertEquals(2, doc.get("rowCount").asInt());
        assertEquals(251860L, doc.get("rows").get(0).get("taxable").asLong(),
                "Paise, not rupees — downstream systems keep full precision");
        assertTrue(doc.get("note").asText().contains("paise"));
    }

    @Test
    @DisplayName("JSON carries the column types so a consumer can format correctly")
    void jsonCarriesColumnTypes() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(new JsonReportWriter().write(report));
        JsonNode taxableCol = doc.get("columns").get(3);
        assertEquals("taxable", taxableCol.get("key").asText());
        assertEquals("MONEY", taxableCol.get("type").asText());
    }

    // ── XLSX ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("XLSX writes money as a number, not text, so totals work")
    void excelWritesNumericMoney() throws Exception {
        byte[] out = new ExcelReportWriter().write(report);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out))) {
            Sheet sheet = wb.getSheetAt(0);
            assertTrue(sheet.getRow(0).getCell(0).getStringCellValue().contains("GST Sales Report"));
            assertEquals("Invoice", sheet.getRow(2).getCell(0).getStringCellValue());

            var taxableCell = sheet.getRow(3).getCell(3);
            assertEquals(2518.60, taxableCell.getNumericCellValue(), 0.001,
                    "Money is numeric in rupees so a spreadsheet can sum it");
            assertEquals(3.0, sheet.getRow(3).getCell(2).getNumericCellValue(), 0.001);
        }
    }

    @Test
    @DisplayName("XLSX sheet name stays within Excel's limits")
    void excelSheetNameIsSafe() throws Exception {
        GstReport longTitle = GstReport.of("X", "A report title far longer than Excel permits on a sheet tab")
                .column(text("a", "A")).build();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(new ExcelReportWriter().write(longTitle)))) {
            assertTrue(wb.getSheetName(0).length() <= 31);
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PDF produces a valid document")
    void pdfIsValid() throws Exception {
        byte[] out = new PdfReportWriter().write(report);

        assertTrue(out.length > 500, "PDF has content");
        assertEquals("%PDF", new String(out, 0, 4, StandardCharsets.ISO_8859_1));
        assertTrue(new String(out, StandardCharsets.ISO_8859_1).contains("%%EOF"));
    }

    @Test
    @DisplayName("An empty report still produces a valid file in every format")
    void emptyReportIsStillValid() throws Exception {
        GstReport empty = GstReport.of("GST_SALES", "GST Sales Report")
                .period("2026-09")
                .column(text("invoice", "Invoice"))
                .column(money("taxable", "Taxable"))
                .build();

        assertTrue(new CsvReportWriter().write(empty).length > 0);
        assertTrue(new JsonReportWriter().write(empty).length > 0);
        assertTrue(new ExcelReportWriter().write(empty).length > 0);
        byte[] pdf = new PdfReportWriter().write(empty);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1));
    }

    // ── Consistency across formats ───────────────────────────────────────────

    @Test
    @DisplayName("Every writer declares its own format, type and extension")
    void writersDeclareThemselves() {
        assertEquals("CSV", new CsvReportWriter().format());
        assertEquals("csv", new CsvReportWriter().fileExtension());
        assertEquals("text/csv", new CsvReportWriter().contentType());

        assertEquals("XLSX", new ExcelReportWriter().format());
        assertEquals("PDF", new PdfReportWriter().format());
        assertEquals("application/pdf", new PdfReportWriter().contentType());
        assertEquals("JSON", new JsonReportWriter().format());
    }

    @Test
    @DisplayName("CSV and XLSX show the same rupee figure for the same cell")
    void csvAndExcelAgree() throws Exception {
        String csv = new String(new CsvReportWriter().write(report), StandardCharsets.UTF_8);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(new ExcelReportWriter().write(report)))) {
            double excelValue = wb.getSheetAt(0).getRow(3).getCell(3).getNumericCellValue();
            assertTrue(csv.contains(String.format("%.2f", excelValue)),
                    "The same paise figure renders identically in both formats");
        }
    }
}
