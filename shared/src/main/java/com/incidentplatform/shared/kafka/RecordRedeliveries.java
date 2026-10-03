package com.incidentplatform.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Since when a consumed record has been failing (backlog #0-96, found in
 * review): {@link DeadLetterPublisher} gives up redelivering a record whose
 * failures last longer than its deadline, so a record that keeps failing
 * holds its partition (and every tenant on it) for a bounded time, not for
 * ever.
 *
 * <p>One entry per consumer group and partition: a {@code nack} seeks the
 * partition back to the failing record, so only the record at its head can
 * be failing. A failure of another offset replaces the entry, which is how an
 * entry left by a record that later succeeded goes away; so the map holds at
 * most one entry per partition this instance has seen fail. An entry older
 * than twice the deadline is treated as a new failure: the same offset
 * failing again much later (redelivered after a rebalance) starts over.
 *
 * <p>In memory, per instance: after a rebalance the new owner of the
 * partition starts counting from its own first failure, so a record can be
 * retried somewhat longer than the deadline, never less.
 */
final class RecordRedeliveries {

    private record Key(String group, String topic, int partition) {
    }

    private record Failing(long offset, Instant since) {
    }

    private final Map<Key, Failing> failing = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration deadline;

    RecordRedeliveries(Clock clock, Duration deadline) {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("A redelivery deadline must be positive: " + deadline);
        }
        this.clock = clock;
        this.deadline = deadline;
    }

    Duration deadline() {
        return deadline;
    }

    /**
     * Records one more failure of {@code record} for the current consumer
     * group.
     *
     * @return how long it has been failing (zero on its first failure)
     */
    Duration failed(String group, ConsumerRecord<?, ?> record) {
        final Instant now = clock.instant();
        final Failing entry = failing.compute(key(group, record), (key, existing) ->
                existing != null && existing.offset() == record.offset()
                        && !existing.since().plus(deadline.multipliedBy(2)).isBefore(now)
                        ? existing
                        : new Failing(record.offset(), now));
        return Duration.between(entry.since(), now);
    }

    /** Whether {@code record} failed before (for this group) and is not settled yet. */
    boolean isFailing(String group, ConsumerRecord<?, ?> record) {
        final Failing entry = failing.get(key(group, record));
        return entry != null && entry.offset() == record.offset();
    }

    /** The record is settled (dead-lettered): its failures are forgotten. */
    void settled(String group, ConsumerRecord<?, ?> record) {
        failing.computeIfPresent(key(group, record),
                (key, existing) -> existing.offset() == record.offset() ? null : existing);
    }

    int size() {
        return failing.size();
    }

    private static Key key(String group, ConsumerRecord<?, ?> record) {
        return new Key(group == null ? "" : group, record.topic(), record.partition());
    }
}
