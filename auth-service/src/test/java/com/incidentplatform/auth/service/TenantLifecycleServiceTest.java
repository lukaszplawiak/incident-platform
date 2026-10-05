package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.SuspensionReason;
import com.incidentplatform.auth.domain.Tenant;
import com.incidentplatform.auth.domain.TenantStatus;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * Suspending and resuming a tenant (backlog #0-82): the conditional updates,
 * the end of sessions when (and only when) a tenant becomes suspended in full,
 * the audit in both tenants and the counter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantLifecycleService")
class TenantLifecycleServiceTest {

    private static final String TENANT = "acme";
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final String NOTE = "Invoice 2026-09 unpaid, ticket #4411";

    @Mock private TenantRepository tenantRepository;
    @Mock private AuthTokenRepository authTokenRepository;
    @Mock private AuditEventPublisher auditEventPublisher;

    private SimpleMeterRegistry meters;
    private TenantLifecycleService service;
    private final UserPrincipal operator = new UserPrincipal(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR,
            "ops@platform.test", List.of("ROLE_ADMIN"), List.of());

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        service = new TenantLifecycleService(tenantRepository, authTokenRepository, auditEventPublisher, meters);
    }

    private void current(TenantStatus status, SuspensionMode mode) {
        final Tenant tenant = mock(Tenant.class);
        given(tenant.getStatus()).willReturn(status);
        org.mockito.Mockito.lenient().when(tenant.getSuspensionMode()).thenReturn(mode);
        org.mockito.Mockito.lenient().when(tenant.getSuspensionReason())
                .thenReturn(mode == null ? null : SuspensionReason.BILLING);
        given(tenantRepository.findByIdForUpdate(TENANT)).willReturn(Optional.of(tenant));
    }

    private double counted(String action) {
        return meters.get(TenantLifecycleService.COUNTER).tag("action", action).counter().count();
    }

    @Test
    @DisplayName("suspending in full ends the tenant's sessions and audits both tenants, the note only for the operator")
    void suspendFull() {
        current(TenantStatus.ACTIVE, null);
        given(tenantRepository.suspend(TENANT, "FULL", "SECURITY", NOTE, OPERATOR_ID)).willReturn(1);
        given(authTokenRepository.invalidateSessionsOfTenant(eq(TENANT), any())).willReturn(7);

        service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.SECURITY, "  " + NOTE + " ", operator);

        then(authTokenRepository).should().invalidateSessionsOfTenant(eq(TENANT), any());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> operatorSide = ArgumentCaptor.forClass(Map.class);
        then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                eq(AuditEventTypes.TENANT_SUSPENDED), anyString(), eq(OPERATOR_ID.toString()), anyString(),
                operatorSide.capture());
        assertThat(operatorSide.getValue()).containsEntry("note", NOTE).containsEntry("tenantId", TENANT)
                .containsEntry("mode", "FULL").containsEntry("previousMode", "NONE")
                .containsEntry("sessionsEnded", "7");
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> customerSide = ArgumentCaptor.forClass(Map.class);
        then(auditEventPublisher).should().publishAuth(eq(TenantLifecycleService.tenantResource(TENANT)),
                eq(TENANT), eq(AuditEventTypes.TENANT_SUSPENDED), anyString(), eq("platform-operator"),
                anyString(), customerSide.capture());
        assertThat(customerSide.getValue()).doesNotContainKey("note").containsEntry("reason", "SECURITY");
        assertThat(counted("suspended")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("read-only keeps the sessions; read-only to full ends them; full to read-only does not")
    void sessionsOnlyWhenBecomingFull() {
        current(TenantStatus.ACTIVE, null);
        given(tenantRepository.suspend(TENANT, "READ_ONLY", "BILLING", NOTE, OPERATOR_ID)).willReturn(1);
        service.suspend(TENANT, SuspensionMode.READ_ONLY, SuspensionReason.BILLING, NOTE, operator);
        then(authTokenRepository).should(never()).invalidateSessionsOfTenant(any(), any());

        current(TenantStatus.SUSPENDED, SuspensionMode.READ_ONLY);
        given(tenantRepository.suspend(TENANT, "FULL", "BILLING", NOTE, OPERATOR_ID)).willReturn(1);
        service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.BILLING, NOTE, operator);
        then(authTokenRepository).should().invalidateSessionsOfTenant(eq(TENANT), any());

        current(TenantStatus.SUSPENDED, SuspensionMode.FULL);
        service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.BILLING, NOTE, operator);
        then(authTokenRepository).should(org.mockito.Mockito.times(1)).invalidateSessionsOfTenant(any(), any());
    }

    @Test
    @DisplayName("the tenant in no state to be suspended (offboarding) is a 409, nothing audited")
    void conflict() {
        current(TenantStatus.OFFBOARDING, null);
        given(tenantRepository.suspend(TENANT, "FULL", "TERMS", NOTE, OPERATOR_ID)).willReturn(0);

        assertThatThrownBy(() -> service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.TERMS, NOTE, operator))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
        then(auditEventPublisher).shouldHaveNoInteractions();
        then(authTokenRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("refuses the reserved tenants, a malformed id, a missing mode or reason, and 404s an unknown tenant")
    void refusals() {
        for (final String tenant : List.of(ReservedTenants.PLATFORM_OPERATOR, ReservedTenants.LEGACY_SYSTEM, "Bad Id")) {
            assertThatThrownBy(() -> service.suspend(tenant, SuspensionMode.FULL, SuspensionReason.OTHER, NOTE, operator))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> service.suspend(TENANT, null, SuspensionReason.OTHER, NOTE, operator))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.suspend(TENANT, SuspensionMode.FULL, null, NOTE, operator))
                .isInstanceOf(BusinessException.class);
        // A security suspension must be full: read-only would leave an intruder's sessions (review of #0-82).
        assertThatThrownBy(() -> service.suspend(TENANT, SuspensionMode.READ_ONLY, SuspensionReason.SECURITY,
                NOTE, operator))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("SECURITY suspension must be FULL");
                });
        assertThatThrownBy(() -> service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.OTHER, NOTE, operator))
                .isInstanceOf(ResourceNotFoundException.class);
        then(tenantRepository).should(never()).suspend(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "line\nbreak", "bidi‮override"})
    @DisplayName("refuses a blank note or one that could forge a log or audit line")
    void unsafeNote(String note) {
        assertThatThrownBy(() -> service.suspend(TENANT, SuspensionMode.FULL, SuspensionReason.OTHER, note, operator))
                .isInstanceOf(BusinessException.class).hasMessageContaining("note");
        assertThatThrownBy(() -> service.resume(TENANT, note, operator))
                .isInstanceOf(BusinessException.class).hasMessageContaining("note");
        then(tenantRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("resuming audits both tenants with what the suspension was; a tenant not suspended is a 409")
    void resume() {
        current(TenantStatus.SUSPENDED, SuspensionMode.READ_ONLY);
        given(tenantRepository.resume(TENANT)).willReturn(1, 0);

        service.resume(TENANT, "Paid in full", operator);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> customerSide = ArgumentCaptor.forClass(Map.class);
        then(auditEventPublisher).should().publishAuth(eq(TenantLifecycleService.tenantResource(TENANT)),
                eq(TENANT), eq(AuditEventTypes.TENANT_RESUMED), anyString(), eq("platform-operator"), anyString(),
                customerSide.capture());
        assertThat(customerSide.getValue()).containsEntry("previousMode", "READ_ONLY").doesNotContainKey("note");
        then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                eq(AuditEventTypes.TENANT_RESUMED), anyString(), eq(OPERATOR_ID.toString()), anyString(),
                any());
        assertThat(counted("resumed")).isEqualTo(1.0);

        assertThatThrownBy(() -> service.resume(TENANT, "again", operator))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("counts a suspension only once its transaction commits")
    void countsAfterCommit() {
        current(TenantStatus.ACTIVE, null);
        given(tenantRepository.suspend(TENANT, "READ_ONLY", "BILLING", NOTE, OPERATOR_ID)).willReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.suspend(TENANT, SuspensionMode.READ_ONLY, SuspensionReason.BILLING, NOTE, operator);
            assertThat(counted("suspended")).isZero();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(counted("suspended")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the customer-side resource id is stable per tenant and different across tenants")
    void tenantResource() {
        assertThat(TenantLifecycleService.tenantResource("acme")).isEqualTo(TenantLifecycleService.tenantResource("acme"))
                .isNotEqualTo(TenantLifecycleService.tenantResource("globex"));
    }
}
