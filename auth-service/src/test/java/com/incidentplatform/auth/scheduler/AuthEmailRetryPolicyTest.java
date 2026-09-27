package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AuthEmailRetryPolicy} (backlog #0-52): the backoff steps,
 * the repeat of the last step, the move to the deadline and the give-up, plus
 * whole failure sequences for a reset and an invite with the production
 * defaults.
 */
@DisplayName("AuthEmailRetryPolicy")
class AuthEmailRetryPolicyTest {

    private static final List<Duration> DEFAULT_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6));

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    private final AuthEmailRetryPolicy policy = new AuthEmailRetryPolicy(DEFAULT_BACKOFF);

    @Nested
    @DisplayName("single step")
    class SingleStep {

        private final Instant farDeadline = T0.plus(Duration.ofDays(7));

        @Test
        @DisplayName("uses the n-th delay after the n-th failure")
        void usesNthDelay() {
            assertThat(policy.nextAttempt(1, T0, farDeadline)).contains(T0.plus(Duration.ofMinutes(1)));
            assertThat(policy.nextAttempt(2, T0, farDeadline)).contains(T0.plus(Duration.ofMinutes(5)));
            assertThat(policy.nextAttempt(3, T0, farDeadline)).contains(T0.plus(Duration.ofMinutes(30)));
            assertThat(policy.nextAttempt(4, T0, farDeadline)).contains(T0.plus(Duration.ofHours(2)));
            assertThat(policy.nextAttempt(5, T0, farDeadline)).contains(T0.plus(Duration.ofHours(6)));
        }

        @Test
        @DisplayName("repeats the last delay after the list runs out")
        void repeatsLastDelay() {
            assertThat(policy.nextAttempt(6, T0, farDeadline)).contains(T0.plus(Duration.ofHours(6)));
            assertThat(policy.nextAttempt(40, T0, farDeadline)).contains(T0.plus(Duration.ofHours(6)));
        }

        @Test
        @DisplayName("allows an attempt exactly at the deadline")
        void allowsAttemptAtDeadline() {
            final Instant deadline = T0.plus(Duration.ofMinutes(1));

            assertThat(policy.nextAttempt(1, T0, deadline)).contains(deadline);
        }

        @Test
        @DisplayName("moves an attempt that would fall after the deadline to the deadline")
        void movesToDeadline() {
            final Instant deadline = T0.plus(Duration.ofMinutes(10));

            assertThat(policy.nextAttempt(3, T0, deadline)).contains(deadline);
        }

        @Test
        @DisplayName("gives up once the deadline has come")
        void givesUpAtDeadline() {
            assertThat(policy.nextAttempt(1, T0, T0)).isEmpty();
            assertThat(policy.nextAttempt(1, T0.plusSeconds(1), T0)).isEmpty();
        }

        @Test
        @DisplayName("rejects a failure count below 1")
        void rejectsZeroFailures() {
            assertThatThrownBy(() -> policy.nextAttempt(0, T0, T0.plus(Duration.ofDays(1))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects an empty backoff")
        void rejectsEmptyBackoff() {
            assertThatThrownBy(() -> new AuthEmailRetryPolicy(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reads its backoff from InviteEmailProperties")
        void readsProperties() {
            final InviteEmailProperties properties = new InviteEmailProperties(
                    "noreply@test.com", "http://localhost", 15, 30000L,
                    List.of(Duration.ofMinutes(3)), Duration.ofMinutes(2), Duration.ofDays(30));

            assertThat(new AuthEmailRetryPolicy(properties).nextAttempt(9, T0, T0.plus(Duration.ofDays(1))))
                    .contains(T0.plus(Duration.ofMinutes(3)));
        }
    }

    /**
     * The test the backlog item asks for: a failure sequence ends in
     * PERMANENTLY_FAILED only at the deadline, not after a fixed count. Every
     * attempt happens exactly when due.
     */
    @Nested
    @DisplayName("whole failure sequences with the defaults")
    class Sequences {

        private List<Instant> attemptsUntilGiveUp(Instant deadline) {
            final List<Instant> attempts = new ArrayList<>();
            Optional<Instant> next = Optional.of(T0);
            int failures = 0;
            while (next.isPresent()) {
                final Instant attempt = next.get();
                attempts.add(attempt);
                failures++;
                next = policy.nextAttempt(failures, attempt, deadline);
                assertThat(failures).as("sequence must end").isLessThan(1000);
            }
            return attempts;
        }

        @Test
        @DisplayName("password reset (15 minutes): 4 attempts, the last at the deadline")
        void passwordReset() {
            final Instant deadline = T0.plus(Duration.ofMinutes(15));

            assertThat(attemptsUntilGiveUp(deadline)).containsExactly(
                    T0,
                    T0.plus(Duration.ofMinutes(1)),
                    T0.plus(Duration.ofMinutes(6)),
                    deadline);
            // The old policy gave up after 3 attempts over about 10 minutes.
        }

        @Test
        @DisplayName("invite (7 days): retried for the whole week, about every 6 hours at the end")
        void invite() {
            final Instant deadline = T0.plus(Duration.ofDays(7));

            final List<Instant> attempts = attemptsUntilGiveUp(deadline);

            assertThat(attempts).hasSizeBetween(30, 36);
            assertThat(attempts.getLast()).isEqualTo(deadline);
            for (int i = 1; i < attempts.size(); i++) {
                assertThat(Duration.between(attempts.get(i - 1), attempts.get(i)))
                        .isPositive()
                        .isLessThanOrEqualTo(Duration.ofHours(6));
            }
        }
    }
}
