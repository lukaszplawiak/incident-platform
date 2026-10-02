package com.incidentplatform.auth.ratelimit;

import com.incidentplatform.auth.repository.ApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("ApiKeyCreationLimit (backlog #0-89)")
class ApiKeyCreationLimitTest {

    private static final String TENANT = "acme";
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ApiKeyCreationLimit limit =
            new ApiKeyCreationLimit(repository, 3, meters, Clock.fixed(NOW, ZoneOffset.UTC));

    /** The repository's answer: newest first, at most the page size (the limit, three here). */
    private void createdMinutesAgo(int... minutes) {
        final List<Instant> newestFirst = IntStream.of(minutes).boxed()
                .map(m -> NOW.minusSeconds(60L * m))
                .sorted(java.util.Comparator.reverseOrder())
                .limit(3)
                .toList();
        given(repository.findCreationTimesSince(TENANT, USER, NOW.minus(ApiKeyCreationLimit.WINDOW),
                PageRequest.of(0, 3))).willReturn(newestFirst);
    }

    @Test
    @DisplayName("allowed below the limit, counting the last hour only")
    void allowedBelow() {
        createdMinutesAgo(10, 50);
        assertThat(limit.check(TENANT, USER)).isEqualTo(RateLimitDecision.ALLOWED);
    }

    @Test
    @DisplayName("refused at the limit, Retry-After until the oldest of the newest three leaves the window")
    void refusedAtLimit() {
        createdMinutesAgo(50, 20, 5);

        final RateLimitDecision decision = limit.check(TENANT, USER);

        assertThat(decision.outcome()).isEqualTo(RateLimitDecision.Outcome.LIMITED);
        // The key made 50 minutes ago leaves the window in 10 minutes.
        assertThat(decision.retryAfterSeconds()).isEqualTo(601);
        assertThat(meters.counter("auth.api_key.creation.limited").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("loads at most the limit's number of rows, however many keys there are (review)")
    void boundedLoad() {
        createdMinutesAgo(55, 50, 20, 5);

        // The newest three are 50, 20 and 5 minutes old: the 50-minute one frees the next slot.
        assertThat(limit.check(TENANT, USER).retryAfterSeconds()).isEqualTo(601);
        org.mockito.Mockito.verify(repository).findCreationTimesSince(TENANT, USER,
                NOW.minus(ApiKeyCreationLimit.WINDOW), PageRequest.of(0, 3));
    }

    @Test
    @DisplayName("a configured limit below one is refused at startup")
    void rejectsZero() {
        assertThatThrownBy(() -> new ApiKeyCreationLimit(repository, 0, meters))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a creator row held by another creation is a 429 after one second; otherwise the lock's result")
    void lockingCreator() {
        assertThat(ApiKeyCreationLimit.lockingCreator(() -> "creator")).isEqualTo("creator");

        assertThatThrownBy(() -> ApiKeyCreationLimit.lockingCreator(() -> {
            throw new org.springframework.dao.CannotAcquireLockException("could not obtain lock on row");
        }))
                .isInstanceOfSatisfying(RateLimitRefusedException.class, e -> {
                    assertThat(e.decision().outcome()).isEqualTo(RateLimitDecision.Outcome.LIMITED);
                    assertThat(e.decision().retryAfterSeconds()).isEqualTo(1);
                });
    }
}
