package com.app.master.service.service.admin.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/** XLSX via Apache POI, already a project dependency. */
@Component
public class ExcelReportWriter implements GstReportWriter {

    @Override public String format() { return "XLSX"; }
    @Override public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
    @Override public String fileExtension() { return "xlsx"; }

    @Override
    public byte[] write(GstReport report) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(report.title()));

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);
            titleStyle.setFont(titleFont);

            int r = 0;
            Row titleRow = sheet.createRow(r++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(report.title()
                    + (report.taxPeriod() != null ? "  —  " + report.taxPeriod() : ""));
            titleCell.setCellStyle(titleStyle);
            r++;  // spacer

            Row header = sheet.createRow(r++);
            for (int c = 0; c < report.columns().size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(report.columns().get(c).label());
                cell.setCellStyle(headerStyle);
            }

            for (Map<String, Object> row : report.rows()) {
                Row sheetRow = sheet.createRow(r++);
                for (int c = 0; c < report.columns().size(); c++) {
                    GstReport.Column col = report.columns().get(c);
                    Cell cell = sheetRow.createCell(c);
                    Object v = row.get(col.key());
                    switch (col.type()) {
                        case MONEY -> {
                            cell.setCellValue(ReportValues.rupeesAsDouble(v));
                            cell.setCellStyle(moneyStyle);
                        }
                        case NUMBER -> {
                            if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                            else cell.setCellValue(v == null ? "" : String.valueOf(v));
                        }
                        default -> cell.setCellValue(v == null ? "" : String.valueOf(v));
                    }
                }
            }

            if (!report.summary().isEmpty()) {
                r++;
                for (Map.Entry<String, Object> e : report.summary().entrySet()) {
                    Row sumRow = sheet.createRow(r++);
                    Cell k = sumRow.createCell(0);
                    k.setCellValue(e.getKey());
                    k.setCellStyle(headerStyle);
                    sumRow.createCell(1).setCellValue(String.valueOf(e.getValue()));
                }
            }

            for (int c = 0; c < report.columns().size(); c++) sheet.autoSizeColumn(c);
            sheet.createFreezePane(0, 3);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Excel forbids these characters and caps sheet names at 31 chars. */
    private String safeSheetName(String name) {
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : (s.isEmpty() ? "Report" : s);
    }
}
