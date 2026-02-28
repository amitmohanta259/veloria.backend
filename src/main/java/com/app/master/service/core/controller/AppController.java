package com.app.master.service.core.controller;

import com.app.master.service.core.config.AppConfig;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

public class AppController {

    public ResponseEntity<Response> data(Object entity) {
        return data(ResponseCode.ENTITY, null, entity);
    }

    public ResponseEntity<Response> data(ResponseCode code, String message, Object entity) {
        return new ResponseEntity<>(Response.builder()
                .code(code)
                .message(message)
                .data(entity)
                .path(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getRequestURI())
                .requestId(UUID.randomUUID().toString())
                .version(AppConfig.getVersion()).build(), HttpStatus.OK);
    }

    public ResponseEntity<Response> success(ResponseCode code, String message, Object entity) {
        return new ResponseEntity<>(Response.builder()
                .code(code)
                .message(message)
                .data(entity)
                .path(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getRequestURI())
                .requestId(UUID.randomUUID().toString())
                .version("1.0")
                .build(), code == ResponseCode.CREATED ? HttpStatus.CREATED : HttpStatus.OK);
    }

    public ResponseEntity<Response> success(ResponseCode code, Object message) {
        return new ResponseEntity<>(Response.builder()
                .code(code)
                .message(message)
                .path(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getRequestURI())
                .requestId(UUID.randomUUID().toString())
                .version(AppConfig.getVersion())
                .build(), code == ResponseCode.CREATED ? HttpStatus.CREATED : HttpStatus.OK);
    }

}