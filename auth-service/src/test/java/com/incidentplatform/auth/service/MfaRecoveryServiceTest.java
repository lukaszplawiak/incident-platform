package com.incidentplatform.auth.service;

import com.incidentplatform.auth.config.MfaRecoveryProperties;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaRecoveryCloseReason;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.domain.MfaRecoveryStatus;
import com.incidentplatform.auth.domain.MfaVerificationMethod;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.MfaRecoveryRequestRepository;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * The rules of operator-assisted MFA recovery (backlog #0-90): who may be
 * recovered, what each tenant's audit trail gets, and that the reset runs only
 * past the waiting period, after the checks again, and only for the call whose
 * conditional UPDATE won. The SQL runs against Postgres in
 * {@code AuthRepositoryIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MfaRecoveryService")
class MfaRecoveryServiceTest {

    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration WAIT = Duration.ofHours(72);
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String NOTE = "Video call with J. Doe 2026-10-04, ID card checked";

    @Mock private MfaRecoveryRequestRepository requestRepository;
    @Mock private UserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AuthTokenRepository tokenRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private AuthEmailRequestService emailRequests;
    @Mock private MfaService mfaService;
    @Mock private AuditEventPublisher auditEventPublisher;

    private SimpleMeterRegistry meters;
    private MfaRecoveryService service;
    private final UserPrincipal operator = new UserPrincipal(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR,
            "ops@platform.test", List.of("ROLE_ADMIN"), List.of());

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        service = new MfaRecoveryService(requestRepository, userRepository, tenantRepository, tokenRepository,
                authTokenService, emailRequests, mfaService, auditEventPublisher,
                new MfaRecoveryProperties(WAIT, 300_000L, 20), meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User admin(boolean mfa) {
        final User user = User.forTesting(ADMIN_ID, TENANT, "admin@acme.test", "hash", true, List.of("ROLE_ADMIN"));
        if (mfa) {
            user.storePendingMfaSecret("encrypted");
            user.enableMfa();
        }
        return user;
    }

    private double counted(String outcome) {
        return meters.get(MfaRecoveryService.COUNTER).tag("outcome", outcome).counter().count();
    }

    private void lockedOutSoleAdmin(User user) {
        given(tenantRepository.existsById(TENANT)).willReturn(true);
        given(userRepository.findByIdAndTenantId(ADMIN_ID, TENANT)).willReturn(Optional.of(user));
    }

    /** The operator who asked is still an operator admin (rechecked at execution, security review). */
    private void operatorStillAdmin() {
        given(userRepository.findByIdAndTenantId(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR)).willReturn(
                Optional.of(User.forTesting(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR, "ops@platform.test",
                        "hash", true, List.of("ROLE_ADMIN"))));
    }

    private static MfaRecoveryRequest pending(Instant noticeSentAt) {
        final MfaRecoveryRequest request = MfaRecoveryRequest.open(TENANT, ADMIN_ID, OPERATOR_ID,
                MfaVerificationMethod.VIDEO_CALL, NOTE, NOW.minus(Duration.ofDays(4)));
        ReflectionTestUtils.setField(request, "noticeSentAt", noticeSentAt);
        return request;
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        @DisplayName("records the request, queues its notice and audits it in both tenants, the note only for the operator")
        void recordsAndAudits() {
            final User user = admin(true);
            lockedOutSoleAdmin(user);

            final MfaRecoveryRequest request = service.request(TENANT, ADMIN_ID,
                    MfaVerificationMethod.VIDEO_CALL, "  " + NOTE + " ", operator);

            assertThat(request.getStatus()).isEqualTo(MfaRecoveryStatus.PENDING);
            assertThat(request.getVerificationNote()).isEqualTo(NOTE);
            assertThat(request.getCreatedAt()).isEqualTo(NOW);
            final var order = inOrder(requestRepository, emailRequests);
            order.verify(requestRepository).saveAndFlush(request);
            order.verify(emailRequests).requestMfaRecoveryNotice(user, request.getId());

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> operatorSide = ArgumentCaptor.forClass(Map.class);
            then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                    eq(AuditEventTypes.MFA_RECOVERY_REQUESTED), anyString(), eq(OPERATOR_ID.toString()),
                    anyString(), operatorSide.capture());
            assertThat(operatorSide.getValue()).containsEntry("verificationNote", NOTE)
                    .containsEntry("tenantId", TENANT).containsEntry("userId", ADMIN_ID.toString())
                    .containsEntry("requestId", request.getId().toString());

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> tenantSide = ArgumentCaptor.forClass(Map.class);
            then(auditEventPublisher).should().publishAuth(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_REQUESTED), anyString(), eq("platform-operator"),
                    anyString(), tenantSide.capture());
            assertThat(tenantSide.getValue()).doesNotContainKey("verificationNote")
                    .containsEntry("verificationMethod", "VIDEO_CALL");
            assertThat(counted("requested")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("counts the request only once its transaction commits (review: no alert on a rollback)")
        void countsAfterCommit() {
            lockedOutSoleAdmin(admin(true));
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator);
                assertThat(counted("requested")).as("not yet committed").isZero();

                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                        .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
                assertThat(counted("requested")).isEqualTo(1.0);
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("a rolled-back request is never counted")
        void rollbackNotCounted() {
            lockedOutSoleAdmin(admin(true));
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator);
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(
                                org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(counted("requested")).isZero();
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("refuses a reserved tenant: an operator admin has break-glass")
        void reservedTenantRefused() {
            assertThatThrownBy(() -> service.request(ReservedTenants.PLATFORM_OPERATOR, ADMIN_ID,
                    MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            then(requestRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("refuses a malformed tenant id and a missing method")
        void malformedInputRefused() {
            assertThatThrownBy(() -> service.request("Not A Slug", ADMIN_ID,
                    MfaVerificationMethod.VIDEO_CALL, NOTE, operator)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, null, NOTE, operator))
                    .isInstanceOf(BusinessException.class);
            then(requestRepository).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "forged\nline", "bidi‮override", "sep arator"})
        @DisplayName("refuses a blank note or one that could forge a log or audit line")
        void unsafeNoteRefused(String note) {
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.OTHER, note, operator))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("verificationNote");
            then(requestRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("refuses a note over 500 characters")
        void longNoteRefused() {
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.OTHER,
                    "x".repeat(501), operator)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("404 for an unknown tenant or user")
        void unknownTenantOrUser() {
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(ResourceNotFoundException.class);
            given(tenantRepository.existsById(TENANT)).willReturn(true);
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("refuses a user who is not an active admin with MFA and an accepted invite")
        void notApplicableRefused() {
            final User noMfa = admin(false);
            final User responder = User.forTesting(ADMIN_ID, TENANT, "r@acme.test", "hash", true,
                    List.of("ROLE_RESPONDER"));
            final User invited = User.forTesting(ADMIN_ID, TENANT, "i@acme.test", null, true, List.of("ROLE_ADMIN"));
            final User deactivated = admin(true);
            deactivated.setActive(false);
            given(tenantRepository.existsById(TENANT)).willReturn(true);
            for (final User user : List.of(noMfa, responder, invited, deactivated)) {
                given(userRepository.findByIdAndTenantId(ADMIN_ID, TENANT)).willReturn(Optional.of(user));
                assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL,
                        NOTE, operator))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus())
                                .isEqualTo(HttpStatus.CONFLICT));
            }
            then(requestRepository).should(never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("refuses while the tenant has another active admin, who can reset it (#0-88)")
        void otherAdminRefused() {
            lockedOutSoleAdmin(admin(true));
            given(userRepository.countActiveAcceptedUsersWithRoleExcluding(TENANT, Role.ROLE_ADMIN, ADMIN_ID))
                    .willReturn(1L);

            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("another active admin");
            then(requestRepository).should(never()).saveAndFlush(any());
            then(emailRequests).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("refuses a second open request, also when the unique index catches a race")
        void secondOpenRequestRefused() {
            lockedOutSoleAdmin(admin(true));
            given(requestRepository.findPendingByUserId(ADMIN_ID)).willReturn(Optional.of(pending(null)));
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("open MFA recovery request");

            given(requestRepository.findPendingByUserId(ADMIN_ID)).willReturn(Optional.empty());
            given(requestRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uq"));
            assertThatThrownBy(() -> service.request(TENANT, ADMIN_ID, MfaVerificationMethod.VIDEO_CALL, NOTE, operator))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("open MFA recovery request");
            then(emailRequests).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        private AuthToken cancelToken() {
            final AuthToken token = mock(AuthToken.class);
            given(token.getUser()).willReturn(admin(true));
            given(token.getTenantId()).willReturn(TENANT);
            given(authTokenService.consumeToken("raw", AuthToken.Type.MFA_RECOVERY_CANCEL)).willReturn(token);
            return token;
        }

        @Test
        @DisplayName("the account's link closes the open request, invalidates its links and audits both tenants")
        void byAccount() {
            cancelToken();
            final MfaRecoveryRequest request = pending(null);
            given(requestRepository.findPendingByUserId(ADMIN_ID)).willReturn(Optional.of(request));
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.CANCELLED,
                    MfaRecoveryCloseReason.CANCELLED_BY_ACCOUNT, ADMIN_ID, NOW)).willReturn(1);

            assertThat(service.cancelByAccount("raw")).isTrue();

            then(tokenRepository).should().invalidateValidTokens(ADMIN_ID, AuthToken.Type.MFA_RECOVERY_CANCEL, NOW);
            then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                    eq(AuditEventTypes.MFA_RECOVERY_CANCELLED), anyString(), eq(ADMIN_ID.toString()), anyString(),
                    anyMap());
            then(auditEventPublisher).should().publishAuth(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_CANCELLED), anyString(), eq(ADMIN_ID.toString()), anyString(),
                    anyMap());
            assertThat(counted("cancelled_by_account")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a link with no open request left does nothing")
        void byAccountNothingOpen() {
            cancelToken();
            assertThat(service.cancelByAccount("raw")).isFalse();
            then(requestRepository).should(never()).close(any(), any(), any(), any(), any());
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a token of another tenant than the open request closes nothing")
        void byAccountOtherTenant() {
            cancelToken();
            final MfaRecoveryRequest otherTenants = MfaRecoveryRequest.open("globex", ADMIN_ID, OPERATOR_ID,
                    MfaVerificationMethod.VIDEO_CALL, NOTE, NOW);
            given(requestRepository.findPendingByUserId(ADMIN_ID)).willReturn(Optional.of(otherTenants));

            assertThat(service.cancelByAccount("raw")).isFalse();
            then(requestRepository).should(never()).close(any(), any(), any(), any(), any());
            then(tokenRepository).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("an invalid token is the token service's 401, nothing is read")
        void byAccountInvalidToken() {
            given(authTokenService.consumeToken("bad", AuthToken.Type.MFA_RECOVERY_CANCEL))
                    .willThrow(new BusinessException("UNAUTHORIZED", "invalid", HttpStatus.UNAUTHORIZED));
            assertThatThrownBy(() -> service.cancelByAccount("bad")).isInstanceOf(BusinessException.class);
            then(requestRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("an operator cancels; 409 once the request has ended, 404 for an unknown one")
        void byOperator() {
            final MfaRecoveryRequest request = pending(null);
            given(requestRepository.findById(request.getId())).willReturn(Optional.of(request));
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.CANCELLED,
                    MfaRecoveryCloseReason.CANCELLED_BY_OPERATOR, OPERATOR_ID, NOW)).willReturn(1, 0);

            service.cancelByOperator(request.getId(), operator);
            assertThat(counted("cancelled_by_operator")).isEqualTo(1.0);
            // The customer tenant sees "platform-operator", never the operator account's id.
            then(auditEventPublisher).should().publishAuth(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_CANCELLED), anyString(), eq("platform-operator"), anyString(),
                    anyMap());
            then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                    eq(AuditEventTypes.MFA_RECOVERY_CANCELLED), anyString(), eq(OPERATOR_ID.toString()), anyString(),
                    anyMap());

            assertThatThrownBy(() -> service.cancelByOperator(request.getId(), operator))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("already ended");
            assertThatThrownBy(() -> service.cancelByOperator(UUID.randomUUID(), operator))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        private MfaRecoveryRequest due() {
            final MfaRecoveryRequest request = pending(NOW.minus(WAIT));
            given(requestRepository.findById(request.getId())).willReturn(Optional.of(request));
            operatorStillAdmin();
            return request;
        }

        @Test
        @DisplayName("the operator who asked is no longer an operator admin: cancelled with OPERATOR_NO_LONGER_ADMIN")
        void operatorNoLongerAdmin() {
            final MfaRecoveryRequest request = pending(NOW.minus(WAIT));
            given(requestRepository.findById(request.getId())).willReturn(Optional.of(request));
            final User deactivated = User.forTesting(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR,
                    "ops@platform.test", "hash", false, List.of("ROLE_ADMIN"));
            final User demoted = User.forTesting(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR,
                    "ops@platform.test", "hash", true, List.of("ROLE_RESPONDER"));
            given(userRepository.findByIdAndTenantId(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR))
                    .willReturn(Optional.of(deactivated), Optional.of(demoted), Optional.empty());
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT)).willReturn(Optional.of(admin(true)));
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.CANCELLED,
                    MfaRecoveryCloseReason.OPERATOR_NO_LONGER_ADMIN, null, NOW)).willReturn(1);

            for (int i = 0; i < 3; i++) {
                assertThat(service.execute(request.getId())).isFalse();
            }
            then(requestRepository).should(org.mockito.Mockito.times(3)).close(request.getId(),
                    MfaRecoveryStatus.CANCELLED, MfaRecoveryCloseReason.OPERATOR_NO_LONGER_ADMIN, null, NOW);
            then(mfaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("claims the request, then resets factor, password and sessions and audits it as the platform")
        void runs() {
            final MfaRecoveryRequest request = due();
            final User user = admin(true);
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT)).willReturn(Optional.of(user));
            given(userRepository.findByIdAndTenantId(ADMIN_ID, TENANT)).willReturn(Optional.of(user));
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.EXECUTED, null, null, NOW))
                    .willReturn(1);
            given(mfaService.resetForRecovery(user, TENANT)).willReturn(2);

            assertThat(service.execute(request.getId())).isTrue();

            final var order = inOrder(requestRepository, mfaService, tokenRepository);
            order.verify(requestRepository).close(request.getId(), MfaRecoveryStatus.EXECUTED, null, null, NOW);
            order.verify(tokenRepository).invalidateValidTokens(ADMIN_ID, AuthToken.Type.PASSWORD_RESET, NOW);
            order.verify(mfaService).resetForRecovery(user, TENANT);
            order.verify(tokenRepository).invalidateValidTokens(ADMIN_ID, AuthToken.Type.MFA_RECOVERY_CANCEL, NOW);
            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            then(auditEventPublisher).should().publishAuthSystem(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_EXECUTED), anyString(), anyString(), details.capture());
            assertThat(details.getValue()).containsEntry(ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, "2");
            then(auditEventPublisher).should().publishAuthSystem(eq(OPERATOR_ID), eq(ReservedTenants.PLATFORM_OPERATOR),
                    eq(AuditEventTypes.MFA_RECOVERY_EXECUTED), anyString(), anyString(), anyMap());
            assertThat(counted("executed")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does nothing before the waiting period has passed since the notice, or before the notice")
        void notDue() {
            for (final Instant sent : new Instant[] {null, NOW.minus(WAIT).plusSeconds(1)}) {
                final MfaRecoveryRequest request = pending(sent);
                given(requestRepository.findById(request.getId())).willReturn(Optional.of(request));
                assertThat(service.execute(request.getId())).isFalse();
            }
            then(requestRepository).should(never()).close(any(), any(), any(), any(), any());
            then(mfaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a request that ended meanwhile is not reset (its claim updates no row)")
        void lostRace() {
            final MfaRecoveryRequest request = due();
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT)).willReturn(Optional.of(admin(true)));
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.EXECUTED, null, null, NOW))
                    .willReturn(0);

            assertThat(service.execute(request.getId())).isFalse();
            then(mfaService).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("another admin appeared: cancelled with OTHER_ADMIN_EXISTS, no reset")
        void otherAdminAppeared() {
            final MfaRecoveryRequest request = due();
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT)).willReturn(Optional.of(admin(true)));
            given(userRepository.countActiveAcceptedUsersWithRoleExcluding(TENANT, Role.ROLE_ADMIN, ADMIN_ID))
                    .willReturn(1L);
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.CANCELLED,
                    MfaRecoveryCloseReason.OTHER_ADMIN_EXISTS, null, NOW)).willReturn(1);

            assertThat(service.execute(request.getId())).isFalse();
            then(mfaService).shouldHaveNoInteractions();
            then(auditEventPublisher).should().publishAuthSystem(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_CANCELLED), anyString(), anyString(), anyMap());
            assertThat(counted("cancelled_on_recheck")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the user no longer has MFA (or is gone): cancelled with NO_LONGER_APPLICABLE")
        void noLongerApplicable() {
            final MfaRecoveryRequest request = due();
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT))
                    .willReturn(Optional.of(admin(false)), Optional.empty());
            given(requestRepository.close(request.getId(), MfaRecoveryStatus.CANCELLED,
                    MfaRecoveryCloseReason.NO_LONGER_APPLICABLE, null, NOW)).willReturn(1, 0);

            assertThat(service.execute(request.getId())).isFalse();
            assertThat(service.execute(request.getId())).isFalse();
            then(mfaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a request that is not PENDING is skipped without a query for its user")
        void notPending() {
            final MfaRecoveryRequest request = pending(NOW.minus(WAIT));
            ReflectionTestUtils.setField(request, "status", MfaRecoveryStatus.CANCELLED);
            given(requestRepository.findById(request.getId())).willReturn(Optional.of(request));
            assertThat(service.execute(request.getId())).isFalse();
            assertThat(service.execute(UUID.randomUUID())).isFalse();
            then(userRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("scheduler queries and expiry")
    class Scheduling {

        @Test
        @DisplayName("due = notice sent at least the waiting period ago")
        void dueCutoff() {
            service.findDue(20);
            then(requestRepository).should().findDue(eq(NOW.minus(WAIT)), any(Pageable.class));
        }

        @Test
        @DisplayName("undelivered = created before the notice deadline plus a margin")
        void undeliveredCutoff() {
            given(emailRequests.securityNotificationDeadline()).willReturn(Duration.ofHours(24));
            service.findUndelivered(20);
            then(requestRepository).should().findUndeliveredBefore(
                    eq(NOW.minus(Duration.ofHours(24)).minus(MfaRecoveryService.NOTICE_EXPIRY_MARGIN)),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("a request whose notice never went out expires; one whose notice went out does not")
        void expire() {
            final MfaRecoveryRequest undelivered = pending(null);
            final MfaRecoveryRequest delivered = pending(NOW.minusSeconds(60));
            given(requestRepository.findById(undelivered.getId())).willReturn(Optional.of(undelivered));
            given(requestRepository.findById(delivered.getId())).willReturn(Optional.of(delivered));
            given(requestRepository.expireIfUndelivered(undelivered.getId(),
                    MfaRecoveryCloseReason.NOTICE_NOT_DELIVERED, NOW)).willReturn(1);

            assertThat(service.expire(undelivered.getId())).isTrue();
            assertThat(service.expire(delivered.getId())).isFalse();

            then(auditEventPublisher).should().publishAuthSystem(eq(ADMIN_ID), eq(TENANT),
                    eq(AuditEventTypes.MFA_RECOVERY_EXPIRED), anyString(), anyString(), anyMap());
            then(requestRepository).should(never()).expireIfUndelivered(eq(delivered.getId()), any(), any());
            then(requestRepository).should(never()).close(any(), any(), any(), isNull(), any());
            assertThat(counted("expired")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a notice recorded between the read and the UPDATE keeps the request: nothing closed, nothing audited")
        void expireLosesToNotice() {
            final MfaRecoveryRequest undelivered = pending(null);
            given(requestRepository.findById(undelivered.getId())).willReturn(Optional.of(undelivered));
            given(requestRepository.expireIfUndelivered(undelivered.getId(),
                    MfaRecoveryCloseReason.NOTICE_NOT_DELIVERED, NOW)).willReturn(0);

            assertThat(service.expire(undelivered.getId())).isFalse();
            then(auditEventPublisher).shouldHaveNoInteractions();
            then(tokenRepository).shouldHaveNoInteractions();
        }
    }
}
