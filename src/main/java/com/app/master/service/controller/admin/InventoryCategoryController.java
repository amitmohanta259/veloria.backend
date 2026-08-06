package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.InventoryCategory;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.InventoryCategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/inventory-category")
public class InventoryCategoryController extends AppController {

    private final InventoryCategoryService service;

    public InventoryCategoryController(InventoryCategoryService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createInventoryCategory(@RequestBody InventoryCategory category) throws VeloriaException {

        service.createInventoryCategory(category);
        return success(ResponseCode.CREATED, "Inventory category created successfully");
    }

    @PutMapping("/update/{uuid}")
    public ResponseEntity<Response> updateInventoryCategory(@PathVariable UUID uuid, @RequestBody InventoryCategory category) throws VeloriaException {

        service.updateInventoryCategory(uuid, category);
        return success(ResponseCode.UPDATED, "Inventory category updated successfully");
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuidInventoryCategory(@PathVariable UUID uuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory category by uuid fetched successfully", service.byUuidInventoryCategory(uuid));
    }

    @GetMapping("/all")
    public ResponseEntity<Response> allInventoryCategory(@RequestParam(required = false, defaultValue = "0") int page,
                                                         @RequestParam(required = false, defaultValue = "10") int pageSize,
                                                         @RequestParam(required = false) String search) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory category list fetched successfully", service.allInventoryCategory(page, pageSize, search));
    }

    @GetMapping("/list")
    public ResponseEntity<Response> listAllInventoryCategory() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Inventory category list fetched successfully", service.listAllInventoryCategory());
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Response> deleteInventoryCategory(@PathVariable UUID uuid) throws VeloriaException {

        service.deleteInventoryCategory(uuid);
        return success(ResponseCode.DELETED, "Inventory category deleted successfully");
    }

}