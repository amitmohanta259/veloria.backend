package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.GstAccountingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst-accounting")
public class GstAccountingController extends AppController {

    private final GstAccountingService service;

    public GstAccountingController(GstAccountingService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/summary")
    public ResponseEntity<Response> summary(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "GST summary", service.getSummary(period));
    }

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/input-tax")
    public ResponseEntity<Response> inputTax(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Input tax list", service.getInputTax(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/output-tax")
    public ResponseEntity<Response> outputTax(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Output tax list", service.getOutputTax(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/liability")
    public ResponseEntity<Response> liability(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "GST liability", service.getLiability(period));
    }

    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PatchMapping("/input-tax/{id}/eligibility")
    public ResponseEntity<Response> markEligibility(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        service.markItcEligibility(id, body.get("eligibility"));
        return success(ResponseCode.UPDATED, "ITC eligibility updated", null);
    }
}
