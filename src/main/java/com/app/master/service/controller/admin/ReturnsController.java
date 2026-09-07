package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.CustomerOrderItemEntity;
import com.app.master.service.core.entity.OrderReturnRequestEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.OrderReturnRequestRepository;
import com.app.master.service.service.admin.ReturnProcessingService;
import com.app.master.service.service.admin.ReturnsService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/returns")
public class ReturnsController extends AppController {

    private final ReturnsService service;
    private final OrderReturnRequestRepository returnRequestRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final ReturnProcessingService returnProcessingService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    public ReturnsController(ReturnsService service,
                             OrderReturnRequestRepository returnRequestRepository,
                             CustomerOrderRepository customerOrderRepository,
                             CustomerOrderItemRepository customerOrderItemRepository,
                             ReturnProcessingService returnProcessingService,
                             com.app.master.service.core.security.GstSecurityContext securityContext) {
        this.service = service;
        this.returnRequestRepository = returnRequestRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.customerOrderItemRepository = customerOrderItemRepository;
        this.returnProcessingService = returnProcessingService;
        this.securityContext = securityContext;
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/all")
    public ResponseEntity<Response> all(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Returns fetched successfully",
                service.getReturnsLog(month, year, search, page, pageSize));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/requests")
    public ResponseEntity<Response> requests(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        String searchParam = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        Page<OrderReturnRequestEntity> result =
                returnRequestRepository.findAllActive(searchParam, PageRequest.of(page, pageSize));
        Page<Map<String, Object>> mapped = result.map(r -> {
            CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(r.getOrderCode()).orElse(null);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("uuid", r.getUuid());
            m.put("orderCode", r.getOrderCode());
            m.put("customerId", r.getCustomerId());
            m.put("returnType", r.getReturnType());
            m.put("reason", r.getReason());
            m.put("hasFrontImage", r.getFrontImage() != null);
            m.put("hasBackImage", r.getBackImage() != null);
            m.put("hasTagImage", r.getTagImage() != null);
            m.put("frontImage", r.getFrontImage());
            m.put("backImage", r.getBackImage());
            m.put("tagImage", r.getTagImage());
            m.put("verificationStatus", r.getVerificationStatus());
            m.put("verificationNotes", r.getVerificationNotes());
            m.put("orderStatus", order != null ? order.getStatus() : null);
            m.put("customerName", order != null ? order.getCustomerName() : null);
            m.put("customerEmail", order != null ? order.getCustomerEmail() : null);
            m.put("createdAt", r.getCreatedAt());
            return m;
        });
        return data(ResponseCode.FETCHED, "Return requests fetched successfully", mapped);
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/stats")
    public ResponseEntity<Response> stats() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Returns stats fetched successfully", service.getStats());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/trends")
    public ResponseEntity<Response> trends(
            @RequestParam(defaultValue = "weekly") String period) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Trends fetched successfully", service.getTrends(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/reasons")
    public ResponseEntity<Response> reasons() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Return reasons fetched successfully", service.getReasons());
    }

    @PreAuthorize("hasAuthority('CREATE_CREDIT_NOTE')")
    @PutMapping("/{itemUuid}/condition")
    public ResponseEntity<Response> setCondition(
            @PathVariable UUID itemUuid,
            @RequestBody Map<String, String> body) throws VeloriaException {
        String condition = body.get("condition");
        if (condition == null || !List.of("PRODUCT_OK", "DAMAGED", "LOST").contains(condition)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid condition. Must be PRODUCT_OK, DAMAGED, or LOST");
        }
        service.setReturnCondition(itemUuid, condition);
        return success(ResponseCode.UPDATED, "Return condition updated successfully");
    }

    /**
     * Admin verifies a returned product after it has been received.
     *
     * Accepts item-level lines with quantities:
     * <pre>
     * {
     *   "items": [ { "orderItemId": 123, "quantity": 2, "condition": "PRODUCT_OK" } ],
     *   "notes": "Customer return verified",
     *   "gstAdjustmentRequired": true
     * }
     * </pre>
     *
     * Omitting "items" returns every unit still outstanding on the order, which
     * keeps the older whole-order call working. The legacy top-level
     * "condition" field is still honoured and applied to all lines.
     *
     * returnCondition drives inventory; gstAdjustmentRequired independently
     * drives whether a credit note is raised.
     */
    @PreAuthorize("hasAuthority('CREATE_CREDIT_NOTE')")
    @PostMapping("/order/{orderCode}/verify")
    public ResponseEntity<Response> verifyProduct(
            @PathVariable String orderCode,
            @RequestBody VerifyReturnRequest body) throws VeloriaException {

        String legacyCondition = body.getCondition();
        if (legacyCondition != null
                && !List.of("PRODUCT_OK", "DAMAGED", "LOST").contains(legacyCondition)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "condition must be PRODUCT_OK, DAMAGED or LOST");
        }

        List<ReturnProcessingService.ReturnLine> lines = body.getItems() == null
                ? List.of()
                : body.getItems().stream()
                    .map(i -> new ReturnProcessingService.ReturnLine(
                            i.getOrderItemId(),
                            i.getQuantity(),
                            i.getCondition() != null ? i.getCondition() : legacyCondition))
                    .toList();

        ReturnProcessingService.VerifyResult result = returnProcessingService.verify(
                orderCode,
                lines,
                body.getNotes(),
                body.getGstAdjustmentRequired(),
                body.getGstAdjustmentReason(),
                currentUser());

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("returnNumber", result.returnNumber());
        payload.put("orderStatus", result.orderStatus());
        payload.put("linesReturned", result.linesReturned());
        payload.put("unitsReturned", result.unitsReturned());
        payload.put("gstAdjustmentStatus", result.gstAdjustmentStatus());
        payload.put("creditNoteNumber", result.creditNoteNumber());
        payload.put("taxableReversedPaise", result.taxableReversedPaise());
        payload.put("taxReversedPaise", result.taxReversedPaise());

        String message = "FAILED".equals(result.gstAdjustmentStatus())
                ? "Return verified, but the GST credit note could not be created and is pending review"
                : "Return verified successfully";

        return data(ResponseCode.OK, message, payload);
    }

    /**
     * Returnable lines for an order: what was bought, what has already come
     * back, and what is still eligible. Drives the admin return UI.
     */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/order/{orderCode}/returnable-items")
    public ResponseEntity<Response> returnableItems(@PathVariable String orderCode) throws VeloriaException {
        CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));

        List<Map<String, Object>> items =
                customerOrderItemRepository.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId())
                        .stream().map(i -> {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("orderItemId", i.getId());
                            m.put("productUuid", i.getProductUuid());
                            m.put("size", i.getSize());
                            m.put("hsnCode", i.getHsnCode());
                            m.put("quantity", i.getQuantity());
                            m.put("returnedQuantity", i.getReturnedQuantity());
                            m.put("remainingReturnableQuantity", i.remainingReturnableQuantity());
                            m.put("unitPricePaise", i.getUnitPricePaise());
                            m.put("taxableValuePaise", i.getTaxableValuePaise());
                            m.put("cgstAmount", i.getCgstAmount());
                            m.put("sgstAmount", i.getSgstAmount());
                            m.put("igstAmount", i.getIgstAmount());
                            m.put("returnCondition", i.getReturnCondition());
                            return m;
                        }).toList();

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("orderCode", orderCode);
        payload.put("orderStatus", order.getStatus());
        payload.put("items", items);
        return data(ResponseCode.FETCHED, "Returnable items fetched successfully", payload);
    }

    /** The authenticated caller, for audit attribution. */
    private String currentUser() {
        return securityContext.actor();
    }

    @lombok.Data
    public static class VerifyReturnRequest {
        private List<VerifyReturnItem> items;
        private String notes;
        /** Legacy whole-order field; applied to every line when set. */
        private String condition;
        private Boolean gstAdjustmentRequired;
        private String gstAdjustmentReason;
    }

    @lombok.Data
    public static class VerifyReturnItem {
        private Long orderItemId;
        private Integer quantity;
        private String condition;
    }
}
