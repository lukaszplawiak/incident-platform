package com.incidentplatform.shared.exception;

import com.incidentplatform.shared.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password", "secret", "token", "apiKey", "api_key",
            "authorization", "credential", "privateKey"
    );

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex) {

        log.info("Resource not found: {} with id: {}",
                ex.getResourceType(), ex.getResourceId());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        HttpStatus.NOT_FOUND.value(),
                        "RESOURCE_NOT_FOUND",
                        ex.getMessage(),
                        getRequestId()
                ));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex) {

        log.warn("Business rule violation: errorCode={}, message={}",
                ex.getErrorCode(), ex.getMessage());

        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ErrorResponse.of(
                        ex.getHttpStatus().value(),
                        ex.getErrorCode(),
                        ex.getMessage(),
                        getRequestId()
                ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleIngestionExceptions(
            RuntimeException ex) {

        final String className = ex.getClass().getSimpleName();

        if ("NormalizationException".equals(className)) {
            log.warn("Alert normalization failed: {}", ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(
                            HttpStatus.BAD_REQUEST.value(),
                            "NORMALIZATION_FAILED",
                            ex.getMessage(),
                            getRequestId()
                    ));
        }

        if ("UnknownSourceException".equals(className)) {
            log.warn("Unknown alert source: {}", ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(
                            HttpStatus.BAD_REQUEST.value(),
                            "UNKNOWN_ALERT_SOURCE",
                            ex.getMessage(),
                            getRequestId()
                    ));
        }

        throw ex;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        final List<ErrorResponse.ValidationError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toValidationError)
                .toList();

        log.info("Validation failed for {} fields: {}",
                errors.size(),
                errors.stream().map(ErrorResponse.ValidationError::field).toList());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validationFailed(getRequestId(), errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex) {

        final List<ErrorResponse.ValidationError> errors = ex.getConstraintViolations()
                .stream()
                .map(violation -> new ErrorResponse.ValidationError(
                        violation.getPropertyPath().toString(),
                        null,
                        violation.getMessage()
                ))
                .toList();

        log.info("Constraint violations: {}", errors.size());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validationFailed(getRequestId(), errors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        log.info("Type mismatch for parameter: {}", ex.getName());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_PARAMETER_TYPE",
                        String.format("Parameter '%s' has invalid type", ex.getName()),
                        getRequestId()
                ));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex) {

        log.warn("Authentication failed: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(
                        HttpStatus.UNAUTHORIZED.value(),
                        "UNAUTHORIZED",
                        "Authentication required. Please provide a valid token.",
                        getRequestId()
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex) {

        log.warn("Access denied: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        HttpStatus.FORBIDDEN.value(),
                        "FORBIDDEN",
                        "You do not have permission to perform this action.",
                        getRequestId()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {

        log.error("Unexpected error occurred, requestId: {}", getRequestId(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_SERVER_ERROR",
                        "An unexpected error occurred. Please contact support " +
                                "with request id: " + getRequestId(),
                        getRequestId()
                ));
    }

    private String getRequestId() {
        final String requestId = MDC.get("requestId");
        return requestId != null ? requestId : "unknown";
    }

    private ErrorResponse.ValidationError toValidationError(FieldError fieldError) {
        final String fieldName = fieldError.getField();

        final String rejectedValue = isSensitiveField(fieldName)
                ? "[REDACTED]"
                : fieldError.getRejectedValue() != null
                ? fieldError.getRejectedValue().toString()
                : null;

        return new ErrorResponse.ValidationError(
                fieldName,
                rejectedValue,
                fieldError.getDefaultMessage()
        );
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) return false;
        final String lowerField = fieldName.toLowerCase();
        return SENSITIVE_FIELD_NAMES.stream()
                .anyMatch(lowerField::contains);
    }
}