package com.incidentplatform.shared.kafka;

import java.util.UUID;

public class UnrecognizedSeverityException extends RuntimeException {

    private final String rawSeverity;
    private final UUID incidentId;
    private final String operation;

    public UnrecognizedSeverityException(String rawSeverity,
                                         UUID incidentId,
                                         String operation) {
        // Backlog #0-92: the message does not quote the raw value. It comes
        // from the record's payload, and the message goes into log lines and
        // the dead-letter reason; the value stays available through
        // getRawSeverity() for a caller that needs it.
        super(String.format(
                "Unrecognized severity value for incidentId=%s " +
                        "during '%s'. Message skipped — check producer/consumer " +
                        "version compatibility.",
                incidentId, operation));
        this.rawSeverity = rawSeverity;
        this.incidentId = incidentId;
        this.operation = operation;
    }

    public String getRawSeverity() { return rawSeverity; }
    public UUID getIncidentId()    { return incidentId; }
    public String getOperation()   { return operation; }
}