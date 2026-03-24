package com.incidentplatform.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(

        @JsonProperty("status")
        int status,

        @JsonProperty("errorCode")
        String errorCode,

        @JsonProperty("message")
        String message,

        @JsonProperty("requestId")
        String requestId,

        @JsonProperty("timestamp")
        Instant timestamp,

        @JsonProperty("validationErrors")
        List<ValidationError> validationErrors

) {

    public record ValidationError(

            @JsonProperty("field")
            String field,

            @JsonProperty("rejectedValue")
            String rejectedValue,

            @JsonProperty("message")
            String message
    ) {}

    public static ErrorResponse of(int status, String errorCode,
                                   String message, String requestId) {
        return new ErrorResponse(
                status,
                errorCode,
                message,
                requestId,
                Instant.now(),
                null
        );
    }

    public static ErrorResponse validationFailed(String requestId,
                                                 List<ValidationError> errors) {
        return new ErrorResponse(
                400,
                "VALIDATION_FAILED",
                "Request validation failed. Please check the provided data.",
                requestId,
                Instant.now(),
                List.copyOf(errors)
        );
    }
}