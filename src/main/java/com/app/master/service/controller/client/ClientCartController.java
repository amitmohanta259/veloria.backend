package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientCartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client/cart")
@RequiredArgsConstructor
public class ClientCartController extends AppController {

    private final ClientCartService cartService;

    @GetMapping
    public ResponseEntity<Response> getCart(@RequestHeader("Authorization") String authHeader) throws VeloriaException {
        return success(ResponseCode.OK, "Cart fetched", cartService.getCart(token(authHeader)));
    }

    @PostMapping("/add")
    public ResponseEntity<Response> addToCart(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody AddToCartRequest request) throws VeloriaException {
        cartService.addToCart(token(authHeader), request);
        return success(ResponseCode.OK, "Added to cart", null);
    }

    @PutMapping("/{productUuid}/quantity")
    public ResponseEntity<Response> updateQuantity(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID productUuid,
            @RequestParam int quantity) throws VeloriaException {
        cartService.updateQuantity(token(authHeader), productUuid, quantity);
        return success(ResponseCode.OK, "Quantity updated", null);
    }

    @DeleteMapping("/{productUuid}")
    public ResponseEntity<Response> removeFromCart(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID productUuid) throws VeloriaException {
        cartService.removeFromCart(token(authHeader), productUuid);
        return success(ResponseCode.OK, "Removed from cart", null);
    }

    private String token(String authHeader) {
        return authHeader.replace("Bearer ", "").trim();
    }
}
