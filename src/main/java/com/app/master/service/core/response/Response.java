package com.app.master.service.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.Date;
import java.util.Map;

@Data
@SuperBuilder
public class Response {

    @Builder.Default
    private Date date = new Date();
    private ResponseCode code;

    @JsonDeserialize(as = String.class)
    private Object message;

    @JsonDeserialize(as = String.class)
    private Object data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> errors;
    private String path;
    private String requestId;
    private String version;
}
