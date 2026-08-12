package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.SaveAddressRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.client.ClientProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client")
@RequiredArgsConstructor
public class ClientProfileController extends AppController {

    private final ClientProfileService clientProfileService;

    @GetMapping("/profile")
    public ResponseEntity<Response> getProfile(
            @RequestHeader("Authorization") String authHeader) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.OK, "Profile fetched", clientProfileService.getProfile(token));
    }

    @GetMapping("/address-book")
    public ResponseEntity<Response> getAddressBook(
            @RequestHeader("Authorization") String authHeader) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.OK, "Address book fetched", clientProfileService.getAddressBook(token));
    }

    @PostMapping("/address-book")
    public ResponseEntity<Response> saveAddress(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody SaveAddressRequest request) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.OK, "Address saved", clientProfileService.saveAddress(token, request));
    }
}
