package com.incidentplatform.ingestion.normalizer;

import com.incidentplatform.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class NormalizationException extends BusinessException {

    private final String source;
    private final String reason;

    public NormalizationException(String source, String reason) {
        super(
                "NORMALIZATION_FAILED",
                String.format("Failed to normalize alert from source '%s': %s",
                        source, reason),
                HttpStatus.BAD_REQUEST
        );
        this.source = source;
        this.reason = reason;
    }

    public NormalizationException(String source, String reason, Throwable cause) {
        super(
                "NORMALIZATION_FAILED",
                String.format("Failed to normalize alert from source '%s': %s",
                        source, reason),
                cause
        );
        this.source = source;
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }
}