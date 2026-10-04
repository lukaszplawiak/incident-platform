package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.MfaRecoveryProperties;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.domain.MfaVerificationMethod;
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * {@link MfaRecoveryScheduler} (backlog #0-90): every due request runs in its
 * own tenant context, and one that fails, a malformed tenant included, does
 * not stop the others (backlog #0-92).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MfaRecoveryScheduler")
class MfaRecoverySchedulerTest {

    @Mock private MfaRecoveryService recoveryService;

    private MfaRecoveryScheduler scheduler;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        scheduler = new MfaRecoveryScheduler(recoveryService,
                new MfaRecoveryProperties(Duration.ofHours(72), 300_000L, 7), meters);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MfaRecoveryRequest request(String tenantId) {
        final MfaRecoveryRequest request = MfaRecoveryRequest.open("acme", UUID.randomUUID(), UUID.randomUUID(),
                MfaVerificationMethod.VIDEO_CALL, "checked", Instant.now());
        // A row that somehow holds a malformed tenant must fail alone.
        ReflectionTestUtils.setField(request, "tenantId", tenantId);
        return request;
    }

    @Test
    @DisplayName("executes due requests and expires undelivered ones, each under its own tenant")
    void runsEachUnderItsTenant() {
        final MfaRecoveryRequest due = request("acme");
        final MfaRecoveryRequest undelivered = request("globex");
        given(recoveryService.findDue(7)).willReturn(List.of(due));
        given(recoveryService.findUndelivered(7)).willReturn(List.of(undelivered));
        final AtomicReference<String> executedIn = new AtomicReference<>();
        final AtomicReference<String> expiredIn = new AtomicReference<>();
        given(recoveryService.execute(due.getId())).willAnswer(i -> {
            executedIn.set(TenantContext.get());
            return true;
        });
        given(recoveryService.expire(undelivered.getId())).willAnswer(i -> {
            expiredIn.set(TenantContext.get());
            return true;
        });

        scheduler.processRequests();

        assertThat(executedIn.get()).isEqualTo("acme");
        assertThat(expiredIn.get()).isEqualTo("globex");
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("a request with a bad tenant, or one that throws, does not stop the batch")
    void badRowBeforeGoodRow() {
        final MfaRecoveryRequest badTenant = request("Not A Slug");
        final MfaRecoveryRequest failing = request("acme");
        final MfaRecoveryRequest good = request("globex");
        given(recoveryService.findDue(7)).willReturn(List.of(badTenant, failing, good));
        given(recoveryService.findUndelivered(7)).willReturn(List.of());
        given(recoveryService.execute(failing.getId())).willThrow(new IllegalStateException("db down"));
        given(recoveryService.execute(good.getId())).willReturn(true);

        scheduler.processRequests();

        then(recoveryService).should(org.mockito.Mockito.never()).execute(badTenant.getId());
        then(recoveryService).should().execute(good.getId());
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(meters.counter(MfaRecoveryScheduler.FAILURE_COUNTER).count())
                .as("both failures counted, for PlatformMfaRecoveryJobFailing").isEqualTo(2.0);
    }

    @Test
    @DisplayName("a failed query for due requests does not skip expiring the undelivered ones (review)")
    void failedQueryDoesNotSkipOtherHalf() {
        final MfaRecoveryRequest undelivered = request("acme");
        given(recoveryService.findDue(7)).willThrow(new IllegalStateException("db hiccup"));
        given(recoveryService.findUndelivered(7)).willReturn(List.of(undelivered));
        given(recoveryService.expire(undelivered.getId())).willReturn(true);

        scheduler.processRequests();

        then(recoveryService).should().expire(undelivered.getId());
        assertThat(meters.counter(MfaRecoveryScheduler.FAILURE_COUNTER).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the failure counter exists at zero before any failure")
    void failureCounterPreRegistered() {
        assertThat(meters.find(MfaRecoveryScheduler.FAILURE_COUNTER).counter()).isNotNull();
        assertThat(meters.counter(MfaRecoveryScheduler.FAILURE_COUNTER).count()).isZero();
    }
}
