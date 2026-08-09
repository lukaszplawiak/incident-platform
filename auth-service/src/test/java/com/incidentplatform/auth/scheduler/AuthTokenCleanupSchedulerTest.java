package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.repository.AuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Tests for {@link AuthTokenCleanupScheduler} — new class, wiring up
 * {@link AuthTokenRepository#deleteExpiredAndUsed} which was previously
 * defined but never called anywhere (see this scheduler's own Javadoc).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenCleanupScheduler")
class AuthTokenCleanupSchedulerTest {

    @Mock
    private AuthTokenRepository tokenRepository;

    private AuthTokenCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuthTokenCleanupScheduler(tokenRepository);
    }

    @Test
    @DisplayName("calls deleteExpiredAndUsed with a threshold at or before now")
    void callsDeleteExpiredAndUsedWithCurrentThreshold() {
        given(tokenRepository.deleteExpiredAndUsed(org.mockito.ArgumentMatchers.any()))
                .willReturn(0);

        final Instant before = Instant.now();
        scheduler.cleanupExpiredAndUsedTokens();
        final Instant after = Instant.now();

        final ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        then(tokenRepository).should().deleteExpiredAndUsed(captor.capture());

        final Instant usedThreshold = captor.getValue();
        assertThat(usedThreshold).isBetween(before, after);
    }

    @Test
    @DisplayName("does not throw when nothing was deleted")
    void doesNotThrowWhenNothingDeleted() {
        given(tokenRepository.deleteExpiredAndUsed(org.mockito.ArgumentMatchers.any()))
                .willReturn(0);

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> scheduler.cleanupExpiredAndUsedTokens())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not throw when rows were deleted")
    void doesNotThrowWhenRowsDeleted() {
        given(tokenRepository.deleteExpiredAndUsed(org.mockito.ArgumentMatchers.any()))
                .willReturn(42);

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> scheduler.cleanupExpiredAndUsedTokens())
                .doesNotThrowAnyException();
    }
}