package com.incidentplatform.ingestion.normalizer;

import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.springframework.http.HttpStatus;

// Fixed (backlog #71): removed the unused 3-arg constructor
// (source, reason, Throwable cause) — every one of the 6 call sites
// across all 3 normalizers uses the 2-arg form; the cause-accepting
// overload had no caller anywhere in the codebase.
public class NormalizationException extends BusinessException {

    private final String source;
    private final String reason;

    public NormalizationException(String source, String reason) {
        super(
                ErrorCodes.NORMALIZATION_FAILED,
                String.format("Failed to normalize alert from source '%s': %s",
                        source, reason),
                HttpStatus.BAD_REQUEST
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