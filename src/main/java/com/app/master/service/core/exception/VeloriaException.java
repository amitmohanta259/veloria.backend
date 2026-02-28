package com.app.master.service.core.exception;

import com.app.master.service.core.response.ResponseCode;
import lombok.Getter;

@Getter
public class VeloriaException extends Exception {

    private ResponseCode errorCode;
    private String[] fields;
    private Exception exception;

    public VeloriaException() {
        super("Failed to do operation");
        this.errorCode = ResponseCode.INTERNAL_ERROR;
        this.exception = new RuntimeException();
    }

    public VeloriaException(ResponseCode code, String message, String... fields) {
        super(message);
        this.errorCode = code;
        this.fields = fields;
    }

    public VeloriaException(Exception exception) {
        super(exception.getLocalizedMessage());
        this.errorCode = ResponseCode.INTERNAL_ERROR;
        this.exception = exception;
    }

    public VeloriaException(String message) {
        super(message);
    }

    public VeloriaException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ResponseCode.GENERIC_ERROR;
    }

    public VeloriaException(String message, ResponseCode errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

}