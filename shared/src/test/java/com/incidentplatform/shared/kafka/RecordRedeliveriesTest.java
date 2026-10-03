package com.incidentplatform.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecordRedeliveries} (backlog #0-96): since when the record at a
 * partition's head has been failing, per consumer group, at most one entry
 * per partition.
 */
@DisplayName("RecordRedeliveries")
class RecordRedeliveriesTest {

    private static final Duration DEADLINE = Duration.ofMinutes(30);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final RecordRedeliveries redeliveries = new RecordRedeliveries(clock, DEADLINE);

    private static ConsumerRecord<String, String> at(int partition, long offset) {
        return new ConsumerRecord<>("incidents.lifecycle", partition, offset, "k", "v");
    }

    private void advance(Duration by) {
        now.set(now.get().plus(by));
    }

    @Test
    @DisplayName("counts from the first failure of the same record")
    void countsFromFirstFailure() {
        assertThat(redeliveries.failed("g", at(0, 5))).isZero();
        advance(Duration.ofMinutes(10));
        assertThat(redeliveries.failed("g", at(0, 5))).isEqualTo(Duration.ofMinutes(10));
        assertThat(redeliveries.isFailing("g", at(0, 5))).isTrue();
    }

    @Test
    @DisplayName("another offset on the partition replaces the entry; groups and partitions are apart")
    void onePerGroupAndPartition() {
        redeliveries.failed("g", at(0, 5));
        advance(Duration.ofMinutes(10));

        assertThat(redeliveries.failed("g", at(0, 6))).isZero();
        assertThat(redeliveries.failed("other-group", at(0, 6))).isZero();
        assertThat(redeliveries.failed("g", at(1, 6))).isZero();
        assertThat(redeliveries.isFailing("g", at(0, 5))).isFalse();
        assertThat(redeliveries.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("a settled record is forgotten; settling another offset forgets nothing")
    void settled() {
        redeliveries.failed("g", at(0, 5));
        redeliveries.settled("g", at(0, 4));
        assertThat(redeliveries.isFailing("g", at(0, 5))).isTrue();

        redeliveries.settled("g", at(0, 5));
        assertThat(redeliveries.isFailing("g", at(0, 5))).isFalse();
        assertThat(redeliveries.size()).isZero();
    }

    @Test
    @DisplayName("an entry older than twice the deadline is a new failure (the record came back much later)")
    void staleEntryStartsOver() {
        redeliveries.failed("g", at(0, 5));
        advance(DEADLINE.multipliedBy(2).plusSeconds(1));
        assertThat(redeliveries.failed("g", at(0, 5))).isZero();
    }

    @Test
    @DisplayName("a missing group (outside a listener) is one group of its own; a deadline must be positive")
    void edges() {
        assertThat(redeliveries.failed(null, at(0, 5))).isZero();
        assertThat(redeliveries.isFailing(null, at(0, 5))).isTrue();
        assertThatThrownBy(() -> new RecordRedeliveries(clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecordRedeliveries(clock, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
