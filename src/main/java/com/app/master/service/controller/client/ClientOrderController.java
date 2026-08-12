package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client")
@RequiredArgsConstructor
public class ClientOrderController extends AppController {

    private final ClientOrderService clientOrderService;

    @PostMapping("/order/place")
    public ResponseEntity<Response> placeOrder(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody PlaceOrderRequest request) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.OK, "Order placed successfully", clientOrderService.placeOrder(token, request));
    }
}
