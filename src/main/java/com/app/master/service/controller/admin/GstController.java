package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.GstHsnMasterEntity;
import com.app.master.service.core.entity.GstStateMasterEntity;
import com.app.master.service.core.entity.GstTaxRuleEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstHsnMasterRepository;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import com.app.master.service.repository.admin.GstTaxRuleRepository;
import com.app.master.service.service.admin.GstCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst")
@RequiredArgsConstructor
public class GstController extends AppController {

    private final GstTaxRuleRepository ruleRepository;
    private final GstHsnMasterRepository hsnRepository;
    private final GstStateMasterRepository stateRepository;
    private final GstCalculationService calculationService;

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/rules")
    public ResponseEntity<Response> getAllRules() {
        List<GstTaxRuleEntity> rules = ruleRepository.findByActiveTrueOrderByPriorityDescHsnCode();
        return data(ResponseCode.FETCHED, null, rules);
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/rules")
    public ResponseEntity<Response> createRule(@RequestBody GstTaxRuleRequest req) {
        GstTaxRuleEntity entity = GstTaxRuleEntity.builder()
                .uuid(UUID.randomUUID())
                .hsnCode(req.hsnCode())
                .hsnMatchType(req.hsnMatchType() != null ? req.hsnMatchType() : "PREFIX")
                .description(req.description())
                .minPricePaise(req.minPricePaise())
                .maxPricePaise(req.maxPricePaise())
                .cgstRateBp(req.cgstRateBp())
                .sgstRateBp(req.sgstRateBp())
                .igstRateBp(req.igstRateBp())
                .priority(req.priority() != null ? req.priority() : 100)
                .effectiveFrom(req.effectiveFrom())
                .effectiveTo(req.effectiveTo())
                .active(true)
                .created(Instant.now())
                .build();
        ruleRepository.save(entity);
        return success(ResponseCode.CREATED, "GST rule created", entity);
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PatchMapping("/rules/{uuid}/toggle")
    public ResponseEntity<Response> toggleRule(@PathVariable UUID uuid) throws VeloriaException {
        GstTaxRuleEntity rule = ruleRepository.findByUuidAndActiveTrue(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Rule not found"));
        rule.setActive(!rule.getActive());
        rule.setModified(Instant.now());
        ruleRepository.save(rule);
        return success(ResponseCode.UPDATED, Map.of("active", rule.getActive()));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @PostMapping("/calculate")
    public ResponseEntity<Response> calculate(@RequestBody GstCalculateRequest req) {
        GstCalculationService.GstResult result = calculationService.calculate(
                req.hsnCode(),
                req.pricePaise(),
                req.buyerStateCode(),
                req.sellerStateCode(),
                req.effectiveDate() != null ? req.effectiveDate() : LocalDate.now()
        );
        return data(ResponseCode.OK, null, result);
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/hsn/search")
    public ResponseEntity<Response> searchHsn(@RequestParam(required = false) String q) {
        List<GstHsnMasterEntity> results;
        if (q == null || q.isBlank()) {
            results = hsnRepository.findAll();
        } else if (q.matches("\\d+")) {
            results = hsnRepository.findByHsnCodeStartingWithAndActiveTrueOrderByHsnCode(q);
        } else {
            results = hsnRepository.findByDescriptionContainingIgnoreCaseAndActiveTrueOrderByHsnCode(q);
        }
        return data(ResponseCode.FETCHED, null, results);
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/states")
    public ResponseEntity<Response> getStates() {
        List<GstStateMasterEntity> states = stateRepository.findAllByActiveTrueOrderByStateName();
        return data(ResponseCode.FETCHED, null, states);
    }

    // Request records
    record GstTaxRuleRequest(
            String hsnCode,
            String hsnMatchType,
            String description,
            Long minPricePaise,
            Long maxPricePaise,
            Integer cgstRateBp,
            Integer sgstRateBp,
            Integer igstRateBp,
            Integer priority,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}

    record GstCalculateRequest(
            String hsnCode,
            long pricePaise,
            String buyerStateCode,
            String sellerStateCode,
            LocalDate effectiveDate
    ) {}
}
