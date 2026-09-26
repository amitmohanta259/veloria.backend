package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.ClientPasswordLoginRequest;
import com.app.master.service.core.request.client.ClientRegisterRequest;
import com.app.master.service.core.request.client.SendOtpRequest;
import com.app.master.service.core.request.client.VerifyOtpRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.ClientAuthResponse;
import com.app.master.service.service.client.ClientAuthService;
import com.app.master.service.service.client.ClientSessionStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client")
@RequiredArgsConstructor
public class ClientAuthController extends AppController {

    private final ClientAuthService clientAuthService;
    private final ClientSessionStore sessionStore;

    @PostMapping("/register")
    public ResponseEntity<Response> register(@Valid @RequestBody ClientRegisterRequest request) throws VeloriaException {
        clientAuthService.register(request);
        return success(ResponseCode.OK, "Registration successful. You can now sign in.", null);
    }

    @PostMapping("/login/password")
    public ResponseEntity<Response> loginWithPassword(@Valid @RequestBody ClientPasswordLoginRequest request) throws VeloriaException {
        return success(ResponseCode.OK, "Login successful", clientAuthService.loginWithPassword(request));
    }

    @PostMapping("/login/send-otp")
    public ResponseEntity<Response> sendOtp(@Valid @RequestBody SendOtpRequest request) throws VeloriaException {
        clientAuthService.sendOtp(request);
        return success(ResponseCode.OK, "OTP sent to your registered phone and email.", null);
    }

    @PostMapping("/login/verify-otp")
    public ResponseEntity<Response> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) throws VeloriaException {
        return success(ResponseCode.OK, "Login successful", clientAuthService.verifyOtp(request));
    }

    /**
     * Exchanges the current token for a fresh one. The old value keeps working
     * for a short grace period, so a request already in flight is not cut off.
     */
    @PostMapping("/session/refresh")
    public ResponseEntity<Response> refreshSession(@RequestHeader("Authorization") String authHeader)
            throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        ClientSessionStore.Refreshed r = sessionStore.refresh(token);
        if (r == null) throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        return success(ResponseCode.OK, "Session refreshed", ClientAuthResponse.builder()
                .token(r.token())
                .userId(r.session().userId())
                .name(r.session().name())
                .email(r.session().email())
                .phone(r.session().phone())
                .build());
    }
}
