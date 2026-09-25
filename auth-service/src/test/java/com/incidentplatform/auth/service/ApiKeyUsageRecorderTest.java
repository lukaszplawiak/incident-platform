package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.ApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyUsageRecorder (backlog #0-16)")
class ApiKeyUsageRecorderTest {

    private static final Duration INTERVAL = Duration.ofMinutes(5);

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private ApiKeyUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recorder = new ApiKeyUsageRecorder(apiKeyRepository, transactionManager, INTERVAL, meterRegistry);
    }

    private double failures() {
        return meterRegistry.counter("api_key_usage_record_failures").count();
    }

    @Test
    @DisplayName("updates in a REQUIRES_NEW transaction with threshold = now - interval")
    void conditionalUpdate() {
        final ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        given(transactionManager.getTransaction(definition.capture()))
                .willReturn(new SimpleTransactionStatus());
        final UUID keyId = UUID.randomUUID();

        recorder.recordUsage(keyId);

        final ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        then(apiKeyRepository).should().touchLastUsedAt(eq(keyId), now.capture(), threshold.capture());
        assertThat(Duration.between(threshold.getValue(), now.getValue())).isEqualTo(INTERVAL);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(failures()).isZero();
    }

    @Test
    @DisplayName("a failing UPDATE is counted and does not reach the caller")
    void updateFails() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(apiKeyRepository.touchLastUsedAt(any(), any(), any()))
                .willThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> recorder.recordUsage(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(failures()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a transaction that cannot start is counted and does not reach the caller")
    void transactionFails() {
        given(transactionManager.getTransaction(any()))
                .willThrow(new CannotCreateTransactionException("no connection"));

        assertThatCode(() -> recorder.recordUsage(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(failures()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejects a non-positive interval at startup")
    void rejectsZeroInterval() {
        assertThatThrownBy(() -> new ApiKeyUsageRecorder(
                apiKeyRepository, transactionManager, Duration.ZERO, meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
