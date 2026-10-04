package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.MfaRecoveryProperties;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs operator MFA recovery requests whose waiting period has passed, and
 * expires those whose notice was never sent (backlog #0-90; the rules are
 * {@link MfaRecoveryService}'s).
 *
 * <p>One job under one ShedLock lock, so replicas do not both run a request
 * (the conditional UPDATE would stop the second anyway). Each request is its
 * own transaction, with {@code TenantContext} set from the row inside its
 * {@code try} (backlog #0-92: a bad row fails only itself, not the batch).
 * Like every scheduled job of auth-service, it does not run in break-glass
 * mode ({@code SchedulerConfig}).
 *
 * <p>A request that keeps failing stays PENDING and, being the oldest, is taken
 * first on every run, so enough of them would hold back the rest of the batch
 * (performance review). Every failure is counted
 * ({@code platform.mfa_recovery.failures}, registered at zero) and alerted
 * ({@code PlatformMfaRecoveryJobFailing}), so a stuck request gets a person
 * long before it could fill a batch.
 */
@Component
@EnableConfigurationProperties(MfaRecoveryProperties.class)
public class MfaRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MfaRecoveryScheduler.class);

    static final String FAILURE_COUNTER = "platform.mfa_recovery.failures";

    private final MfaRecoveryService recoveryService;
    private final int batchSize;
    private final Counter failures;

    public MfaRecoveryScheduler(MfaRecoveryService recoveryService, MfaRecoveryProperties properties,
                                MeterRegistry meterRegistry) {
        this.recoveryService = recoveryService;
        this.batchSize = properties.batchSize();
        this.failures = Counter.builder(FAILURE_COUNTER)
                .description("MFA recovery requests the scheduler could not execute or expire (backlog #0-90)")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${platform.mfa-recovery.scheduler-interval-ms:300000}",
            initialDelayString = "90000"
    )
    @SchedulerLock(
            name = "auth-service:processMfaRecoveryRequests",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void processRequests() {
        processEach("execute", () -> recoveryService.findDue(batchSize), recoveryService::execute);
        processEach("expire", () -> recoveryService.findUndelivered(batchSize), recoveryService::expire);
    }

    /** Each half on its own: a failed query for one does not skip the other (review). */
    private void processEach(String action, Supplier<List<MfaRecoveryRequest>> query, Predicate<UUID> step) {
        final List<MfaRecoveryRequest> requests;
        try {
            requests = query.get();
        } catch (RuntimeException e) {
            failures.increment();
            log.error("MFA recovery: could not find requests to {} — will be retried", action, e);
            return;
        }
        for (final MfaRecoveryRequest request : requests) {
            try {
                TenantContext.set(request.getTenantId());
                step.test(request.getId());
            } catch (RuntimeException e) {
                // The request stays PENDING and is tried again next run.
                failures.increment();
                log.error("MFA recovery: could not {} request={}, tenant={} — will be retried",
                        action, request.getId(), request.getTenantId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
