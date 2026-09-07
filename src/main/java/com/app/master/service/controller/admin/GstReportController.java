package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.GstReportExportEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstReportExportRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.report.GstReport;
import com.app.master.service.service.admin.report.GstReportService;
import com.app.master.service.service.admin.report.GstReportWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GST report viewing and export (spec phases 18 and 19).
 *
 * Viewing needs VIEW_GST; exporting needs EXPORT_GST_REPORT, because an export
 * leaves the application. Every export is tenant-scoped and audit-logged with
 * the user, organization, report type, period, format and size.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst/reports")
@Slf4j
public class GstReportController extends AppController {

    private final GstReportService reportService;
    private final GstReportExportRepository exportRepo;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;
    private final Map<String, GstReportWriter> writers;

    public GstReportController(GstReportService reportService,
                               GstReportExportRepository exportRepo,
                               GstAuditService auditService,
                               com.app.master.service.core.security.GstSecurityContext securityContext,
                               List<GstReportWriter> writerList) {
        this.reportService = reportService;
        this.exportRepo = exportRepo;
        this.auditService = auditService;
        this.securityContext = securityContext;
        this.writers = writerList.stream()
                .collect(Collectors.toMap(w -> w.format().toUpperCase(), w -> w));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/types")
    public ResponseEntity<Response> types() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportTypes", GstReportService.REPORT_TYPES);
        out.put("formats", writers.keySet().stream().sorted().toList());
        return data(ResponseCode.FETCHED, "Report catalogue", out);
    }

    /** Renders a report as JSON for on-screen viewing. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/{reportType}")
    public ResponseEntity<Response> view(@PathVariable String reportType,
                                          @RequestParam(required = false) String period)
            throws VeloriaException {
        GstReport report = reportService.build(securityContext.organizationId(), reportType, period);
        return data(ResponseCode.FETCHED, "Report generated", report);
    }

    /**
     * Downloads a report. Requires EXPORT_GST_REPORT — a viewer cannot pull
     * data out of the application.
     */
    @PreAuthorize("hasAuthority('EXPORT_GST_REPORT')")
    @GetMapping("/{reportType}/export")
    public ResponseEntity<byte[]> export(@PathVariable String reportType,
                                          @RequestParam(required = false) String period,
                                          @RequestParam(defaultValue = "CSV") String format)
            throws Exception {

        GstReportWriter writer = writers.get(format.toUpperCase());
        if (writer == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Unsupported format '" + format + "'. Supported: " + writers.keySet());
        }

        Long orgId = securityContext.organizationId();
        GstReport report = reportService.build(orgId, reportType, period);
        byte[] body = writer.write(report);

        String filename = reportType.toLowerCase()
                + (period != null ? "-" + period : "")
                + "." + writer.fileExtension();

        exportRepo.save(GstReportExportEntity.builder()
                .reportType(reportType)
                .format(writer.format())
                .taxPeriod(period)
                .rowCount(report.rowCount())
                .byteSize((long) body.length)
                .organizationId(orgId)
                .gstRegistrationId(securityContext.gstRegistrationId())
                .exportedBy(securityContext.actor())
                .build());

        auditService.log("GST_REPORT", null, reportType + " " + writer.format(),
                "REPORT_EXPORTED", period, securityContext.actor());
        log.info("Report exported: type={} format={} period={} rows={} bytes={} by={}",
                reportType, writer.format(), period, report.rowCount(), body.length,
                securityContext.actor());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(writer.contentType()));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    /** The export audit trail. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exports/log")
    public ResponseEntity<Response> exportLog() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Export log",
                exportRepo.findByOrganizationIdOrderByIdDesc(securityContext.organizationId()));
    }
}
