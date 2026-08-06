package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.Supplier;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.SupplierService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/supplier")
public class SupplierController extends AppController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createSupplier(@RequestBody Supplier supplier) throws VeloriaException {
        service.createSupplier(supplier);
        return success(ResponseCode.CREATED, "Supplier created successfully");
    }

    @PutMapping("/update/{uuid}")
    public ResponseEntity<Response> updateSupplier(@PathVariable UUID uuid, @RequestBody Supplier supplier) throws VeloriaException {
        service.updateSupplier(uuid, supplier);
        return success(ResponseCode.UPDATED, "Supplier updated successfully");
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuidSupplier(@PathVariable UUID uuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Supplier fetched successfully", service.byUuidSupplier(uuid));
    }

    @GetMapping("/all")
    public ResponseEntity<Response> allSuppliers(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Suppliers fetched successfully",
                service.allSuppliers(page, pageSize, search, category));
    }

    @GetMapping("/list")
    public ResponseEntity<Response> listAllSuppliers() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Suppliers fetched successfully", service.listAllSuppliers());
    }

    @GetMapping("/stats")
    public ResponseEntity<Response> getSupplierStats() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Supplier stats fetched successfully", service.getSupplierStats());
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Response> deleteSupplier(@PathVariable UUID uuid) throws VeloriaException {
        service.deleteSupplier(uuid);
        return success(ResponseCode.DELETED, "Supplier terminated successfully");
    }
}
