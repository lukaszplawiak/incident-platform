package com.incidentplatform.shared.audit;

/**
 * An audit event the caller required to be confirmed was not acknowledged by
 * Kafka (backlog #0-88): serialization failed, the send failed, or no
 * acknowledgement came within the timeout. Thrown only by
 * {@link AuditEventPublisher#publishAuthConfirmed}; the ordinary publish
 * methods log and swallow failures instead.
 */
public class AuditNotConfirmedException extends RuntimeException {

    public AuditNotConfirmedException(String message, Throwable cause) {
        super(message, cause);
    }
}
