package com.incidentplatform.shared.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@DisplayName("AuditOutboxRelay (backlog #0-84)")
class AuditOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final AuditEventKafkaSender sender = mock(AuditEventKafkaSender.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private AuditOutboxRelay relay;

    private final AuditOutbox.Pending first = new AuditOutbox.Pending(UUID.randomUUID(), "acme", "A", "{1}", 0);
    private final AuditOutbox.Pending second = new AuditOutbox.Pending(UUID.randomUUID(), "globex", "B", "{2}", 2);

    /** A clock the tests move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private AuditOutboxRelay relay(int batchSize, int maxBatches, Duration sendTimeout) {
        return new AuditOutboxRelay(outbox, sender,
                new AuditOutboxProperties("auth_audit_outbox", batchSize, maxBatches, sendTimeout, Duration.ofDays(7)),
                lockProvider, meters, clock);
    }

    private void acknowledgeEverything() {
        given(sender.sendForRelay(any(), anyString())).willAnswer(i -> CompletableFuture.completedFuture(null));
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuditOutboxRelay.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuditOutboxRelay.class)).detachAppender(appender);
        appender.stop();
    }

    @BeforeEach
    void setUp() {
        given(lockProvider.lock(any())).willReturn(Optional.of(mock(SimpleLock.class)));
        given(outbox.markSent(any())).willAnswer(i -> i.getArgument(0, List.class).size());
        given(outbox.markFailed(any(), any(), anyString())).willReturn(true);
        relay = relay(50, 10, Duration.ofSeconds(3));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("sends each due event with its own tenant and marks it sent after the acknowledgement")
        void sendsAndMarks() {
            given(outbox.due(50)).willReturn(List.of(first, second));
            acknowledgeEverything();

            final AuditOutboxRelay.RunResult result = relay.relayNow();

            then(sender).should().sendForRelay("acme", "{1}");
            then(sender).should().sendForRelay("globex", "{2}");
            then(outbox).should().markSent(List.of(first.id(), second.id()));
            assertThat(result).isEqualTo(new AuditOutboxRelay.RunResult(2, 0));
            assertThat(meters.counter("audit.outbox.sent").count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("hands the whole batch to Kafka before waiting for any acknowledgement")
        void pipelined() {
            given(outbox.due(50)).willReturn(List.of(first, second));
            final CompletableFuture<Object> firstAck = new CompletableFuture<>();
            given(sender.sendForRelay(eq("acme"), anyString())).willAnswer(i -> firstAck);
            // The first acknowledgement only comes once the second event was
            // handed over: waiting per event would time out on the first.
            given(sender.sendForRelay(eq("globex"), anyString())).willAnswer(i -> {
                firstAck.complete(null);
                return CompletableFuture.completedFuture(null);
            });

            assertThat(relay(50, 10, Duration.ofMillis(200)).relayNow())
                    .isEqualTo(new AuditOutboxRelay.RunResult(2, 0));
        }

        @Test
        @DisplayName("takes further full batches in one run, up to max-batches-per-run")
        void severalBatches() {
            final AuditOutboxRelay small = relay(2, 3, Duration.ofSeconds(3));
            given(outbox.due(2)).willReturn(List.of(first, second));
            acknowledgeEverything();

            assertThat(small.relayNow().sent()).isEqualTo(6);
            then(outbox).should(times(3)).due(2);
        }

        @Test
        @DisplayName("stops after a batch that was not full")
        void stopsAtShortBatch() {
            final AuditOutboxRelay small = relay(2, 3, Duration.ofSeconds(3));
            given(outbox.due(2)).willReturn(List.of(first, second), List.of(first));
            acknowledgeEverything();

            assertThat(small.relayNow().sent()).isEqualTo(3);
            then(outbox).should(times(2)).due(2);
        }

        @Test
        @DisplayName("the tenant is set while each row is handed over and marked failed, and cleared after it")
        void tenantPerRow() {
            given(outbox.due(50)).willReturn(List.of(first, second));
            final List<String> during = new ArrayList<>();
            given(sender.sendForRelay(any(), anyString())).willAnswer(i -> {
                during.add(TenantContext.getOrNull());
                return CompletableFuture.completedFuture(null);
            });
            willAnswer(i -> {
                during.add("failed:" + TenantContext.getOrNull());
                return true;
            }).given(outbox).markFailed(eq(second.id()), any(), anyString());
            willAnswer(i -> {
                during.add(TenantContext.getOrNull());
                return CompletableFuture.failedFuture(new RecordTooLargeException("too large"));
            }).given(sender).sendForRelay(eq("globex"), anyString());

            relay.relayNow();

            assertThat(during).containsExactly("acme", "globex", "failed:globex");
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("a row without a tenant is sent without one and leaves no tenant behind")
        void rowWithoutTenant() {
            final AuditOutbox.Pending system = new AuditOutbox.Pending(UUID.randomUUID(), null, "S", "{s}", 0);
            given(outbox.due(50)).willReturn(List.of(system));
            final List<String> during = new ArrayList<>();
            given(sender.sendForRelay(any(), anyString())).willAnswer(i -> {
                during.add(TenantContext.getOrNull());
                return CompletableFuture.completedFuture(null);
            });

            relay.relayNow();

            then(sender).should().sendForRelay(null, "{s}");
            assertThat(during).containsOnlyNulls();
            then(outbox).should().markSent(List.of(system.id()));
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a failed acknowledgement backs the row off by its attempt count; the others are still marked")
        void failedRowBacksOff() {
            given(outbox.due(50)).willReturn(List.of(second, first));
            // globex fails only after the whole batch was handed over.
            final CompletableFuture<Object> globexAck = new CompletableFuture<>();
            given(sender.sendForRelay(eq("globex"), anyString())).willAnswer(i -> globexAck);
            given(sender.sendForRelay(eq("acme"), anyString())).willAnswer(i -> {
                globexAck.completeExceptionally(new IllegalStateException("not acknowledged"));
                return CompletableFuture.completedFuture(null);
            });

            final AuditOutboxRelay.RunResult result = relay.relayNow();

            // second has 2 attempts: the third waits 5 s * 2^2 = 20 s.
            then(outbox).should().markFailed(eq(second.id()), eq(Duration.ofSeconds(20)), contains("not acknowledged"));
            then(outbox).should().markSent(List.of(first.id()));
            assertThat(result).isEqualTo(new AuditOutboxRelay.RunResult(1, 1));
            assertThat(meters.counter("audit.outbox.send.failed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a send that fails at once ends the batch: the next rows stay for the next run")
        void immediateFailureStopsDispatch() {
            given(outbox.due(50)).willReturn(List.of(second, first));
            given(sender.sendForRelay(eq("globex"), anyString())).willReturn(CompletableFuture.failedFuture(
                    new org.apache.kafka.common.errors.TimeoutException("no metadata")));

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(0, 1));
            then(sender).should(never()).sendForRelay(eq("acme"), anyString());
            then(outbox).should(never()).markFailed(eq(first.id()), any(), anyString());
        }

        @Test
        @DisplayName("a row Kafka refuses for itself is backed off alone: the rest is sent, no pause (found in review)")
        void recordRefusedDoesNotHoldOthersUp() {
            given(outbox.due(50)).willReturn(List.of(second, first));
            given(sender.sendForRelay(eq("globex"), anyString()))
                    .willReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")));
            given(sender.sendForRelay(eq("acme"), anyString())).willReturn(CompletableFuture.completedFuture(null));

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(1, 1));
            then(outbox).should().markSent(List.of(first.id()));
            then(outbox).should().markFailed(eq(second.id()), eq(Duration.ofSeconds(20)), contains("too large"));
            assertThat(relay.pausedUntil()).isEqualTo(Instant.MIN);
        }

        @Test
        @DisplayName("Kafka's own failures pause the relay; a record's do not; unknown ones do, to be safe")
        void failureClassification() {
            assertThat(AuditOutboxRelay.isKafkaFailure(
                    new org.apache.kafka.common.errors.TimeoutException("metadata"))).isTrue();
            assertThat(AuditOutboxRelay.isKafkaFailure(new java.util.concurrent.TimeoutException())).isTrue();
            assertThat(AuditOutboxRelay.isKafkaFailure(
                    new org.apache.kafka.common.errors.NotLeaderOrFollowerException("moved"))).isTrue();
            assertThat(AuditOutboxRelay.isKafkaFailure(new IllegalStateException("unknown"))).isTrue();
            assertThat(AuditOutboxRelay.isKafkaFailure(new IllegalStateException("wrapped",
                    new RecordTooLargeException("too large")))).isFalse();
            assertThat(AuditOutboxRelay.isKafkaFailure(
                    new org.apache.kafka.common.errors.SerializationException("bad"))).isFalse();
        }

        @Test
        @DisplayName("a send that throws is a failed send of that row, not a broken run")
        void sendThrows() {
            given(outbox.due(50)).willReturn(List.of(first));
            willThrow(new IllegalStateException("serializer")).given(sender).sendForRelay(any(), anyString());

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(0, 1));
            then(outbox).should().markFailed(eq(first.id()), eq(Duration.ofSeconds(5)), contains("serializer"));
        }

        @Test
        @DisplayName("no acknowledgement within the send timeout is a failure")
        void timeout() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString())).willReturn(new CompletableFuture<>());

            assertThat(relay(50, 10, Duration.ofMillis(20)).relayNow().failed()).isEqualTo(1);
            then(outbox).should().markFailed(eq(first.id()), any(),
                    contains(TimeoutException.class.getName()));
        }

        @Test
        @DisplayName("an interrupt is a failure and keeps the thread's interrupt flag")
        void interrupted() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString())).willReturn(new CompletableFuture<>());
            Thread.currentThread().interrupt();
            try {
                assertThat(relay.relayNow().failed()).isEqualTo(1);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("after a failed run scheduled runs pause, longer each time; a good run ends the pause")
        void pausesAfterFailure() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

            relay.relay();
            assertThat(relay.pausedUntil()).isEqualTo(NOW.plusSeconds(5));

            relay.relay();
            then(outbox).should(times(1)).due(50);

            clock.advance(Duration.ofSeconds(5));
            relay.relay();
            assertThat(relay.pausedUntil()).as("second failed run in a row").isEqualTo(NOW.plusSeconds(15));

            given(sender.sendForRelay(any(), anyString())).willReturn(CompletableFuture.completedFuture(null));
            clock.advance(Duration.ofSeconds(10));
            relay.relay();
            assertThat(relay.pausedUntil()).isEqualTo(Instant.MIN);
        }

        @Test
        @DisplayName("relayNow ignores a pause (the break-glass command sends its event at once)")
        void relayNowIgnoresPause() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
            relay.relay();

            acknowledgeEverything();
            assertThat(relay.relayNow().sent()).isEqualTo(1);
        }

        @Test
        @DisplayName("sent but not marked: counted as sent, never as failed, not marked failed")
        void markSentThrows() {
            given(outbox.due(50)).willReturn(List.of(first));
            acknowledgeEverything();
            willThrow(new IllegalStateException("db hiccup")).given(outbox).markSent(List.of(first.id()));

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(1, 0));
            then(outbox).should(never()).markFailed(any(), any(), anyString());
            assertThat(meters.counter("audit.outbox.send.failed").count()).isZero();
        }

        @Test
        @DisplayName("rows no longer pending when marked sent are logged at WARN, not thrown")
        void markSentFindsNothing() {
            given(outbox.due(50)).willReturn(List.of(first, second));
            acknowledgeEverything();
            given(outbox.markSent(List.of(first.id(), second.id()))).willReturn(1);

            final ListAppender<ILoggingEvent> logs = captureLogs();
            try {
                assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(2, 0));
                assertThat(logs.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("1 of 2 outbox rows were no longer pending");
                });
            } finally {
                release(logs);
            }
        }

        @Test
        @DisplayName("a row no longer pending when marked failed is logged at WARN, not thrown")
        void markFailedFindsNothing() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")));
            given(outbox.markFailed(any(), any(), anyString())).willReturn(false);

            final ListAppender<ILoggingEvent> logs = captureLogs();
            try {
                assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(0, 1));
                assertThat(logs.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("failed but its outbox row was no longer pending");
                });
            } finally {
                release(logs);
            }
        }

        @Test
        @DisplayName("a row that cannot be marked failed stays due; the run ends normally")
        void markFailedThrows() {
            given(outbox.due(50)).willReturn(List.of(first));
            given(sender.sendForRelay(any(), anyString()))
                    .willReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
            willThrow(new IllegalStateException("db down")).given(outbox).markFailed(any(), any(), anyString());

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(0, 1));
        }

        @Test
        @DisplayName("a database error reading the outbox is thrown, as for any scheduled job")
        void readFailure() {
            given(outbox.due(50)).willThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> relay.relayNow()).hasMessage("db down");
        }
    }

    @Nested
    @DisplayName("locks, metrics, purge, settings")
    class Housekeeping {

        @Test
        @DisplayName("relay and purge locks are named after the table; the relay's lasts one full run")
        void locks() {
            given(outbox.due(50)).willReturn(List.of());
            final ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);

            relay.relay();
            relay.purge();

            then(lockProvider).should(times(2)).lock(lock.capture());
            assertThat(lock.getAllValues().get(0).getName()).isEqualTo("audit-outbox-relay-auth_audit_outbox");
            assertThat(lock.getAllValues().get(0).getLockAtMostUntil())
                    .as("3 s per batch, 10 batches, plus 30 s").isEqualTo(NOW.plusSeconds(60));
            assertThat(lock.getAllValues().get(1).getName()).isEqualTo("audit-outbox-purge-auth_audit_outbox");
        }

        @Test
        @DisplayName("a run another instance holds the lock for does nothing")
        void lockHeldElsewhere() {
            given(lockProvider.lock(any())).willReturn(Optional.empty());

            assertThat(relay.relayNow()).isEqualTo(new AuditOutboxRelay.RunResult(0, 0));
            then(outbox).should(never()).due(50);
        }

        @Test
        @DisplayName("the gauges read the table when scraped, whether or not the relay runs")
        void gaugesReadTheTable() {
            given(outbox.backlog()).willReturn(new AuditOutbox.Backlog(7, Optional.of(Duration.ofSeconds(90))));

            assertThat(meters.get("audit.outbox.pending").gauge().value()).isEqualTo(7.0);
            assertThat(meters.get("audit.outbox.oldest.pending.age").gauge().value()).isEqualTo(90.0);
            then(outbox).should(times(1)).backlog();
            then(outbox).should(never()).due(50);
        }

        @Test
        @DisplayName("the backlog is read at most every 5 s, and the age keeps growing between reads")
        void backlogCached() {
            given(outbox.backlog()).willReturn(new AuditOutbox.Backlog(1, Optional.of(Duration.ofSeconds(10))));

            meters.get("audit.outbox.pending").gauge().value();
            clock.advance(Duration.ofSeconds(4));
            assertThat(meters.get("audit.outbox.oldest.pending.age").gauge().value()).isEqualTo(14.0);
            then(outbox).should(times(1)).backlog();

            clock.advance(Duration.ofSeconds(1));
            meters.get("audit.outbox.pending").gauge().value();
            then(outbox).should(times(2)).backlog();
        }

        @Test
        @DisplayName("an empty outbox shows 0; an unreadable one NaN")
        void gaugeEdges() {
            given(outbox.backlog()).willReturn(new AuditOutbox.Backlog(0, Optional.empty()));
            assertThat(meters.get("audit.outbox.oldest.pending.age").gauge().value()).isZero();

            clock.advance(Duration.ofSeconds(5));
            given(outbox.backlog()).willThrow(new IllegalStateException("db down"));
            assertThat(meters.get("audit.outbox.pending").gauge().value()).isNaN();
        }

        @Test
        @DisplayName("retry delays double from 5 s and stop at 5 min")
        void retryDelays() {
            assertThat(AuditOutboxRelay.retryDelay(1)).isEqualTo(Duration.ofSeconds(5));
            assertThat(AuditOutboxRelay.retryDelay(2)).isEqualTo(Duration.ofSeconds(10));
            assertThat(AuditOutboxRelay.retryDelay(7)).isEqualTo(Duration.ofMinutes(5));
            assertThat(AuditOutboxRelay.retryDelay(1000)).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("purge deletes sent events older than the retention")
        void purge() {
            relay.purge();

            then(outbox).should().purgeSentOlderThan(Duration.ofDays(7));
        }

        @Test
        @DisplayName("settings: defaults, and a bad table name, batch size or timeout refused at startup")
        void settings() {
            final AuditOutboxProperties defaults = new AuditOutboxProperties("auth_audit_outbox",
                    null, null, null, null);
            assertThat(defaults.batchSize()).isEqualTo(100);
            assertThat(defaults.maxBatchesPerRun()).isEqualTo(10);
            assertThat(defaults.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(defaults.retention()).isEqualTo(Duration.ofDays(7));

            assertThatThrownBy(() -> new AuditOutboxProperties("x; DROP TABLE users", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("Audit", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", 0, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", null, 0, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", null, null, Duration.ZERO, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", null, null, Duration.ofSeconds(-1), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", null, null, null, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditOutboxProperties("a", null, null, null, Duration.ofDays(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
