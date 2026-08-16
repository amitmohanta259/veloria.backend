package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientBagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client/bag")
@RequiredArgsConstructor
public class ClientBagController extends AppController {

    private final ClientBagService bagService;

    @GetMapping
    public ResponseEntity<Response> getBag(@RequestHeader("Authorization") String authHeader) throws VeloriaException {
        return success(ResponseCode.OK, "Bag fetched", bagService.getBag(token(authHeader)));
    }

    @PostMapping("/add")
    public ResponseEntity<Response> addToBag(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody AddToCartRequest request) throws VeloriaException {
        bagService.addToBag(token(authHeader), request);
        return success(ResponseCode.OK, "Added to bag", null);
    }

    @PutMapping("/{productUuid}/quantity")
    public ResponseEntity<Response> updateQuantity(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID productUuid,
            @RequestParam int quantity) throws VeloriaException {
        bagService.updateQuantity(token(authHeader), productUuid, quantity);
        return success(ResponseCode.OK, "Quantity updated", null);
    }

    @DeleteMapping("/{productUuid}")
    public ResponseEntity<Response> removeFromBag(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID productUuid) throws VeloriaException {
        bagService.removeFromBag(token(authHeader), productUuid);
        return success(ResponseCode.OK, "Removed from bag", null);
    }

    private String token(String authHeader) {
        return authHeader.replace("Bearer ", "").trim();
    }
}
