package com.incidentplatform.ingestion.normalizer;

import com.incidentplatform.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.List;

public class UnknownSourceException extends BusinessException {

    private final String source;
    private final List<String> availableSources;

    public UnknownSourceException(String source, List<String> availableSources) {
        super(
                "UNKNOWN_ALERT_SOURCE",
                String.format("Unknown alert source: '%s'. Available sources: %s",
                        source, availableSources),
                HttpStatus.BAD_REQUEST
        );
        this.source = source;
        this.availableSources = List.copyOf(availableSources);
    }

    public String getSource() {
        return source;
    }

    public List<String> getAvailableSources() {
        return availableSources;
    }
}