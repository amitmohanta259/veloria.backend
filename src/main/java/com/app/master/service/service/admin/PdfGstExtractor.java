package com.app.master.service.service.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class PdfGstExtractor {

    public record GstExtractResult(
        String invoiceNumber,
        String vendorGstin,
        BigDecimal taxableValue,
        BigDecimal cgstRate,
        BigDecimal cgstAmount,
        BigDecimal sgstRate,
        BigDecimal sgstAmount,
        BigDecimal igstRate,
        BigDecimal igstAmount,
        BigDecimal totalGst,
        String invoiceDate,
        boolean success
    ) {
        static GstExtractResult empty() {
            return new GstExtractResult(null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, false);
        }
    }

    public GstExtractResult extract(InputStream pdfStream) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("gst_extract_", ".pdf");
            try (OutputStream os = Files.newOutputStream(tmp)) {
                pdfStream.transferTo(os);
            }

            log.info("PDF GST: starting pdfminer subprocess on {}", tmp.toAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(
                "/usr/bin/python3", "-c",
                "import sys; from pdfminer.high_level import extract_text; print(extract_text(sys.argv[1]))",
                tmp.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String text;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                text = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            boolean finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("PDF GST: subprocess timed out after 30s");
                return GstExtractResult.empty();
            }
            log.info("PDF text extracted ({} chars), success={}", text.length(),
                text.contains("CGST") || text.contains("IGST") || text.contains("SGST"));
            GstExtractResult result = parseGstFromText(text);
            log.info("GST result: success={} cgst={} sgst={}", result.success(), result.cgstAmount(), result.sgstAmount());
            return result;
        } catch (Exception e) {
            log.warn("PDF GST extraction failed: {}", e.getMessage());
            return GstExtractResult.empty();
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private GstExtractResult parseGstFromText(String text) {
        String normalised = text.replaceAll("\\s+", " ").trim();

        String invoiceNumber = extractFirst(normalised,
            "(?i)(?:invoice\\s*(?:no|number|#)[:\\s]+|inv[\\s\\-#:]+)([A-Z0-9\\-/]+)");

        String vendorGstin = extractFirst(normalised,
            "(?i)GSTIN[:\\s]*([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1})");
        if (vendorGstin == null) {
            vendorGstin = extractFirst(normalised,
                "([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1})");
        }

        String invoiceDate = extractFirst(normalised,
            "(?i)(?:invoice\\s*date|date)[:\\s]+([0-9]{1,2}[\\-\\/][0-9]{1,2}[\\-\\/][0-9]{2,4})");

        BigDecimal taxableValue = extractAmount(normalised,
            "(?i)(?:taxable\\s*(?:value|amount|supply))[:\\s₹Rs.]*([0-9,]+(?:\\.[0-9]{1,2})?)");

        BigDecimal cgstRate = extractRate(normalised,
            "(?i)CGST\\s*@?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");
        BigDecimal cgstAmount = extractAmount(normalised,
            "(?i)CGST[\\s@%0-9.]*[:\\s₹Rs.]*([0-9,]+(?:\\.[0-9]{1,2})?)");

        BigDecimal sgstRate = extractRate(normalised,
            "(?i)SGST\\s*@?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");
        BigDecimal sgstAmount = extractAmount(normalised,
            "(?i)SGST[\\s@%0-9.]*[:\\s₹Rs.]*([0-9,]+(?:\\.[0-9]{1,2})?)");

        BigDecimal igstRate = extractRate(normalised,
            "(?i)IGST\\s*@?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%");
        BigDecimal igstAmount = extractAmount(normalised,
            "(?i)IGST[\\s@%0-9.]*[:\\s₹Rs.]*([0-9,]+(?:\\.[0-9]{1,2})?)");

        BigDecimal totalGst = cgstAmount.add(sgstAmount).add(igstAmount);
        if (totalGst.compareTo(BigDecimal.ZERO) == 0) {
            totalGst = extractAmount(normalised,
                "(?i)(?:total\\s*(?:gst|tax))[:\\s₹Rs.]*([0-9,]+(?:\\.[0-9]{1,2})?)");
        }

        boolean success = totalGst.compareTo(BigDecimal.ZERO) > 0
            || cgstAmount.compareTo(BigDecimal.ZERO) > 0
            || igstAmount.compareTo(BigDecimal.ZERO) > 0;

        return new GstExtractResult(
            invoiceNumber, vendorGstin, taxableValue,
            cgstRate, cgstAmount, sgstRate, sgstAmount,
            igstRate, igstAmount, totalGst, invoiceDate, success
        );
    }

    private String extractFirst(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (m.find()) {
            String val = m.group(1).trim();
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    private BigDecimal extractAmount(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", "").trim());
            } catch (NumberFormatException ignored) {}
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal extractRate(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).trim());
            } catch (NumberFormatException ignored) {}
        }
        return BigDecimal.ZERO;
    }
}
