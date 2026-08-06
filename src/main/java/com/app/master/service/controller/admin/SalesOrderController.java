package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.SalesOrderRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.SalesOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/sales-order")
public class SalesOrderController extends AppController {

    private final SalesOrderService service;

    public SalesOrderController(SalesOrderService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> create(@RequestBody SalesOrderRequest request) throws VeloriaException {
        UUID uuid = service.createSalesOrder(request);
        return success(ResponseCode.CREATED, "Sales order created successfully", uuid);
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuid(@PathVariable UUID uuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Sales order fetched successfully", service.byUuid(uuid));
    }

    @GetMapping("/all")
    public ResponseEntity<Response> allOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Sales orders fetched successfully",
                service.allOrders(status, month, year, search, page, pageSize));
    }

    @GetMapping("/stats")
    public ResponseEntity<Response> stats() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Sales stats fetched successfully", service.getStats());
    }
}
