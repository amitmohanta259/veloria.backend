package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.InventoryProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/inventory-product")
public class InventoryProductController extends AppController {

    private final InventoryProductService service;

    public InventoryProductController(InventoryProductService service) {
        this.service = service;
    }

    @PostMapping("/create/{subCategoryUuid}")
    public ResponseEntity<Response> createInventoryProduct(@PathVariable UUID subCategoryUuid,
                                                           @RequestPart("product") InventoryProduct product,
                                                           @RequestPart(value = "images", required = false) List<MultipartFile> images) throws VeloriaException {

        service.createInventoryProduct(subCategoryUuid, product, images);
        return success(ResponseCode.CREATED, "Inventory product created successfully");
    }

    @PutMapping("/update/{productUuid}")
    public ResponseEntity<Response> updateInventoryProduct(@PathVariable UUID productUuid,
                                                           @RequestPart("product") InventoryProduct product,
                                                           @RequestPart(value = "images", required = false) List<MultipartFile> images) throws VeloriaException {

        service.updateInventoryProduct(productUuid, product, images);
        return success(ResponseCode.UPDATED, "Inventory product updated successfully");
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> getInventoryProductByUuid(@PathVariable UUID uuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory product by uuid fetched successfully", service.getInventoryProductByUuid(uuid));
    }

    @PutMapping("/toggle")
    public ResponseEntity<Response> toggleInventoryProduct(@RequestParam(required = false) UUID productUuid) throws VeloriaException {

        return success(ResponseCode.UPDATED, service.toggleInventoryProduct(productUuid) ? "Inventory product activated successfully" : "Inventory product de-activated successfully");
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Response> deleteInventoryProduct(@PathVariable UUID uuid) throws VeloriaException {

        service.deleteInventoryProduct(uuid);
        return success(ResponseCode.DELETED, "Inventory product deleted successfully");
    }

    @GetMapping("/all")
    public ResponseEntity<Response> getInventoryProductList(@RequestParam(required = false) UUID subCategoryUuid,
                                                            @RequestParam(required = false, defaultValue = "0") int page,
                                                            @RequestParam(required = false, defaultValue = "10") int pageSize,
                                                            @RequestParam(required = false) String search) throws VeloriaException {

        return data(ResponseCode.FETCHED, "All inventory product fetched successfully", service.getInventoryProductList(subCategoryUuid, page, pageSize, search));
    }

    @GetMapping("/performance-ledger")
    public ResponseEntity<Response> getPerformanceLedger() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Performance ledger fetched successfully", service.getPerformanceLedger());
    }

    @GetMapping("/inventory-stats")
    public ResponseEntity<Response> inventoryStats() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Inventory stats fetched successfully", service.getInventoryStats());
    }

    @PatchMapping("/{uuid}/stock/add")
    public ResponseEntity<Response> addStock(@PathVariable UUID uuid,
                                             @RequestParam String size,
                                             @RequestParam long qty) throws VeloriaException {
        service.addStock(uuid, size, qty);
        return success(ResponseCode.UPDATED, "Stock updated successfully");
    }

    @GetMapping("/top-sellers")
    public ResponseEntity<Response> topSellers(
            @RequestParam(defaultValue = "monthly") String period) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Top sellers fetched successfully", service.getTopSellers(period));
    }

}