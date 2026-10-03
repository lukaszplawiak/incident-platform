package com.incidentplatform.shared.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KafkaFailures#isTransient} (backlog #0-96): a database outage, in any
 * of the types Spring gives it, is transient; a record's own fault is not.
 */
@DisplayName("KafkaFailures")
class KafkaFailuresTest {

    static Stream<Arguments> transientFailures() {
        return Stream.of(
                Arguments.of(new DataAccessResourceFailureException("connection refused")),
                Arguments.of(new CannotCreateTransactionException("could not open JPA EntityManager")),
                Arguments.of(new QueryTimeoutException("timeout")),
                Arguments.of(new RecoverableDataAccessException("recoverable")),
                Arguments.of(new OptimisticLockingFailureException("race")),
                Arguments.of(new SQLTransientConnectionException("pool exhausted")),
                Arguments.of(new SQLRecoverableException("lost")),
                Arguments.of(new IllegalStateException("wrapped",
                        new RuntimeException(new SQLTransientConnectionException("deep")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transientFailures")
    @DisplayName("transient: the database unreachable, timed out, or a lost race — at any depth")
    void transientFailures(Throwable failure) {
        assertThat(KafkaFailures.isTransient(failure)).isTrue();
    }

    static Stream<Arguments> recordFailures() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("bad UUID")),
                Arguments.of(new NullPointerException()),
                Arguments.of(new DataIntegrityViolationException("constraint")),
                Arguments.of(new java.time.format.DateTimeParseException("bad date", "x", 0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordFailures")
    @DisplayName("not transient: the record's own fault, retried in vain")
    void recordFailures(Throwable failure) {
        assertThat(KafkaFailures.isTransient(failure)).isFalse();
    }

    @Test
    @DisplayName("null and a circular cause chain end the walk")
    void bounded() {
        assertThat(KafkaFailures.isTransient(null)).isFalse();
        final RuntimeException a = new RuntimeException("a");
        final RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(KafkaFailures.isTransient(a)).isFalse();
    }

    @Test
    @DisplayName("reason: a content-free platform message is kept; any other exception by type and the "
            + "platform's frame, never its message (backlog #0-96, found in review)")
    void reason() {
        assertThat(KafkaFailures.reason(new UnreadableRecordException("Unparseable JSON payload (JsonParseException)",
                null))).isEqualTo("Unparseable JSON payload (JsonParseException)");
        assertThat(KafkaFailures.reason(new TenantResolutionException(TenantResolutionException.Reason.MISMATCH,
                "Record tenant refused (mismatch)"))).isEqualTo("Record tenant refused (mismatch)");
        final UnrecognizedSeverityException severity = new UnrecognizedSeverityException("top-secret",
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "handleOpened");
        assertThat(KafkaFailures.reason(severity)).isEqualTo(severity.getMessage())
                .contains("dead-lettered").doesNotContain("top-secret");

        final IllegalArgumentException uuid = assertThrowsFrom(() -> java.util.UUID.fromString("top-secret"));
        assertThat(KafkaFailures.reason(uuid))
                .startsWith("IllegalArgumentException at KafkaFailuresTest.")
                .doesNotContain("top-secret");
        assertThat(KafkaFailures.reason(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("describe: the first frame in the platform's packages, else the first frame")
    void describe() {
        final RuntimeException platform = new IllegalStateException("secret");
        assertThat(KafkaFailures.describe(platform))
                .isEqualTo("IllegalStateException at KafkaFailuresTest.describe(KafkaFailuresTest.java:"
                        + platform.getStackTrace()[0].getLineNumber() + ")");

        final RuntimeException foreign = new IllegalStateException("secret");
        foreign.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.UUID", "fromString", "UUID.java", 10)});
        assertThat(KafkaFailures.describe(foreign)).isEqualTo("IllegalStateException at UUID.fromString(UUID.java:10)");

        final RuntimeException noTrace = new IllegalStateException("secret");
        noTrace.setStackTrace(new StackTraceElement[0]);
        assertThat(KafkaFailures.describe(noTrace)).isEqualTo("IllegalStateException");
    }

    private static IllegalArgumentException assertThrowsFrom(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("expected an IllegalArgumentException");
    }
}
