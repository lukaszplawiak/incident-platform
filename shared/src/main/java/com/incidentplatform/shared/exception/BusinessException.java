package com.incidentplatform.shared.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final String errorCode;

    private final HttpStatus httpStatus;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public BusinessException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public static BusinessException incidentAlreadyClosed(String incidentId) {
        return new BusinessException(
                "INCIDENT_ALREADY_CLOSED",
                String.format("Incident '%s' is already closed and cannot be modified",
                        incidentId)
        );
    }

    public static BusinessException invalidStatusTransition(
            String currentStatus, String targetStatus) {
        return new BusinessException(
                "INVALID_STATUS_TRANSITION",
                String.format("Cannot transition incident from '%s' to '%s'",
                        currentStatus, targetStatus)
        );
    }

    public static BusinessException onCallNotConfigured(String tenantId) {
        return new BusinessException(
                "ON_CALL_NOT_CONFIGURED",
                String.format("No on-call schedule configured for tenant '%s'. " +
                        "Please configure escalation policies first.", tenantId)
        );
    }

    public static BusinessException conflict(String errorCode, String message) {
        return new BusinessException(errorCode, message, HttpStatus.CONFLICT);
    }
}