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

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/inventory-product")
public class InventoryProductController extends AppController {

    private final InventoryProductService service;

    public InventoryProductController(InventoryProductService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createInventoryProduct(@RequestPart("product") InventoryProduct product,
                                                           @RequestPart("images") List<MultipartFile> images) throws VeloriaException {

        service.createInventoryProduct(product, images);
        return success(ResponseCode.CREATED, "Inventory product created successfully");
    }

}