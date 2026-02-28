package com.app.master.service.core.exception;

import com.app.master.service.core.config.AppConfig;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class AppExceptionHandler extends ResponseEntityExceptionHandler {

    private static final HttpHeaders httpHeaders = new HttpHeaders();

    @ExceptionHandler(VeloriaException.class)
    protected ResponseEntity<Object> handleCustomException(VeloriaException exception, WebRequest request) {
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        switch (exception.getErrorCode()) {
            case UNSUPPORTED_MEDIA_TYPE -> httpStatus = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NOT_FOUND -> httpStatus = HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR, DB_ERROR, IAM_ERROR, AWS_ERROR -> httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return handleExceptionInternal(exception, buildResponse(exception.getErrorCode(), exception.getMessage(), request), httpHeaders, httpStatus, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDaoException(DataIntegrityViolationException exception, WebRequest request) {
        log.error("Database Exception", exception);
        return handleExceptionInternal(exception, buildResponse(ResponseCode.DB_ERROR,
                        NestedExceptionUtils.getMostSpecificCause(exception).getMessage(), request),
                httpHeaders, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.error("HttpMessageNotReadable Exception", exception);
        String message = null;
        if (exception.getCause() instanceof InvalidFormatException) {
            InvalidFormatException ifx = (InvalidFormatException) exception.getCause();
            if (ifx.getTargetType() != null && ifx.getTargetType().isEnum()) {
                message = String.format("Invalid enum value for the field: '%s'. The value must be one of: %s.",
                        ifx.getPath().get(ifx.getPath().size() - 1).getFieldName(), Arrays.toString(ifx.getTargetType().getEnumConstants()));
            }
        }
        return handleExceptionInternal(exception, buildResponse(ResponseCode.BAD_REQUEST, message, request), headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception exception, WebRequest request) {
        log.error("Generic Exception", exception);
        return handleExceptionInternal(exception, buildResponse(ResponseCode.INTERNAL_ERROR, exception.getMessage(), request),
                httpHeaders, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Object> handleFeignException(FeignException exception, WebRequest request, HttpServletResponse response) {
        try {
            response.setStatus(exception.status());
            JSONObject jsonObject = new JSONObject(exception.contentUTF8());
            return handleExceptionInternal(exception, buildResponse(ResponseCode.BAD_REQUEST,
                            jsonObject.get("message") != null ? jsonObject.get("message").toString() : "no error message", request),
                    httpHeaders, HttpStatus.BAD_REQUEST, request);
        } catch (Exception e) {
            return handleExceptionInternal(exception, buildResponse(ResponseCode.SERVICE_UNAVAILABLE, exception.getMessage(), request),
                    httpHeaders, HttpStatus.SERVICE_UNAVAILABLE, request);
        }
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDeniedException(AccessDeniedException exception, WebRequest request, HttpServletResponse response) {
        try {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return handleExceptionInternal(exception, buildResponse(ResponseCode.ACCESS_DENIED, "Access denied", request),
                    httpHeaders, HttpStatus.FORBIDDEN, request);
        } catch (Exception e) {
            return handleExceptionInternal(exception, buildResponse(ResponseCode.SERVICE_UNAVAILABLE, exception.getMessage(), request),
                    httpHeaders, HttpStatus.SERVICE_UNAVAILABLE, request);
        }
    }

    @ExceptionHandler(HttpClientErrorException.UnsupportedMediaType.class)
    public ResponseEntity<Object> unsupportedMediaTypeException(HttpClientErrorException.UnsupportedMediaType exception, WebRequest request) {
        return handleExceptionInternal(exception, buildResponse(ResponseCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", request),
                httpHeaders, HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    private Response buildResponse(ResponseCode code, String message, WebRequest request) {
        return Response.builder()
                .code(code)
                .message(message)
                .path(request.getContextPath())
                .requestId(UUID.randomUUID().toString())
                .errors(null)
                .version(AppConfig.getVersion())
                .build();
    }

    private static String extractLastPhrase(String input) {
        Pattern pattern = Pattern.compile("\\[([^]]+)\\]");
        Matcher matcher = pattern.matcher(input);

        String lastPhrase = null;

        // Find all matches
        while (matcher.find()) {
            lastPhrase = matcher.group(1);
        }

        return lastPhrase;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<Object> handleConstraintViolationException(ConstraintViolationException exception, WebRequest request) {
        Map<Integer, List<String>> violationsByRow = new HashMap<>();

        exception.getConstraintViolations().forEach(violation -> {
            Matcher matcher = Pattern.compile("\\[(\\d+)\\]").matcher(violation.getPropertyPath().toString());
            if (matcher.find()) {
                int rowIndex = Integer.parseInt(matcher.group(1));
                String message = "row " + (rowIndex + 1) + ": " + violation.getMessage();
                violationsByRow.computeIfAbsent(rowIndex, k -> new ArrayList<>()).add(message);
            }
        });

        List<String> violations = violationsByRow.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toList());

        String message = "Constraint violation: " + String.join("; ", violations);

        return handleExceptionInternal(exception, buildResponse(ResponseCode.BAD_REQUEST, message, request), new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.error("Method Args Exception", exception);
        String message;
        try {
            message = exception.getBindingResult().getFieldError().getDefaultMessage();
        } catch (Exception e) {
            message = extractLastPhrase(exception.getBindingResult().toString());
            if (StringUtils.isBlank(message)) message = exception.getBindingResult().toString();
        }
        return handleExceptionInternal(exception, buildResponse(ResponseCode.BAD_REQUEST, message, request),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

}