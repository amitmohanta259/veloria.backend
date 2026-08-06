package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.InventorySubCategory;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.InventorySubcategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/inventory-sub-category")
public class InventorySubCategoryController extends AppController {

    private final InventorySubcategoryService service;

    public InventorySubCategoryController(InventorySubcategoryService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createInventorySubCategory(@RequestBody InventorySubCategory subCategory) throws VeloriaException {

        service.createInventorySubCategory(subCategory);
        return success(ResponseCode.CREATED, "Inventory Subcategory created successfully");
    }

    @PutMapping("/update/{uuid}")
    public ResponseEntity<Response> updateInventorySubCategory(@PathVariable UUID uuid, @RequestBody InventorySubCategory subCategory) throws VeloriaException {

        service.updateInventorySubCategory(uuid, subCategory);
        return success(ResponseCode.UPDATED, "Inventory Subcategory updated successfully");
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuidInventorySubCategory(@PathVariable UUID uuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory Subcategory by uuid fetched successfully", service.byUuidInventorySubCategory(uuid));
    }

    @GetMapping("/all")
    public ResponseEntity<Response> allInventorySubCategory(@RequestParam(required = false, defaultValue = "0") int page,
                                                            @RequestParam(required = false, defaultValue = "10") int pageSize,
                                                            @RequestParam(required = false) String search,
                                                            @RequestParam(required = false) UUID collectionUuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory Subcategory list fetched successfully", service.allInventorySubCategory(page, pageSize, search, collectionUuid));
    }

    @GetMapping("/list")
    public ResponseEntity<Response> listAllInventorySubCategory(@RequestParam(required = false) UUID collectionUuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Inventory Subcategory list fetched successfully", service.listAllInventorySubCategory(collectionUuid));
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Response> deleteInventorySubCategory(@PathVariable UUID uuid) throws VeloriaException {

        service.deleteInventorySubCategory(uuid);
        return success(ResponseCode.DELETED, "Inventory Subcategory deleted successfully");
    }

}