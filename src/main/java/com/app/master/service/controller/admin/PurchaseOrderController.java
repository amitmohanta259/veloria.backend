package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.PurchaseOrderRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.PurchaseOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/purchase-order")
public class PurchaseOrderController extends AppController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createPurchaseOrder(@RequestBody PurchaseOrderRequest request) throws VeloriaException {
        UUID uuid = service.createPurchaseOrder(request);
        return data(ResponseCode.CREATED, "Purchase order created successfully", uuid);
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuidPurchaseOrder(@PathVariable UUID uuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Purchase order fetched successfully", service.byUuidPurchaseOrder(uuid));
    }

    @GetMapping("/by-supplier/{supplierUuid}")
    public ResponseEntity<Response> listBySupplier(@PathVariable UUID supplierUuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Purchase orders fetched successfully", service.listBySupplier(supplierUuid));
    }
}
