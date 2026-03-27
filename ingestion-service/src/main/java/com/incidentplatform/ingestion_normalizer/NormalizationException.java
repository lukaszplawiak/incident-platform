package com.incidentplatform.ingestion_normalizer;

public class NormalizationException extends RuntimeException {

    private final String source;
    private final String reason;

    public NormalizationException(String source, String reason) {
        super(String.format("Failed to normalize alert from source '%s': %s",
                source, reason));
        this.source = source;
        this.reason = reason;
    }

    public NormalizationException(String source, String reason, Throwable cause) {
        super(String.format("Failed to normalize alert from source '%s': %s",
                source, reason), cause);
        this.source = source;
        this.reason = reason;
    }

    public String getSource() { return source; }
    public String getReason() { return reason; }
}