package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.audit.AuditText;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Which failures of a Kafka consumer are worth retrying: one rule for every
 * consumer (backlog #0-96).
 *
 * <p>A transient failure leaves the record for redelivery
 * ({@link DeadLetterPublisher#redeliverLater}); anything else is a record that
 * fails the same way every time and goes to the dead-letter topic, so it never
 * holds its partition (backlog #47's direction, kept).
 *
 * <h2>Fixed (backlog #0-96): a database outage is transient</h2>
 * The escalation and postmortem consumers retried only
 * {@link TransientDataAccessException}, the type Spring names transient. An
 * unreachable database is not one: Spring translates a refused or lost
 * connection to {@link DataAccessResourceFailureException}, which extends
 * {@code NonTransientDataAccessResourceException}, and a {@code @Transactional}
 * method that cannot get a connection fails with
 * {@link CannotCreateTransactionException}, not a {@code DataAccessException}
 * at all. So a database outage sent every event to the dead-letter topic
 * instead of waiting for the database. The notification consumer and
 * {@code IncidentKafkaConsumer} went the other way and treated every
 * exception as transient, which only looked safe because such a record was in
 * fact skipped (see {@link DeadLetterPublisher}).
 */
public final class KafkaFailures {

    private static final int MAX_CAUSE_DEPTH = 32;
    private static final String PLATFORM_PACKAGE = "com.incidentplatform.";

    private KafkaFailures() {
    }

    /**
     * @return whether {@code failure}, or any of its causes, is one that a
     *         later attempt of the same record may not repeat: a database that
     *         is unreachable, timed out, or lost a lock or version race
     */
    public static boolean isTransient(Throwable failure) {
        // Bounded: a cause chain can be made circular.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a dead-letter copy and a log line may say about why a record was
     * refused (backlog #0-96, found in review). A message the platform wrote
     * to be content-free — a refused tenant ({@link TenantResolutionException}),
     * an unknown severity ({@link UnrecognizedSeverityException}), a payload
     * that is not JSON ({@link UnreadableRecordException}) — is kept, on one
     * line ({@link AuditText#error}). Any other exception is named by its
     * type and the platform's frame that threw it ({@link #describe}): a
     * {@code UUID.fromString}, {@code Instant.parse}, Jackson or database
     * message quotes the record's own values, and logs are read across
     * tenants.
     */
    public static String reason(Throwable failure) {
        if (failure instanceof TenantResolutionException
                || failure instanceof UnrecognizedSeverityException
                || failure instanceof UnreadableRecordException) {
            return AuditText.error(failure.getMessage());
        }
        return describe(failure);
    }

    /**
     * An exception by its type and where the platform's code threw it, never
     * its message: {@code IllegalArgumentException at
     * IncidentEventConsumer.handleOpened(IncidentEventConsumer.java:212)}.
     * The first frame in the platform's own packages, else the first frame.
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        final StackTraceElement[] trace = failure.getStackTrace();
        StackTraceElement at = trace.length > 0 ? trace[0] : null;
        for (final StackTraceElement frame : trace) {
            if (frame.getClassName().startsWith(PLATFORM_PACKAGE)) {
                at = frame;
                break;
            }
        }
        return failure.getClass().getSimpleName() + (at == null ? "" : " at "
                + at.getClassName().substring(at.getClassName().lastIndexOf('.') + 1) + "."
                + at.getMethodName() + "(" + at.getFileName() + ":" + at.getLineNumber() + ")");
    }
}
