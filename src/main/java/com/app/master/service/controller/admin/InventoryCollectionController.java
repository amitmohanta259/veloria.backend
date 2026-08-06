package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.InventoryCollection;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.InventoryCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/inventory-collection")
public class InventoryCollectionController extends AppController {

    private final InventoryCollectionService service;

    public InventoryCollectionController(InventoryCollectionService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createInventoryCollection(@RequestBody InventoryCollection collection) throws VeloriaException {

        service.createInventoryCollection(collection);
        return success(ResponseCode.CREATED, "Inventory collection created successfully");
    }

    @PutMapping("/update/{uuid}")
    public ResponseEntity<Response> updateInventoryCollection(@PathVariable UUID uuid, @RequestBody InventoryCollection collection) throws VeloriaException {

        service.updateInventoryCollection(uuid, collection);
        return success(ResponseCode.UPDATED, "Inventory collection updated successfully");
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Response> byUuidInventoryCollection(@PathVariable UUID uuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory collection by uuid fetched successfully", service.byUuidInventoryCollection(uuid));
    }

    @GetMapping("/all")
    public ResponseEntity<Response> allInventoryCollection(@RequestParam(required = false, defaultValue = "0") int page,
                                                           @RequestParam(required = false, defaultValue = "10") int pageSize,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(required = false) UUID categoryUuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Inventory collection list fetched successfully", service.allInventoryCollection(page, pageSize, search, categoryUuid));
    }

    @GetMapping("/list")
    public ResponseEntity<Response> listAllInventoryCollection(@RequestParam(required = false) UUID categoryUuid) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Inventory collection list fetched successfully", service.listAllInventoryCollection(categoryUuid));
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Response> deleteInventoryCollection(@PathVariable UUID uuid) throws VeloriaException {

        service.deleteInventoryCollection(uuid);
        return success(ResponseCode.DELETED, "Inventory collection deleted successfully");
    }

}