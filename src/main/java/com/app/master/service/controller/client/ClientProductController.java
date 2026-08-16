package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client")
@RequiredArgsConstructor
public class ClientProductController extends AppController {

    private final ClientProductService clientProductService;

    @GetMapping("/products/new-in")
    public ResponseEntity<Response> getNewArrivals() {
        return success(ResponseCode.OK, "New arrivals fetched", clientProductService.getNewArrivals());
    }

    @GetMapping("/products/all")
    public ResponseEntity<Response> getAllProducts() {
        return success(ResponseCode.OK, "Products fetched", clientProductService.getAllProducts());
    }

    @GetMapping("/products/{uuid}")
    public ResponseEntity<Response> getProductDetail(@PathVariable UUID uuid) throws VeloriaException {
        return success(ResponseCode.OK, "Product fetched", clientProductService.getProductDetail(uuid));
    }

    @GetMapping("/products/popular")
    public ResponseEntity<Response> getPopularProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(ResponseCode.OK, "Popular products fetched", clientProductService.getPopularProducts(page, size));
    }

    @GetMapping("/categories")
    public ResponseEntity<Response> getCategories() {
        return success(ResponseCode.OK, "Categories fetched", clientProductService.getCategories());
    }
}
