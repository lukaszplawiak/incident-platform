package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.TenantStatus;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.auth.repository.TenantStatusView;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/** What each tenant status lets a tenant do (backlog #0-82). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantAccessService")
class TenantAccessServiceTest {

    private static final String TENANT = "acme";

    @Mock private TenantRepository tenantRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TenantAccessService service() {
        return new TenantAccessService(tenantRepository, meterRegistry);
    }

    /** Stubs both lookups: the per-request one and the share-locked one of sign-ins and writes. */
    private void status(TenantStatus status, SuspensionMode mode) {
        org.mockito.Mockito.lenient().when(tenantRepository.findStatus(TENANT))
                .thenReturn(Optional.of(new TenantStatusView(status, mode)));
        org.mockito.Mockito.lenient().when(tenantRepository.findStatusForSignIn(TENANT))
                .thenReturn(Optional.of(new TenantRepository.LockedTenantStatus() {
                    @Override
                    public String getStatus() {
                        return status.name();
                    }

                    @Override
                    public String getMode() {
                        return mode == null ? null : mode.name();
                    }
                }));
    }

    @Test
    @DisplayName("active: full access; suspended: the mode's; offboarding or offboarded: none; no row: full")
    void accessByStatus() {
        status(TenantStatus.ACTIVE, null);
        assertThat(service().accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
        status(TenantStatus.SUSPENDED, SuspensionMode.FULL);
        assertThat(service().accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
        status(TenantStatus.SUSPENDED, SuspensionMode.READ_ONLY);
        assertThat(service().accessOf(TENANT)).isEqualTo(TenantAccess.READ_ONLY);
        status(TenantStatus.OFFBOARDING, null);
        assertThat(service().accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
        status(TenantStatus.OFFBOARDED, null);
        assertThat(service().accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
        given(tenantRepository.findStatus(TENANT)).willReturn(Optional.empty());
        assertThat(service().accessOf(TENANT)).as("a gap in the table locks nobody out").isEqualTo(TenantAccess.FULL);
    }

    @Test
    @DisplayName("sign-in: refused only when suspended in full, 403 TENANT_SUSPENDED")
    void signIn() {
        status(TenantStatus.SUSPENDED, SuspensionMode.READ_ONLY);
        assertThatCode(() -> service().requireCanSignIn(TENANT)).doesNotThrowAnyException();
        status(TenantStatus.SUSPENDED, SuspensionMode.FULL);
        assertThatThrownBy(() -> service().requireCanSignIn(TENANT))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.TENANT_SUSPENDED);
                });
    }

    @Test
    @DisplayName("write without a principal: refused when suspended in either mode, with the mode's code")
    void write() {
        status(TenantStatus.ACTIVE, null);
        assertThatCode(() -> service().requireCanWrite(TENANT)).doesNotThrowAnyException();
        status(TenantStatus.SUSPENDED, SuspensionMode.READ_ONLY);
        assertThatThrownBy(() -> service().requireCanWrite(TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.TENANT_READ_ONLY));
        status(TenantStatus.SUSPENDED, SuspensionMode.FULL);
        assertThatThrownBy(() -> service().requireCanWrite(TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.TENANT_SUSPENDED));
    }

    @Test
    @DisplayName("sign-in and invite checks use the share-locked lookup, never the plain one (backlog #0-82 review)")
    void signInUsesLockedLookup() {
        given(tenantRepository.findStatusForSignIn(TENANT)).willReturn(Optional.empty());

        service().requireCanSignIn(TENANT);
        service().requireCanWrite(TENANT);

        org.mockito.BDDMockito.then(tenantRepository).should(org.mockito.Mockito.times(2)).findStatusForSignIn(TENANT);
        org.mockito.BDDMockito.then(tenantRepository).should(org.mockito.Mockito.never()).findStatus(TENANT);
    }

    @Test
    @DisplayName("the locked lookup is bounded by the sign-in lock timeout, reset right after it (review of #0-82)")
    void lockTimeoutAroundLookupOnly() {
        given(tenantRepository.findStatusForSignIn(TENANT)).willReturn(Optional.empty());
        final TenantAccessService service = service();

        // Both entry points share the bounded lookup.
        for (final Runnable check : List.<Runnable>of(() -> service.requireCanSignIn(TENANT),
                () -> service.requireCanWrite(TENANT))) {
            org.mockito.Mockito.clearInvocations(tenantRepository);
            check.run();
            final org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantRepository);
            order.verify(tenantRepository).setLocalLockTimeout(TenantAccessService.SIGN_IN_LOCK_TIMEOUT);
            order.verify(tenantRepository).findStatusForSignIn(TENANT);
            order.verify(tenantRepository).resetLocalLockTimeout();
        }
    }

    @Test
    @DisplayName("a lock timeout is a 503 with Retry-After, not a refusal, and nothing more runs in the aborted "
            + "transaction")
    void lockTimeoutIsBusy() {
        given(tenantRepository.findStatusForSignIn(TENANT)).willThrow(
                new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service().requireCanSignIn(TENANT))
                .isInstanceOfSatisfying(TenantStatusBusyException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.retryAfter()).isEqualTo(TenantAccessService.SIGN_IN_RETRY_AFTER);
                });
        org.mockito.BDDMockito.then(tenantRepository).should(org.mockito.Mockito.never()).resetLocalLockTimeout();
    }

    @Test
    @DisplayName("a tenant without a row: counted on every lookup, so the alert sees it")
    void missingRowCounted() {
        given(tenantRepository.findStatus(TENANT)).willReturn(Optional.empty());
        final TenantAccessService service = service();

        service.accessOf(TENANT);
        service.accessOf(TENANT);

        assertThat(meterRegistry.counter(TenantAccessService.MISSING_ROW_COUNTER).count()).isEqualTo(2.0);
    }
}
