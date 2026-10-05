package com.incidentplatform.auth.service;

import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.security.TenantAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/** {@link SignInRefusals} (backlog #0-82, step 2): a refused sign-in leaves an audit event and a count. */
@DisplayName("SignInRefusals")
class SignInRefusalsTest {

    private static final String TENANT = "acme";
    private static final UUID USER = UUID.randomUUID();

    private AuditEventPublisher publisher;
    private BruteForceProtectionService bruteForce;
    private SimpleMeterRegistry meters;
    private SignInRefusals refusals;

    @BeforeEach
    void setUp() {
        publisher = mock(AuditEventPublisher.class);
        meters = new SimpleMeterRegistry();
        bruteForce = mock(BruteForceProtectionService.class);
        refusals = new SignInRefusals(publisher, bruteForce, meters);
    }

    private double counted(SignInFlow flow, TenantAccess access) {
        return counted(flow, access, "audited");
    }

    private double counted(SignInFlow flow, TenantAccess access, String outcome) {
        final var counter = meters.find(SignInRefusals.COUNTER)
                .tags("flow", flow.name(), "access", access.name(), "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("audits in the tenant's own trail, with the flow and access, counts by both, and counts the "
            + "refusal against the user's suspended-sign-in limit")
    void auditsAndCounts() {
        assertThat(refusals.record(new TenantSuspendedSignInException(TENANT, USER, SignInFlow.REFRESH,
                TenantAccess.NONE))).isInstanceOf(SignInRefusals.Outcome.Recorded.class);

        then(publisher).should().publishAuth(eq(USER), eq(TENANT),
                eq(AuditEventTypes.USER_SIGN_IN_REFUSED_TENANT_SUSPENDED), eq("auth-service"),
                eq(USER.toString()), anyString(), eq(Map.of("flow", "REFRESH", "access", "NONE")));
        assertThat(counted(SignInFlow.REFRESH, TenantAccess.NONE)).isEqualTo(1);
        then(bruteForce).should().recordFailure(BruteForceProtectionService.Scope.SUSPENDED_SIGN_IN,
                USER.toString(), TENANT);
    }

    @Test
    @DisplayName("a user over the limit is throttled: 429's Retry-After, no audit event, no further count against "
            + "the limit (review of step 2: a reused invite or reset token could write events without end)")
    void overLimitThrottled() {
        org.mockito.BDDMockito.given(bruteForce.isLocked(BruteForceProtectionService.Scope.SUSPENDED_SIGN_IN,
                USER.toString(), TENANT)).willReturn(true);
        org.mockito.BDDMockito.given(bruteForce.getRemainingLockout(BruteForceProtectionService.Scope.SUSPENDED_SIGN_IN,
                USER.toString(), TENANT)).willReturn(java.time.Duration.ofMinutes(7));

        assertThat(refusals.record(new TenantSuspendedSignInException(TENANT, USER, SignInFlow.ACCEPT_INVITE,
                TenantAccess.READ_ONLY)))
                .isEqualTo(new SignInRefusals.Outcome.Throttled(java.time.Duration.ofMinutes(7)));
        then(publisher).shouldHaveNoInteractions();
        then(bruteForce).should(org.mockito.Mockito.never()).recordFailure(any(), anyString(), anyString());
        assertThat(counted(SignInFlow.ACCEPT_INVITE, TenantAccess.READ_ONLY, "throttled")).isEqualTo(1);
    }

    @Test
    @DisplayName("a lockout about to end still asks for at least a second")
    void retryAfterAtLeastOneSecond() {
        org.mockito.BDDMockito.given(bruteForce.isLocked(any(), anyString(), anyString())).willReturn(true);
        org.mockito.BDDMockito.given(bruteForce.getRemainingLockout(any(), anyString(), anyString()))
                .willReturn(java.time.Duration.ZERO);

        assertThat(refusals.record(new TenantSuspendedSignInException(TENANT, USER, SignInFlow.LOGIN,
                TenantAccess.NONE))).isEqualTo(new SignInRefusals.Outcome.Throttled(java.time.Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("refuses to record inside a transaction: the event would roll back with the refusal")
    void notInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> refusals.record(new TenantSuspendedSignInException(TENANT, USER,
                    SignInFlow.LOGIN, TenantAccess.NONE))).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a failed audit write propagates and is not counted: no unaudited refusal")
    void auditFailurePropagates() {
        willThrow(new IllegalStateException("outbox down")).given(publisher)
                .publishAuth(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> refusals.record(new TenantSuspendedSignInException(TENANT, USER,
                SignInFlow.LOGIN, TenantAccess.NONE))).isInstanceOf(IllegalStateException.class);
        assertThat(counted(SignInFlow.LOGIN, TenantAccess.NONE)).isZero();
    }

    @Test
    @DisplayName("a tenant with full access is never a refusal")
    void fullAccessIsNoRefusal() {
        assertThatThrownBy(() -> new TenantSuspendedSignInException(TENANT, USER, SignInFlow.LOGIN,
                TenantAccess.FULL)).isInstanceOf(IllegalArgumentException.class);
    }
}
