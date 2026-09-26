package com.incidentplatform.auth.bootstrap;

import com.incidentplatform.auth.bootstrap.OperatorTenantBootstrap.Outcome;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorTenantBootstrap — operator admin reconciler (backlog #0-16, #0-49)")
class OperatorTenantBootstrapTest {

    private static final String EMAIL = "ops@incident-platform.local";
    private static final String TENANT = ReservedTenants.PLATFORM_OPERATOR;

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenRepository authTokenRepository;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private UserService userService;
    @Mock private ResendInviteService resendInviteService;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private OperatorTenantBootstrap reconciler(String email) {
        return new OperatorTenantBootstrap(email, userRepository, authTokenRepository,
                outboxRepository, userService, resendInviteService, meterRegistry);
    }

    private double pendingGauge() {
        return meterRegistry.get(OperatorTenantBootstrap.PENDING_GAUGE).gauge().value();
    }

    private static User pendingAdmin() {
        return User.forTesting(UUID.randomUUID(), TENANT, EMAIL, null, true,
                List.of(SecurityRoles.ROLE_ADMIN));
    }

    private void noActiveAdmin() {
        given(userRepository.existsActiveAcceptedUserWithRole(TENANT, Role.ROLE_ADMIN))
                .willReturn(false);
    }

    private void latestInvite(User user, AuthEmailStatus status) {
        final AuthEmailOutbox entry = mock(AuthEmailOutbox.class);
        given(entry.getStatus()).willReturn(status);
        given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                user.getId(), AuthEmailType.INVITE)).willReturn(Optional.of(entry));
    }

    private void validInviteTokens(User user, int count) {
        final List<AuthToken> tokens = count == 0 ? List.of() : List.of(mock(AuthToken.class));
        given(authTokenRepository.findValidByUserIdAndType(
                eq(user.getId()), eq(AuthToken.Type.INVITE), any())).willReturn(tokens);
    }

    @Test
    @DisplayName("disabled when no admin email is configured: no queries, no gauge")
    void disabled() {
        final Outcome outcome = reconciler("  ").reconcile();

        assertThat(outcome).isEqualTo(Outcome.DISABLED);
        verifyNoInteractions(userRepository, userService, resendInviteService);
        assertThat(meterRegistry.find(OperatorTenantBootstrap.PENDING_GAUGE).gauge()).isNull();
    }

    @Test
    @DisplayName("an active admin who accepted exists: nothing to do, gauge 0")
    void adminActive() {
        given(userRepository.existsActiveAcceptedUserWithRole(TENANT, Role.ROLE_ADMIN))
                .willReturn(true);
        final OperatorTenantBootstrap reconciler = reconciler(EMAIL);

        assertThat(reconciler.reconcile()).isEqualTo(Outcome.ADMIN_ACTIVE);
        assertThat(pendingGauge()).isZero();
        then(userService).shouldHaveNoInteractions();
        then(resendInviteService).shouldHaveNoInteractions();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("empty tenant: invites the configured address as ADMIN, in the operator tenant")
    void invitesFirstAdmin() {
        noActiveAdmin();
        given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.empty());
        given(userRepository.existsByTenantId(TENANT)).willReturn(false);
        final AtomicReference<String> tenantDuringCall = new AtomicReference<>();
        willAnswer(inv -> {
            tenantDuringCall.set(TenantContext.get());
            return null;
        }).given(userService).createUser(any());
        final OperatorTenantBootstrap reconciler = reconciler(EMAIL);

        assertThat(reconciler.reconcile()).isEqualTo(Outcome.INVITED);
        then(userService).should().createUser(
                new CreateUserRequest(EMAIL, List.of(SecurityRoles.ROLE_ADMIN)));
        assertThat(tenantDuringCall.get()).isEqualTo(TENANT);
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(pendingGauge()).isEqualTo(1.0);
    }

    @Nested
    @DisplayName("configured admin invited but not accepted")
    class PendingAdmin {

        private User user;

        @BeforeEach
        void pending() {
            noActiveAdmin();
            user = pendingAdmin();
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.of(user));
        }

        @Test
        @DisplayName("invite permanently failed: re-invites through ResendInviteService")
        void permanentlyFailedIsReissued() {
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            final OperatorTenantBootstrap reconciler = reconciler(EMAIL);

            assertThat(reconciler.reconcile()).isEqualTo(Outcome.REINVITED);
            then(resendInviteService).should().resendInvite(user.getId());
            assertThat(pendingGauge()).isEqualTo(1.0);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AuthEmailStatus.class, names = {"PENDING", "FAILED"})
        @DisplayName("invite still being sent: waits, no new email")
        void inFlightIsLeftAlone(AuthEmailStatus status) {
            latestInvite(user, status);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.INVITE_IN_PROGRESS);
            then(resendInviteService).should(never()).resendInvite(any());
        }

        @Test
        @DisplayName("invite sent and its token still valid: waits, no new email")
        void sentAndValidIsLeftAlone() {
            latestInvite(user, AuthEmailStatus.SENT);
            validInviteTokens(user, 1);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.INVITE_IN_PROGRESS);
            then(resendInviteService).should(never()).resendInvite(any());
        }

        @Test
        @DisplayName("invite sent but its token expired: re-invites")
        void sentButExpiredIsReissued() {
            latestInvite(user, AuthEmailStatus.SENT);
            validInviteTokens(user, 0);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.REINVITED);
            then(resendInviteService).should().resendInvite(user.getId());
        }

        /**
         * Backlog #0-52: the scheduler marks an entry SUPERSEDED when its token
         * was used or invalidated before sending; whether a valid invite token
         * exists then decides, as for SENT.
         */
        @Test
        @DisplayName("invite superseded and no valid token: re-invites (backlog #0-52)")
        void supersededWithoutValidTokenIsReissued() {
            latestInvite(user, AuthEmailStatus.SUPERSEDED);
            validInviteTokens(user, 0);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.REINVITED);
            then(resendInviteService).should().resendInvite(user.getId());
        }

        @Test
        @DisplayName("invite superseded but a valid token exists: waits (backlog #0-52)")
        void supersededWithValidTokenIsLeftAlone() {
            latestInvite(user, AuthEmailStatus.SUPERSEDED);
            validInviteTokens(user, 1);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.INVITE_IN_PROGRESS);
            then(resendInviteService).should(never()).resendInvite(any());
        }

        @Test
        @DisplayName("no outbox entry and no valid token: re-invites")
        void noInviteAtAllIsReissued() {
            given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    user.getId(), AuthEmailType.INVITE)).willReturn(Optional.empty());
            validInviteTokens(user, 0);

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.REINVITED);
        }

        @Test
        @DisplayName("ResendInviteService's 409 (state changed meanwhile) is benign: waits, not FAILED")
        void conflictFromResendIsBenign() {
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            willAnswer(inv -> {
                throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                        "An invite email is already queued", HttpStatus.CONFLICT);
            }).given(resendInviteService).resendInvite(user.getId());

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.INVITE_IN_PROGRESS);
        }

        @Test
        @DisplayName("any other BusinessException from ResendInviteService is a failure")
        void otherBusinessExceptionFails() {
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            willAnswer(inv -> {
                throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION, "nope",
                        HttpStatus.BAD_REQUEST);
            }).given(resendInviteService).resendInvite(user.getId());

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.FAILED);
        }

        @Test
        @DisplayName("a failure is logged and reported, not thrown; tenant context cleared")
        void failureIsReportedNotThrown() {
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            willAnswer(inv -> {
                throw new IllegalStateException("db down");
            }).given(resendInviteService).resendInvite(user.getId());
            final OperatorTenantBootstrap reconciler = reconciler(EMAIL);

            assertThat(reconciler.reconcile()).isEqualTo(Outcome.FAILED);
            assertThat(pendingGauge()).isEqualTo(1.0);
            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    @Nested
    @DisplayName("data a human has to fix: never creates a second admin or removes a user")
    class Conflicts {

        @BeforeEach
        void noAdmin() {
            noActiveAdmin();
        }

        @Test
        @DisplayName("tenant has users, none of them the configured address")
        void otherUsersOnly() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.empty());
            given(userRepository.existsByTenantId(TENANT)).willReturn(true);
            final OperatorTenantBootstrap reconciler = reconciler(EMAIL);

            assertThat(reconciler.reconcile()).isEqualTo(Outcome.CONFLICT);
            then(userService).shouldHaveNoInteractions();
            then(resendInviteService).shouldHaveNoInteractions();
            assertThat(pendingGauge()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("configured user accepted but is not an admin")
        void acceptedButNotAdmin() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.of(
                    User.forTesting(UUID.randomUUID(), TENANT, EMAIL, "hash", true,
                            List.of(SecurityRoles.ROLE_RESPONDER))));

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.CONFLICT);
            then(resendInviteService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("configured admin is deactivated")
        void deactivated() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.of(
                    User.forTesting(UUID.randomUUID(), TENANT, EMAIL, null, false,
                            List.of(SecurityRoles.ROLE_ADMIN))));

            assertThat(reconciler(EMAIL).reconcile()).isEqualTo(Outcome.CONFLICT);
            then(resendInviteService).shouldHaveNoInteractions();
        }
    }

    /**
     * The gauge is refreshed on every replica, not only the one that wins the
     * reconcile lock — otherwise a replica that once reported 1 would keep the
     * alert's max() firing after the problem was fixed.
     */
    @Nested
    @DisplayName("gauge refresh (every replica, read-only)")
    class GaugeRefresh {

        @Test
        @DisplayName("disabled: no query")
        void disabled() {
            reconciler("").refreshPendingGauge();

            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("reports 0 once an admin can log in and 1 while none can, without acting")
        void followsTheDatabase() {
            final OperatorTenantBootstrap reconciler = reconciler(EMAIL);
            given(userRepository.existsActiveAcceptedUserWithRole(TENANT, Role.ROLE_ADMIN))
                    .willReturn(false, true);

            reconciler.refreshPendingGauge();
            assertThat(pendingGauge()).isEqualTo(1.0);
            reconciler.refreshPendingGauge();
            assertThat(pendingGauge()).isZero();

            then(userService).shouldHaveNoInteractions();
            then(resendInviteService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a database error keeps the last value instead of paging for the wrong cause")
        void errorKeepsLastValue() {
            final OperatorTenantBootstrap reconciler = reconciler(EMAIL);
            given(userRepository.existsActiveAcceptedUserWithRole(TENANT, Role.ROLE_ADMIN))
                    .willReturn(true)
                    .willThrow(new IllegalStateException("db down"));

            reconciler.refreshPendingGauge();
            reconciler.refreshPendingGauge();

            assertThat(pendingGauge()).isZero();
        }
    }
}
