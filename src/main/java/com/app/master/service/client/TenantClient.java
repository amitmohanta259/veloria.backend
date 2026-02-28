package com.app.master.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "backend", path = "/api", url = "http://localhost:8081")
public interface TenantClient {

    @PostMapping("/master/reset-password")
    ResponseEntity<Response> resetPassword(@RequestHeader(name = "X-TENANT-ID") String requester, @RequestParam String iamId,
                                           @RequestParam String password) throws VeloriaException;

}