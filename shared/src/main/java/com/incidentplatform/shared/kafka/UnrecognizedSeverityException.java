package com.incidentplatform.shared.kafka;

import java.util.UUID;

public class UnrecognizedSeverityException extends RuntimeException {

    private final String rawSeverity;
    private final UUID incidentId;
    private final String operation;

    public UnrecognizedSeverityException(String rawSeverity,
                                         UUID incidentId,
                                         String operation) {
        super(String.format(
                "Unrecognized severity value '%s' for incidentId=%s " +
                        "during '%s'. Message skipped — check producer/consumer " +
                        "version compatibility.",
                rawSeverity, incidentId, operation));
        this.rawSeverity = rawSeverity;
        this.incidentId = incidentId;
        this.operation = operation;
    }

    public String getRawSeverity() { return rawSeverity; }
    public UUID getIncidentId()    { return incidentId; }
    public String getOperation()   { return operation; }
}