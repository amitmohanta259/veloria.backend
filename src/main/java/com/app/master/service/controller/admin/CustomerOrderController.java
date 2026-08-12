package com.app.master.service.controller.admin;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.CustomerDetailResponse;
import com.app.master.service.core.response.admin.CustomerOrderResponse;
import com.app.master.service.core.response.admin.CustomerStatsResponse;
import com.app.master.service.core.response.admin.CustomerSummaryResponse;
import com.app.master.service.service.admin.CustomerOrderService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/master/customer-order")
public class CustomerOrderController {

    private final CustomerOrderService customerOrderService;

    public CustomerOrderController(CustomerOrderService customerOrderService) {
        this.customerOrderService = customerOrderService;
    }

    @GetMapping("/by-customer")
    public ResponseEntity<Page<CustomerOrderResponse>> byCustomer(
            @RequestParam String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws VeloriaException {
        return ResponseEntity.ok(customerOrderService.getOrdersByCustomer(customerId, page, pageSize));
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<CustomerOrderResponse> byUuid(@PathVariable UUID uuid) throws VeloriaException {
        return ResponseEntity.ok(customerOrderService.getOrderByUuid(uuid));
    }

    @GetMapping("/stats")
    public ResponseEntity<CustomerStatsResponse> stats(@RequestParam String customerId) {
        return ResponseEntity.ok(customerOrderService.getStats(customerId));
    }

    @GetMapping("/customers")
    public ResponseEntity<Page<CustomerSummaryResponse>> customerList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(customerOrderService.getCustomerList(search, status, page, pageSize));
    }

    @GetMapping("/customers/{customerId}/details")
    public ResponseEntity<CustomerDetailResponse> customerDetail(@PathVariable String customerId) {
        return ResponseEntity.ok(customerOrderService.getCustomerDetail(customerId));
    }
}
