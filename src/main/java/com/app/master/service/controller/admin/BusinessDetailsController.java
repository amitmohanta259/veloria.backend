package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.admin.BusinessDetailsRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.BusinessDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/business-details")
@RequiredArgsConstructor
public class BusinessDetailsController extends AppController {

    private final BusinessDetailsService businessDetailsService;

    @GetMapping
    public ResponseEntity<Response> get() throws VeloriaException {
        return success(ResponseCode.FETCHED, "Business details fetched", businessDetailsService.get());
    }

    @PostMapping
    public ResponseEntity<Response> save(@RequestBody BusinessDetailsRequest request) throws VeloriaException {
        return success(ResponseCode.OK, "Business details saved", businessDetailsService.save(request));
    }
}
