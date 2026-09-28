package com.app.master.service.core.response;

public enum ResponseCode {

    INTERNAL_ERROR,
    ACCESS_DENIED,
    USER_NOT_FOUND,
    USER_ALREADY_EXIST,
    BAD_REQUEST,
    NOT_FOUND,
    CREATED,
    SERVICE_UNAVAILABLE,
    UNAUTHORIZED,
    DB_ERROR,
    IAM_ERROR,
    AWS_ERROR,
    ENTITY,
    OK,
    UPDATED,
    UNSUPPORTED_MEDIA_TYPE,
    CUSTOM_FORM_CREATED,
    CUSTOM_FORM_UPDATED,
    CUSTOM_FORM_DELETED,
    CUSTOM_FORM_STATUS_CHANGED,
    FORM_DATA_GET_SUCCESSFULLY,
    DELETED,
    FETCHED,
    UPLOADED,
    // 202 — the request was taken but the work is not finished. Added for the
    // Engineering manual anomaly scan, which returns a scan id and runs
    // asynchronously; holding the HTTP request open for the whole scan would tie a
    // browser to a multi-minute database read.
    ACCEPTED,
    CONFLICT,
    GENERIC_ERROR,
    ALREADY_EXIST,
    IMPORTED,
    SYNCED
}