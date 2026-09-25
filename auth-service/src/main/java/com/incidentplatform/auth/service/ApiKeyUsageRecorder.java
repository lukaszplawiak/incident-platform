package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.ApiKeyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Records that an API key was used, at most once per key per
 * {@code api-key.usage.write-interval}.
 *
 * <h2>Fixed (backlog #0-16): {@code last_used_at} was never saved</h2>
 * {@code ApiKeyService.recordUsageAsync} was {@code @Async}, but auth-service
 * has no {@code @EnableAsync}, so it ran synchronously inside
 * {@code ApiKeyLookupServiceImpl.lookup}'s {@code @Transactional(readOnly = true)},
 * where the changed entity is never flushed. Introspection makes
 * ingestion-service the main user of API keys, so this matters now.
 *
 * <h2>Why synchronous, and why it cannot fail the request</h2>
 * The write is one conditional UPDATE by primary key
 * ({@link ApiKeyRepository#touchLastUsedAt}), and most calls update nothing
 * because the key was used within the interval. It runs in its own
 * transaction ({@code REQUIRES_NEW}) so that a read-only caller neither
 * swallows it nor is rolled back by it. Recording usage is not part of
 * authenticating: a failure is logged, counted in
 * {@code api_key_usage_record_failures_total} and never turns a valid key
 * into a failed request. A {@link TransactionTemplate} is used instead of
 * {@code @Transactional} so that the catch surrounds the commit as well:
 * catching inside an annotated method would still end in an
 * {@code UnexpectedRollbackException} thrown by the proxy at commit time.
 */
@Service
public class ApiKeyUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyUsageRecorder.class);

    private final ApiKeyRepository apiKeyRepository;
    private final TransactionTemplate requiresNew;
    private final Duration writeInterval;
    private final Counter failures;

    public ApiKeyUsageRecorder(ApiKeyRepository apiKeyRepository,
                               PlatformTransactionManager transactionManager,
                               @Value("${api-key.usage.write-interval:PT5M}") Duration writeInterval,
                               MeterRegistry meterRegistry) {
        if (writeInterval.isNegative() || writeInterval.isZero()) {
            throw new IllegalArgumentException(
                    "api-key.usage.write-interval must be positive, got " + writeInterval);
        }
        this.apiKeyRepository = apiKeyRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeInterval = writeInterval;
        this.failures = Counter.builder("api_key_usage_record_failures")
                .description("API key last_used_at updates that failed (the key still authenticated)")
                .register(meterRegistry);
    }

    public void recordUsage(UUID keyId) {
        final Instant now = Instant.now();
        try {
            requiresNew.executeWithoutResult(status ->
                    apiKeyRepository.touchLastUsedAt(keyId, now, now.minus(writeInterval)));
        } catch (DataAccessException | TransactionException e) {
            failures.increment();
            log.warn("Could not record API key usage, the request is unaffected: keyId={}",
                    keyId, e);
        }
    }
}
