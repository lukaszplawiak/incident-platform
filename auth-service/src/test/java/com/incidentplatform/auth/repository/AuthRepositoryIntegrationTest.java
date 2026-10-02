package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.bootstrap.OperatorTenantBootstrap;
import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.SlackWorkspace;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.UserRole;
import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.ChangePasswordRequest;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.dto.CreateIntegrationRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.ProvisionTenantRequest;
import com.incidentplatform.auth.dto.ProvisionTenantResponse;
import com.incidentplatform.auth.dto.ResetPasswordRequest;
import com.incidentplatform.auth.dto.RevokedApiKeysResponse;
import com.incidentplatform.auth.dto.TenantDto;
import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.service.AesEncryptionService;
import com.incidentplatform.auth.service.ApiKeyService;
import com.incidentplatform.auth.service.AuthEmailPersistenceService;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.auth.service.IntegrationService;
import com.incidentplatform.auth.service.ForgotPasswordService;
import com.incidentplatform.auth.service.InviteService;
import com.incidentplatform.auth.service.MfaService;
import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.TenantProvisioningService;
import com.incidentplatform.auth.service.TotpService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Real-Postgres integration tests for {@code auth-service}'s repository
 * layer — backlog #34. Before this file, {@code auth-service} had zero
 * DB integration tests of any kind despite 9 repositories carrying 21
 * custom {@code @Query} JPQL methods between them; all 28 existing test
 * classes mock the repository layer entirely.
 *
 * <p>Not a theoretical risk — this exact class of bug already happened in
 * this exact service. {@link TeamMemberRepository#findManagedTeamIdsByUserIdAndTenantId}
 * referenced a non-existent {@code tm.role} field (the real one is
 * {@code teamRole}) and made {@code auth-service} fail to start entirely
 * — but only the Docker Compose smoke test caught it, not any of the 28
 * unit tests, since Spring Data JPA validates {@code @Query} JPQL against
 * the entity model at {@code EntityManagerFactory} startup, and Mockito
 * never parses real JPQL. See that method's own Javadoc for the full
 * account. That smoke test also only triggers on infra-path file changes
 * (backlog #35) — a pure Java-only JPQL typo has no reliable safety net
 * today without tests like these.
 *
 * <h2>One class, not one per repository</h2>
 * Deliberately structured as a single test class with one {@code @Nested}
 * group per repository, rather than a separate top-level class per
 * repository — each top-level {@code @SpringBootTest} class would start
 * its own, independent Postgres container from scratch. One class means
 * one container for every repository covered here.
 *
 * <h2>shared.testutils.BaseIntegrationTest removed (backlog #45)</h2>
 * That class existed but had zero actual usages anywhere in this
 * codebase — dead scaffolding, using an older manual
 * {@code @DynamicPropertySource} wiring style and unconditionally
 * including a Kafka container this service doesn't need (auth-service
 * has no Kafka producer or consumer at all). Removed as part of this
 * same change. Followed {@code OncallScheduleOverlapIntegrationTest}'s
 * style instead (oncall-service, backlog #22/#43) — the only
 * integration test in this codebase that has actually run, actually
 * caught real bugs, and uses the newer, simpler
 * {@code @ServiceConnection} auto-wiring instead of manual property
 * registration.
 *
 * <h2>Scope</h2>
 * Covers the highest-risk repositories: {@link TeamMemberRepository}
 * (the one with prior, confirmed history), {@link UserRepository}
 * (login-critical, plus its {@code @SQLRestriction} soft-delete filtering
 * and one native query that deliberately bypasses it),
 * {@link AuthTokenRepository} (invite/password-reset/refresh token
 * validation), and {@link ApiKeyRepository#findActiveByHash} (runs on
 * every API-key-authenticated request). Not exhaustive — remaining
 * repositories (MfaBackupCodeRepository, IntegrationRepository,
 * TeamRepository, AuthEmailOutboxRepository, TenantSettingsRepository)
 * are lower-risk (simpler queries, or none) and left for a follow-up if
 * ever needed.
 */

@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        // AesEncryptionService's constructor requires this — a 32-byte
        // base64-encoded key — with no default value, so the full
        // @SpringBootTest context (which instantiates every bean,
        // including MfaService -> AesEncryptionService, unlike a slice
        // test) fails to start without it. All-zero bytes: this is a
        // test-only key, never used for real encryption, only needs to
        // satisfy AesEncryptionService's 32-byte length check.
        "mfa.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        // Same reasoning as above, now for the second AesEncryptionService
        // bean (backlog #0-21's slackEncryptionService).
        "slack.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@DisplayName("auth-service repositories — real Postgres integration")
class AuthRepositoryIntegrationTest {

    // postgres:16-alpine — same image version used in docker/docker-compose.yml.
    // withReuse(true): once enabled locally (~/.testcontainers.properties,
    // testcontainers.reuse.enable=true), this container survives between
    // local test runs instead of restarting from scratch every time — pure
    // dev-loop speedup, no effect on CI (reuse is opt-in and typically off
    // there, so CI behavior is unchanged).
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withReuse(true);

    @Autowired private TeamMemberRepository teamMemberRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private MfaBackupCodeRepository mfaBackupCodeRepository;
    @Autowired private SlackWorkspaceRepository slackWorkspaceRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantProvisioningService tenantProvisioningService;
    @Autowired private com.incidentplatform.auth.service.MfaSessionStatusService mfaSessionStatusService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthEmailOutboxRepository authEmailOutboxRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private InviteService inviteService;
    @Autowired private PasswordService passwordService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserService userService;
    @Autowired private ResendInviteService resendInviteService;
    @Autowired private ForgotPasswordService forgotPasswordService;
    @Autowired private AuthEmailPersistenceService authEmailPersistenceService;
    @Autowired private MfaService mfaService;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private IntegrationService integrationService;
    @Autowired private TotpService totpService;
    @Autowired @Qualifier("mfaEncryptionService") private AesEncryptionService mfaEncryptionService;

    // The service-level tests (backlog #0-50) publish audit events; this
    // context has no Kafka, and what is published is not under test here.
    @MockitoBean private AuditEventPublisher auditEventPublisher;
    // MFA lockout state lives in Redis, which this context does not have;
    // an unconfigured mock means "not locked", and lockout is not under test.
    @MockitoBean private BruteForceProtectionService bruteForceProtectionService;
    // Backlog #0-88: Redis is not part of this test (MfaResetRateLimiterTest
    // covers the limiter against a real one); allowed unless a test says otherwise.
    @MockitoBean private com.incidentplatform.auth.ratelimit.MfaResetRateLimiter mfaResetRateLimiter;

    @org.junit.jupiter.api.BeforeEach
    void mfaResetAllowed() {
        org.mockito.BDDMockito.given(mfaResetRateLimiter.tryConsume(
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .willReturn(com.incidentplatform.auth.ratelimit.RateLimitDecision.ALLOWED);
    }

    private static final String TENANT_ID = "test-tenant";

    /** The bulk queries with {@code clearAutomatically} (see {@code bulkUpdateKeepsEarlierChange}). */
    enum BulkUpdate {
        REVOKE_PERSONAL_API_KEYS, TOUCH_API_KEY, INVALIDATE_ALL_REFRESH_TOKENS, INVALIDATE_SESSION,
        INVALIDATE_OTHER_SESSIONS, DELETE_EXPIRED_TOKENS, CLEAR_MFA_VERIFIED, DELETE_BACKUP_CODES,
        RECORD_MFA_NOTICE
    }

    private User persistUser(String email, List<String> roleNames) {
        final User user = User.forTesting(
                null, TENANT_ID, email, "hashed-password", true, roleNames);
        return userRepository.saveAndFlush(user);
    }

    private Team persistTeam(String name) {
        final Team team = Team.forTesting(null, TENANT_ID, name);
        return teamRepository.saveAndFlush(team);
    }

    private TeamMember persistTeamMember(Team team, User user, TeamRole role) {
        final TeamMember member = TeamMember.create(team, user, role);
        return teamMemberRepository.saveAndFlush(member);
    }

    /**
     * Backlog #0-21: "one active Slack workspace per tenant" is enforced by the
     * partial unique index in V17, not only by SlackWorkspaceService's 409 check —
     * the index is what holds under two concurrent installs, where both service
     * checks can pass before either insert. Only a real Postgres can prove a
     * partial index (WHERE revoked_at IS NULL) behaves as intended. Each test
     * uses its own tenant id so they can't collide through the index.
     */
    @Nested
    @DisplayName("SlackWorkspaceRepository — partial unique index (V17)")
    class SlackWorkspaceRepositoryTests {

        private SlackWorkspace install(String tenantId) {
            return SlackWorkspace.install(tenantId, null, "T0123456",
                    "iv:ciphertext", "#incidents", false);
        }

        @Test
        @DisplayName("a second active workspace for the same tenant is rejected by the database")
        void secondActiveWorkspaceRejected() {
            final String tenantId = "slack-dup-" + UUID.randomUUID();
            slackWorkspaceRepository.saveAndFlush(install(tenantId));

            // The constraint name is asserted, not just the exception type:
            // SlackWorkspaceService maps a violation to 409 only when it names
            // this index, so a Hibernate/driver change that stopped reporting it
            // would silently turn a lost install race back into a 500.
            assertThatThrownBy(() -> slackWorkspaceRepository.saveAndFlush(install(tenantId)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasCauseInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                    .cause()
                    .extracting(e -> ((org.hibernate.exception.ConstraintViolationException) e)
                            .getConstraintName())
                    .isEqualTo("uq_slack_workspaces_active_tenant");
        }

        @Test
        @DisplayName("after revoking, the tenant can install a new workspace; the revoked row stays for audit")
        void reinstallAfterRevokeAllowed() {
            final String tenantId = "slack-reinstall-" + UUID.randomUUID();
            final SlackWorkspace first = slackWorkspaceRepository.saveAndFlush(install(tenantId));
            first.revoke();
            slackWorkspaceRepository.saveAndFlush(first);

            final SlackWorkspace second = slackWorkspaceRepository.saveAndFlush(install(tenantId));

            assertThat(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(tenantId))
                    .map(SlackWorkspace::getId).contains(second.getId());
            assertThat(slackWorkspaceRepository.findByIdAndTenantId(first.getId(), tenantId))
                    .hasValueSatisfying(ws -> assertThat(ws.isRevoked()).isTrue());
        }

        @Test
        @DisplayName("active workspaces of different tenants don't collide")
        void differentTenantsIndependent() {
            slackWorkspaceRepository.saveAndFlush(install("slack-a-" + UUID.randomUUID()));
            slackWorkspaceRepository.saveAndFlush(install("slack-b-" + UUID.randomUUID()));
        }

        @Test
        @DisplayName("findByIdAndTenantId does not return another tenant's workspace")
        void lookupIsTenantScoped() {
            final SlackWorkspace ws = slackWorkspaceRepository.saveAndFlush(
                    install("slack-owner-" + UUID.randomUUID()));

            assertThat(slackWorkspaceRepository.findByIdAndTenantId(ws.getId(), "someone-else"))
                    .isEmpty();
        }
    }

    /**
     * Backlog #0-47: {@code User} and {@code SlackWorkspace} initialised their
     * {@code @Version} field to {@code 0L}, so Spring Data's
     * {@code isNew()} (which, for a non-primitive version, means "version is
     * null") treated a brand-new entity as existing: {@code save()} ran
     * {@code merge()} and returned a managed <em>copy</em>, leaving the
     * caller's instance transient. {@code UserService.createUser} ignores the
     * return value of {@code save()}, so the {@code AuthToken} it then saved
     * referenced a transient {@code User} and the flush failed with
     * {@code TransientPropertyValueException}. The existing tests never saw
     * it because {@link #persistUser} uses the return value of
     * {@code saveAndFlush}. These tests deliberately use the instance they
     * passed in, the way production code does.
     */
    @Nested
    @DisplayName("New-entity detection (backlog #0-47)")
    class NewEntityDetection {

        @Test
        @DisplayName("save() persists a new User in place, so an AuthToken can reference the same instance")
        void newUserIsPersistedNotMerged() {
            final User user = User.register(TENANT_ID, "new-invitee@example.com");
            user.getRoles().add(UserRole.grant(user, TENANT_ID, "ROLE_RESPONDER"));

            userRepository.save(user);
            authTokenRepository.saveAndFlush(AuthToken.create(
                    user, TENANT_ID, "hash-new-user", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600)));

            assertThat(entityManager.contains(user)).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT version FROM users WHERE id = ?", Long.class, user.getId()))
                    .isZero();
        }

        @Test
        @DisplayName("save() persists a new SlackWorkspace in place and starts its version at 0")
        void newSlackWorkspaceIsPersistedNotMerged() {
            final SlackWorkspace workspace = SlackWorkspace.install(
                    "slack-new-" + UUID.randomUUID(), null, "T0123456",
                    "iv:ciphertext", "#incidents", false);

            slackWorkspaceRepository.save(workspace);
            entityManager.flush();

            assertThat(entityManager.contains(workspace)).isTrue();
            assertThat(workspace.getVersion()).isZero();
        }
    }

    /**
     * Backlog #0-50: {@code AuthTokenService.consumeToken} claims the token
     * with a bulk UPDATE, and every caller then works with
     * {@code token.getUser()}. With {@code clearAutomatically = true} on that
     * UPDATE the persistence context was cleared, the {@code User} proxy was
     * detached, and the first real field access threw
     * {@code LazyInitializationException} — accept-invite, reset-password
     * and refresh rotation all failed on a real database, while the service
     * unit tests (repositories mocked) passed. MFA goes through the same
     * {@code consumeToken} path; its test needed backlog #0-51 first, since
     * MFA tokens could not be stored at all.
     *
     * <p>Each test flushes and clears after its setup, so the service loads
     * the token (and its lazy {@code User}) from the database exactly as a
     * fresh request does. Without that, the setup's managed {@code User}
     * would be returned instead of a proxy and hide the bug.
     */
    @Nested
    @DisplayName("Token consumption through the services (backlog #0-50)")
    class TokenConsumptionThroughServices {

        private String passwordHashOf(UUID userId) {
            return jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        }

        private void startFreshRequest() {
            entityManager.flush();
            entityManager.clear();
        }

        @Test
        @DisplayName("accept-invite sets the invited user's password")
        void acceptInviteSetsPassword() {
            final User user = User.register(TENANT_ID, "accepts@example.com");
            user.getRoles().add(UserRole.grant(user, TENANT_ID, "ROLE_RESPONDER"));
            userRepository.save(user);
            final String rawToken = authTokenService.generateInviteToken(user, TENANT_ID);
            startFreshRequest();

            inviteService.acceptInvite(
                    new AcceptInviteRequest(rawToken, "a-long-enough-password"));
            entityManager.flush();

            assertThat(passwordEncoder.matches(
                    "a-long-enough-password", passwordHashOf(user.getId()))).isTrue();
        }

        @Test
        @DisplayName("reset-password stores the new password and ends every session")
        void resetPasswordPersistsNewPasswordAndRevokesSessions() {
            final User user = persistUser("resets@example.com", List.of("ROLE_RESPONDER"));
            final String rawRefresh = authTokenService.generateRefreshToken(
                    user, TENANT_ID, UUID.randomUUID(), null);
            final String rawReset = authTokenService.generatePasswordResetToken(user, TENANT_ID);
            startFreshRequest();

            passwordService.resetPassword(
                    new ResetPasswordRequest(rawReset, "a-new-password"), TENANT_ID);
            entityManager.flush();

            assertThat(passwordEncoder.matches(
                    "a-new-password", passwordHashOf(user.getId()))).isTrue();
            assertThatThrownBy(() -> authTokenService.rotateRefreshToken(rawRefresh))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("refresh rotation issues a new token pair and consumes the old refresh token")
        void refreshRotationWorks() {
            final User user = persistUser("refreshes@example.com", List.of("ROLE_RESPONDER"));
            final String rawRefresh = authTokenService.generateRefreshToken(
                    user, TENANT_ID, UUID.randomUUID(), null);
            startFreshRequest();

            final AuthTokenService.RotationResult result =
                    authTokenService.rotateRefreshToken(rawRefresh);
            entityManager.flush();

            assertThat(result.accessToken()).isNotBlank();
            assertThat(result.rawRefreshToken()).isNotEqualTo(rawRefresh);
            assertThatThrownBy(() -> authTokenService.rotateRefreshToken(rawRefresh))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("MFA verification with a TOTP code logs the user in and records the time step (backlog #0-51)")
        void mfaVerificationWorks() throws Exception {
            final User user = persistUser("mfa@example.com", List.of("ROLE_RESPONDER"));
            final String secret = totpService.generateSecret();
            user.storePendingMfaSecret(mfaEncryptionService.encrypt(secret));
            user.enableMfa();
            userRepository.save(user);
            final String rawMfaToken = authTokenService.generateMfaSessionToken(user, TENANT_ID);
            startFreshRequest();

            final LoginResponse response =
                    mfaService.verifyMfaToken(rawMfaToken, currentTotpCode(secret));
            entityManager.flush();

            assertThat(response.accessToken()).isNotBlank();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT mfa_last_used_time_step FROM users WHERE id = ?",
                    Long.class, user.getId())).isNotNull();
            assertThatThrownBy(() -> authTokenService.consumeToken(
                    rawMfaToken, AuthToken.Type.MFA_SESSION))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("disabling MFA persists, ends its sessions' MFA, and queues the notification (backlog #0-83)")
        void disableMfaEndToEnd() throws Exception {
            final User user = persistUser("mfa-disable@example.com", List.of("ROLE_ADMIN"));
            user.setPasswordHash(passwordEncoder.encode("a-password"));
            final String secret = totpService.generateSecret();
            user.storePendingMfaSecret(mfaEncryptionService.encrypt(secret));
            user.enableMfa();
            userRepository.saveAndFlush(user);
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            startFreshRequest();

            TenantContext.set(TENANT_ID);
            try {
                mfaService.disableMfa("a-password", currentTotpCode(secret),
                        new com.incidentplatform.shared.security.UserPrincipal(user.getId(), TENANT_ID,
                                user.getEmail(), List.of("ROLE_ADMIN"), List.of()));
            } finally {
                TenantContext.clear();
            }
            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT mfa_enabled FROM users WHERE id = ?", Boolean.class, user.getId()))
                    .as("the user change survives the session clear").isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM auth_tokens WHERE user_id = ? AND mfa_verified_at IS NOT NULL",
                    Integer.class, user.getId())).isZero();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT email_type FROM auth_email_outbox WHERE user_id = ?", String.class, user.getId()))
                    .contains("MFA_DISABLED");
        }

        @Test
        @DisplayName("invalidateLoginContinuationTokens ends the user's MFA session and setup tokens, nothing else (backlog #0-83)")
        void loginContinuationTokensOnly() {
            final User user = persistUser("continuation@example.com", List.of("ROLE_ADMIN"));
            final User other = persistUser("continuation-other@example.com", List.of("ROLE_ADMIN"));
            final String mfaSession = authTokenService.generateMfaSessionToken(user, TENANT_ID);
            final String setupRequired = authTokenService.generateMfaSetupRequiredToken(user, TENANT_ID);
            final String reset = authTokenService.generatePasswordResetToken(user, TENANT_ID);
            final String othersMfaSession = authTokenService.generateMfaSessionToken(other, TENANT_ID);
            startFreshRequest();

            authTokenService.invalidateLoginContinuationTokens(user.getId());
            startFreshRequest();

            assertThatThrownBy(() -> authTokenService.consumeToken(mfaSession, AuthToken.Type.MFA_SESSION))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> authTokenService.consumeToken(setupRequired, AuthToken.Type.MFA_SETUP_REQUIRED))
                    .isInstanceOf(BusinessException.class);
            assertThat(authTokenService.consumeToken(reset, AuthToken.Type.PASSWORD_RESET).getUser().getId())
                    .as("another type of the same user").isEqualTo(user.getId());
            assertThat(authTokenService.consumeToken(othersMfaSession, AuthToken.Type.MFA_SESSION).getUser().getId())
                    .as("the same type of another user").isEqualTo(other.getId());
        }

        @Test
        @DisplayName("a password reset keeps even a factor enabled minutes ago, and ends unfinished logins (backlog #0-88)")
        void passwordResetKeepsFactor() {
            final User fresh = persistUser("reset-fresh@example.com", List.of("ROLE_ADMIN"));
            fresh.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            fresh.enableMfa();
            userRepository.saveAndFlush(fresh);
            final String reset = authTokenService.generatePasswordResetToken(fresh, TENANT_ID);
            final String setupToken = authTokenService.generateMfaSetupRequiredToken(fresh, TENANT_ID);
            final String mfaSessionToken = authTokenService.generateMfaSessionToken(fresh, TENANT_ID);
            startFreshRequest();

            passwordService.resetPassword(new ResetPasswordRequest(reset, "a-new-password"), TENANT_ID);
            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                    Boolean.class, fresh.getId())).as("a mailbox alone does not undo MFA").isTrue();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT email_type FROM auth_email_outbox WHERE user_id = ?", String.class, fresh.getId()))
                    .doesNotContain("MFA_DISABLED");
            assertThat(passwordEncoder.matches("a-new-password", passwordHashOf(fresh.getId()))).isTrue();
            // A half-finished login from before the reset is dead (backlog #0-83).
            assertThatThrownBy(() -> authTokenService.consumeToken(setupToken, AuthToken.Type.MFA_SETUP_REQUIRED))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> authTokenService.consumeToken(mfaSessionToken, AuthToken.Type.MFA_SESSION))
                    .isInstanceOf(BusinessException.class);
        }

        /** A live key, saved as-is (no email); the hash only has to be unique. */
        private UUID apiKey(User owner) {
            final String hash = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            final ApiKey key = owner == null
                    ? ApiKey.createTenant(
                            TENANT_ID, "tenant key", hash, hash.substring(0, 8), List.of("teams:read"), null)
                    : ApiKey.createPersonal(
                            TENANT_ID, "personal key", hash, hash.substring(0, 8), List.of("teams:read"), null, owner);
            return apiKeyRepository.saveAndFlush(key).getId();
        }

        private boolean revoked(UUID keyId) {
            return jdbcTemplate.queryForObject(
                    "SELECT revoked_at IS NOT NULL FROM api_keys WHERE id = ?", Boolean.class, keyId);
        }

        @Test
        @DisplayName("a password reset revokes the user's personal API keys, no one else's and no tenant key (backlog #0-89)")
        void passwordResetRevokesPersonalKeys() {
            final User user = persistUser("reset-keys@example.com", List.of("ROLE_ADMIN"));
            final User other = persistUser("reset-keys-other@example.com", List.of("ROLE_ADMIN"));
            final UUID first = apiKey(user);
            final UUID second = apiKey(user);
            final UUID othersKey = apiKey(other);
            final UUID tenantKey = apiKey(null);
            final String reset = authTokenService.generatePasswordResetToken(user, TENANT_ID);
            startFreshRequest();

            passwordService.resetPassword(new ResetPasswordRequest(reset, "a-new-password"), TENANT_ID);
            entityManager.flush();

            assertThat(revoked(first)).isTrue();
            assertThat(revoked(second)).isTrue();
            assertThat(revoked(othersKey)).as("another user's key").isFalse();
            assertThat(revoked(tenantKey)).as("a tenant key").isFalse();
            assertThat(passwordEncoder.matches("a-new-password", passwordHashOf(user.getId())))
                    .as("the password change survives the bulk revocation after it").isTrue();
        }

        @Test
        @DisplayName("a password change revokes the personal API keys only when asked (backlog #0-89)")
        void passwordChangeRevokesOnRequest() {
            final User user = persistUser("change-keys@example.com", List.of("ROLE_RESPONDER"));
            user.setPasswordHash(passwordEncoder.encode("the-current-password"));
            userRepository.saveAndFlush(user);
            final UUID key = apiKey(user);
            final UserPrincipal principal = new UserPrincipal(user.getId(), TENANT_ID, user.getEmail(),
                    List.of("ROLE_RESPONDER"), List.of(), List.of(), UUID.randomUUID());
            startFreshRequest();

            passwordService.changePassword(principal, new ChangePasswordRequest(
                    "the-current-password", "a-second-password"));
            startFreshRequest();
            assertThat(revoked(key)).as("a routine change keeps the key").isFalse();

            passwordService.changePassword(principal, new ChangePasswordRequest(
                    "a-second-password", "a-third-password", true));
            entityManager.flush();
            assertThat(revoked(key)).isTrue();
            assertThat(passwordEncoder.matches("a-third-password", passwordHashOf(user.getId()))).isTrue();
        }

        @Test
        @DisplayName("V26: keys record their creator and session; an admin revokes what one user created since a "
                + "time, an integration with its key, nobody else's (backlog #0-89)")
        void revokeKeysCreatedBy() {
            final User intruded = persistUser("intruded-admin@example.com", List.of("ROLE_ADMIN"));
            final User other = persistUser("other-admin@example.com", List.of("ROLE_ADMIN"));
            final UUID session = UUID.randomUUID();
            final UserPrincipal asIntruded = new UserPrincipal(intruded.getId(), TENANT_ID, intruded.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), session);
            final UserPrincipal asOther = new UserPrincipal(other.getId(), TENANT_ID, other.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            startFreshRequest();
            final UUID oldKey;
            final UUID tenantKey;
            final UUID integrationId;
            final UUID othersKey;
            TenantContext.set(TENANT_ID);
            try {
                final var shared = new CreateApiKeyRequest("shared",
                        ApiKeyType.TENANT,
                        List.of(ApiKeyScope.TEAMS_READ), null);
                oldKey = apiKeyService.createApiKey(shared, asIntruded).id();
                tenantKey = apiKeyService.createApiKey(shared, asIntruded).id();
                integrationId = integrationService.createIntegration(
                        new CreateIntegrationRequest(
                                "intruder-int", "generic", null, null), asIntruded).id();
                othersKey = apiKeyService.createApiKey(shared, asOther).id();
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT created_by_user_id, created_in_session_id FROM api_keys WHERE id = ?", tenantKey))
                    .containsEntry("created_by_user_id", intruded.getId())
                    .containsEntry("created_in_session_id", session);
            assertThat(apiKeyRepository.countActiveUnownedCreatedBy(TENANT_ID, intruded.getId())).isEqualTo(3);
            // Another tenant's key carrying the same creator id (review: proves the tenant predicate).
            final ApiKey foreign = ApiKey.createTenant(
                    "other-tenant", "foreign", "f".repeat(64), "ffffffff", List.of("teams:read"), null);
            foreign.recordCreator(intruded.getId(), null);
            final UUID foreignKey = apiKeyRepository.saveAndFlush(foreign).getId();
            assertThat(apiKeyRepository.countActiveUnownedCreatedBy(TENANT_ID, intruded.getId()))
                    .as("counted in its own tenant only").isEqualTo(3);
            final Instant since = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
            // Created before the compromise: outside the window.
            jdbcTemplate.update("UPDATE api_keys SET created_at = now() - INTERVAL '10 days' WHERE id = ?", oldKey);
            // Created exactly at the given time: inside (>=), review asked to pin the boundary.
            jdbcTemplate.update("UPDATE api_keys SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(since), tenantKey);
            startFreshRequest();

            TenantContext.set(TENANT_ID);
            final RevokedApiKeysResponse revoked;
            try {
                assertThat(apiKeyService.listApiKeys(asOther, intruded.getId()))
                        .as("the list filtered by creator, through the real query")
                        .extracting(ApiKeyDto::id)
                        .containsExactlyInAnyOrder(oldKey, tenantKey,
                                jdbcTemplate.queryForObject("SELECT api_key_id FROM integrations WHERE id = ?",
                                        UUID.class, integrationId));
                revoked = apiKeyService.revokeKeysCreatedBy(intruded.getId(), since, asOther);
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }

            assertThat(revoked.count()).isEqualTo(2);
            assertThat(revoked(foreignKey)).as("another tenant's key, same creator id").isFalse();
            assertThat(revoked.revokedIntegrationIds()).containsExactly(integrationId);
            assertThat(revoked(tenantKey)).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT revoked_at IS NOT NULL FROM integrations WHERE id = ?", Boolean.class, integrationId))
                    .as("the integration goes with its key").isTrue();
            assertThat(revoked(oldKey)).as("created before the given time").isFalse();
            assertThat(revoked(othersKey)).as("another admin's key").isFalse();
            assertThat(apiKeyRepository.countActiveUnownedCreatedBy(TENANT_ID, intruded.getId())).isEqualTo(1);
        }

        /** A committed admin of its own tenant, for the tests that commit (deleted by {@link #deleteKeyCreator}). */
        private User committedKeyCreator(String tenant, String email) {
            return userRepository.saveAndFlush(User.forTesting(null, tenant, email, "hashed-password", true,
                    List.of("ROLE_ADMIN")));
        }

        private void deleteKeyCreator(User creator) {
            jdbcTemplate.update("DELETE FROM auth_email_outbox WHERE user_id = ?", creator.getId());
            jdbcTemplate.update("DELETE FROM integrations WHERE api_key_id IN "
                    + "(SELECT id FROM api_keys WHERE created_by_user_id = ?)", creator.getId());
            jdbcTemplate.update("DELETE FROM api_keys WHERE created_by_user_id = ?", creator.getId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", creator.getId());
        }

        private static CreateApiKeyRequest tenantKeyRequest(String name) {
            return new CreateApiKeyRequest(name, ApiKeyType.TENANT, List.of(ApiKeyScope.TEAMS_READ), null);
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("the creation audit is published after commit, off the request thread: a stalled publish "
                + "holds neither the request nor a pooled connection (backlog #0-89, review)")
        void creationAuditOffRequestThread() throws Exception {
            final String tenant = "audit-tenant";
            final User admin = committedKeyCreator(tenant, "audit-admin@example.com");
            final UserPrincipal asAdmin = new UserPrincipal(admin.getId(), tenant, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            final CountDownLatch publishing = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            Mockito.doAnswer(invocation -> {
                publishing.countDown();
                release.await(30, TimeUnit.SECONDS); // a Kafka stall
                return null;
            }).when(auditEventPublisher).publishAuth(ArgumentMatchers.any(),
                    ArgumentMatchers.eq(tenant),
                    ArgumentMatchers.eq(AuditEventTypes.API_KEY_CREATED),
                    ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any());
            final var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
            TenantContext.set(tenant);
            try {
                final long start = System.nanoTime();
                apiKeyService.createApiKey(tenantKeyRequest("audited"), asAdmin);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).as("the request does not wait for Kafka")
                        .isLessThan(Duration.ofSeconds(5));

                assertThat(publishing.await(10, TimeUnit.SECONDS)).as("published after commit").isTrue();
                assertThat(pool.getActiveConnections()).as("no connection held while the publish stalls").isZero();
            } finally {
                release.countDown();
                TenantContext.clear();
                deleteKeyCreator(admin);
            }
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("a creation rolled back publishes no audit event (backlog #0-89, review)")
        void rolledBackCreationPublishesNothing() {
            final String tenant = "rollback-tenant";
            final User admin = committedKeyCreator(tenant, "rollback-admin@example.com");
            final UserPrincipal asAdmin = new UserPrincipal(admin.getId(), tenant, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            TenantContext.set(tenant);
            try {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            apiKeyService.createApiKey(tenantKeyRequest("rolled back"), asAdmin);
                            status.setRollbackOnly();
                        });

                Mockito.verify(auditEventPublisher, Mockito.after(1000).never())
                        .publishAuth(ArgumentMatchers.any(), ArgumentMatchers.eq(tenant),
                                ArgumentMatchers.eq(
                                        AuditEventTypes.API_KEY_CREATED),
                                ArgumentMatchers.any(), ArgumentMatchers.any(),
                                ArgumentMatchers.any(), ArgumentMatchers.any());
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys WHERE created_by_user_id = ?",
                        Integer.class, admin.getId())).isZero();
            } finally {
                TenantContext.clear();
                deleteKeyCreator(admin);
            }
        }

        @Test
        @DisplayName("the audit pool is no Executor bean, so Spring Boot keeps its applicationTaskExecutor "
                + "(backlog #0-89, review)")
        void bootTaskExecutorKept() {
            assertThat(applicationContext.containsBean("applicationTaskExecutor")).isTrue();
            assertThat(applicationContext.getBeansOfType(Executor.class).keySet())
                    .noneMatch(name -> name.toLowerCase().contains("audit"));
        }

        @Test
        @DisplayName("the locking lookup finds only an active user of the given tenant, like the entity's "
                + "@SQLRestriction it repeats natively (backlog #0-89, review)")
        void lockingLookupFilters() {
            final User active = persistUser("lock-filter@example.com", List.of("ROLE_ADMIN"));
            final User archived = persistUser("lock-archived@example.com", List.of("ROLE_ADMIN"));
            final User anonymized = persistUser("lock-anonymized@example.com", List.of("ROLE_ADMIN"));
            jdbcTemplate.update("UPDATE users SET archived_at = now() WHERE id = ?", archived.getId());
            jdbcTemplate.update("UPDATE users SET anonymized_at = now() WHERE id = ?", anonymized.getId());
            startFreshRequest();

            assertThat(userRepository.findByIdAndTenantIdForUpdate(active.getId(), TENANT_ID)).isPresent();
            assertThat(userRepository.findByIdAndTenantIdForUpdate(active.getId(), "other-tenant"))
                    .as("another tenant").isEmpty();
            assertThat(userRepository.findByIdAndTenantIdForUpdate(archived.getId(), TENANT_ID))
                    .as("archived").isEmpty();
            assertThat(userRepository.findByIdAndTenantIdForUpdate(anonymized.getId(), TENANT_ID))
                    .as("anonymized").isEmpty();
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("a row referencing the creator in a transaction still open (a reset's outbox row) does not make "
                + "a key creation a 429: FOR NO KEY UPDATE, not FOR UPDATE (backlog #0-89, review)")
        void foreignKeyInsertDoesNotBlockCreation() throws Exception {
            final String tenant = "fk-tenant";
            final User admin = committedKeyCreator(tenant, "fk-admin@example.com");
            final UserPrincipal asAdmin = new UserPrincipal(admin.getId(), tenant, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            final CountDownLatch inserted = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                final var holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            // The insert takes FOR KEY SHARE on the user's row until this transaction ends.
                            authEmailOutboxRepository.saveAndFlush(AuthEmailOutbox.request(
                                    admin, AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15)));
                            inserted.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            status.setRollbackOnly();
                        }));
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

                TenantContext.set(tenant);
                try {
                    assertThat(apiKeyService.createApiKey(tenantKeyRequest("alongside a reset"), asAdmin).id())
                            .isNotNull();
                } finally {
                    TenantContext.clear();
                }
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                pool.shutdownNow();
                deleteKeyCreator(admin);
            }
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("while another transaction holds the creator's row, a key creation is refused at once (NOWAIT, "
                + "429 after one second) instead of waiting on a pooled connection (backlog #0-89, review)")
        void keyCreationRefusedWhileCreatorLocked() throws Exception {
            final String tenant = "lock-tenant";
            final User admin = userRepository.saveAndFlush(User.forTesting(null, tenant,
                    "lock-admin@example.com", "hashed-password", true, List.of("ROLE_ADMIN")));
            final UserPrincipal asAdmin = new UserPrincipal(admin.getId(), tenant, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            final var request = new CreateApiKeyRequest("busy",
                    ApiKeyType.TENANT,
                    List.of(ApiKeyScope.TEAMS_READ), null);
            final var txTemplate = new TransactionTemplate(transactionManager);
            final CountDownLatch locked = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                final var holder = pool.submit(() -> txTemplate.executeWithoutResult(status -> {
                    userRepository.findByIdAndTenantIdForUpdate(admin.getId(), tenant).orElseThrow();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                final Callable<ApiKeyCreatedResponse> create = () -> {
                    TenantContext.set(tenant);
                    try {
                        return apiKeyService.createApiKey(request, asAdmin);
                    } finally {
                        TenantContext.clear();
                    }
                };
                // A waiting call would not return before the holder lets go (30 s); NOWAIT returns at once.
                final var busy = pool.submit(create);
                assertThatThrownBy(() -> busy.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RateLimitRefusedException.class)
                        .cause().satisfies(e -> assertThat(((RateLimitRefusedException) e)
                                .decision().retryAfterSeconds()).isEqualTo(1));
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys WHERE created_by_user_id = ?",
                        Integer.class, admin.getId())).as("nothing created while refused").isZero();

                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertThat(pool.submit(create).get(10, TimeUnit.SECONDS).id())
                        .as("once the row is free, the creation goes through").isNotNull();
            } finally {
                release.countDown();
                pool.shutdownNow();
                jdbcTemplate.update("DELETE FROM auth_email_outbox WHERE user_id = ?", admin.getId());
                jdbcTemplate.update("DELETE FROM api_keys WHERE created_by_user_id = ?", admin.getId());
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", admin.getId());
            }
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("with two creations left in the hour, thirty parallel ones never go over the limit, the rest "
                + "are 429s (busy row or limit), and one at a time it ends at exactly twenty (backlog #0-89, review)")
        void creationLimitHoldsUnderConcurrency() throws Exception {
            // A smoke test of the whole path under load; the lock itself is pinned
            // deterministically by keyCreationRefusedWhileCreatorLocked (review).
            final String tenant = "race-tenant";
            final User admin = userRepository.saveAndFlush(User.forTesting(null, tenant,
                    "race-admin@example.com", "hashed-password", true, List.of("ROLE_ADMIN")));
            final UserPrincipal asAdmin = new UserPrincipal(admin.getId(), tenant, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            final var request = new CreateApiKeyRequest("racer",
                    ApiKeyType.TENANT,
                    List.of(ApiKeyScope.TEAMS_READ), null);
            // Eighteen made in the last hour already: two left, so any overshoot shows.
            for (int i = 0; i < 18; i++) {
                final String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
                final ApiKey earlier = ApiKey.createTenant(tenant, "earlier", hash, hash.substring(0, 8),
                        List.of("teams:read"), null);
                earlier.recordCreator(admin.getId(), null);
                apiKeyRepository.saveAndFlush(earlier);
            }
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger created = new AtomicInteger();
            final AtomicInteger limited = new AtomicInteger();
            final List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
            final ExecutorService pool = Executors.newFixedThreadPool(30);
            try {
                for (int i = 0; i < 30; i++) {
                    pool.submit(() -> {
                        TenantContext.set(tenant);
                        try {
                            start.await();
                            apiKeyService.createApiKey(request, asAdmin);
                            created.incrementAndGet();
                        } catch (RateLimitRefusedException refused) {
                            limited.incrementAndGet();
                        } catch (Throwable other) {
                            unexpected.add(other);
                        } finally {
                            TenantContext.clear();
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

                assertThat(unexpected).isEmpty();
                assertThat(created.get() + limited.get()).isEqualTo(30);
                assertThat(created.get()).as("keys created in parallel, never over the two left")
                        .isPositive().isLessThanOrEqualTo(2);

                // One at a time from here: exactly up to the limit, then the hourly limit refuses.
                TenantContext.set(tenant);
                try {
                    while (true) {
                        try {
                            apiKeyService.createApiKey(request, asAdmin);
                        } catch (RateLimitRefusedException refused) {
                            assertThat(refused.decision().retryAfterSeconds()).as("the hourly limit").isGreaterThan(1);
                            break;
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys WHERE created_by_user_id = ?",
                        Integer.class, admin.getId())).as("keys in total").isEqualTo(20);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM auth_email_outbox WHERE user_id = ? AND email_type = 'API_KEY_CREATED'",
                        Integer.class, admin.getId()))
                        .as("one email per key created through the service (the eighteen were inserted)")
                        .isEqualTo(2);
            } finally {
                pool.shutdownNow();
                jdbcTemplate.update("DELETE FROM auth_email_outbox WHERE user_id = ?", admin.getId());
                jdbcTemplate.update("DELETE FROM api_keys WHERE created_by_user_id = ?", admin.getId());
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", admin.getId());
            }
        }

        @Test
        @DisplayName("the hourly creation limit counts revoked keys too, so a create-and-revoke loop stops "
                + "(V26 index, backlog #0-89)")
        void creationLimitCountsRevokedKeys() {
            final User looper = persistUser("looper@example.com", List.of("ROLE_RESPONDER"));
            for (int i = 0; i < 20; i++) {
                final UUID key = apiKey(looper);
                jdbcTemplate.update("UPDATE api_keys SET revoked_at = now() WHERE id = ?", key);
            }
            // One older than the hour does not count.
            jdbcTemplate.update("UPDATE api_keys SET created_at = now() - INTERVAL '2 hours' "
                    + "WHERE id = (SELECT id FROM api_keys WHERE created_by_user_id = ? LIMIT 1)", looper.getId());
            final UserPrincipal asLooper = new UserPrincipal(looper.getId(), TENANT_ID, looper.getEmail(),
                    List.of("ROLE_RESPONDER"), List.of(), List.of(), UUID.randomUUID());
            final var request = new CreateApiKeyRequest("one more",
                    ApiKeyType.PERSONAL,
                    List.of(ApiKeyScope.TEAMS_READ), null);
            startFreshRequest();

            TenantContext.set(TENANT_ID);
            try {
                apiKeyService.createApiKey(request, asLooper);
                entityManager.flush();
                assertThatThrownBy(() -> apiKeyService.createApiKey(request, asLooper))
                        .as("twenty in the last hour, all but one of them revoked")
                        .isInstanceOf(RateLimitRefusedException.class);
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("creating a key queues the email to its owner, a tenant key's to the creating admin, one per "
                + "key naming it (V25, backlog #0-89)")
        void keyCreationQueuesEmail() {
            final User admin = persistUser("key-creator@example.com", List.of("ROLE_ADMIN"));
            final UserPrincipal principal = new UserPrincipal(admin.getId(), TENANT_ID, admin.getEmail(),
                    List.of("ROLE_ADMIN"), List.of());
            startFreshRequest();
            TenantContext.set(TENANT_ID);
            try {
                apiKeyService.createApiKey(new CreateApiKeyRequest("mine",
                        ApiKeyType.PERSONAL,
                        List.of(ApiKeyScope.TEAMS_READ), null), principal);
                apiKeyService.createApiKey(new CreateApiKeyRequest("shared",
                        ApiKeyType.TENANT,
                        List.of(ApiKeyScope.TEAMS_READ), null), principal);
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }

            assertThat(jdbcTemplate.queryForList(
                    "SELECT email_type FROM auth_email_outbox WHERE user_id = ? AND email = ?",
                    String.class, admin.getId(), admin.getEmail()))
                    .as("one notice per key, never merged (review: a merged one let a key hide)")
                    .containsExactly("API_KEY_CREATED", "API_KEY_CREATED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(DISTINCT o.api_key_id) FROM auth_email_outbox o JOIN api_keys k ON k.id = o.api_key_id "
                            + "WHERE o.user_id = ? AND k.created_by_user_id = ?",
                    Integer.class, admin.getId(), admin.getId()))
                    .as("each row names its own key (V25)").isEqualTo(2);
        }

        @Test
        @DisplayName("an admin's MFA reset removes factor and codes, ends every session, queues the email; "
                + "needs the admin's MFA session; another tenant's user is not found (backlog #0-88)")
        void adminMfaReset() {
            final User admin = persistUser("reset-admin@example.com", List.of("ROLE_ADMIN"));
            admin.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            admin.enableMfa();
            userRepository.saveAndFlush(admin);
            final UUID adminMfaSession = UUID.randomUUID();
            final UUID adminPasswordSession = UUID.randomUUID();
            authTokenService.generateRefreshToken(admin, TENANT_ID, adminMfaSession, Instant.now());
            authTokenService.generateRefreshToken(admin, TENANT_ID, adminPasswordSession, null);

            final User target = persistUser("reset-target@example.com", List.of("ROLE_RESPONDER"));
            target.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            target.enableMfa();
            userRepository.saveAndFlush(target);
            mfaBackupCodeRepository.saveAndFlush(MfaBackupCode.create(target, "backup-code-hash"));
            final String targetRefresh = authTokenService.generateRefreshToken(
                    target, TENANT_ID, UUID.randomUUID(), Instant.now());
            final String targetMfaLogin = authTokenService.generateMfaSessionToken(target, TENANT_ID);
            final UUID targetKey = apiKey(target);

            final User stranger = User.forTesting(null, "other-tenant", "reset-stranger@example.com",
                    "hashed-password", true, List.of("ROLE_RESPONDER"));
            stranger.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            stranger.enableMfa();
            userRepository.saveAndFlush(stranger);
            startFreshRequest();

            final java.util.function.Function<UUID, UserPrincipal> adminIn = session -> new UserPrincipal(
                    admin.getId(), TENANT_ID, admin.getEmail(), List.of("ROLE_ADMIN"), List.of(), List.of(), session);
            TenantContext.set(TENANT_ID);
            try {
                assertThatThrownBy(() -> mfaService.resetMfaByAdmin(target.getId(), adminIn.apply(adminPasswordSession)))
                        .as("password-only session of the admin")
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
                assertThatThrownBy(() -> mfaService.resetMfaByAdmin(target.getId(), adminIn.apply(adminMfaSession)))
                        .as("MFA session, but the admin's factor was just enrolled (review of #0-88)")
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
                // The admin's factor established: its notice went out more than the grace period ago.
                jdbcTemplate.update("UPDATE users SET mfa_enabled_at = now() - INTERVAL '26 hours', "
                        + "mfa_enabled_notice_sent_at = now() - INTERVAL '25 hours' WHERE id = ?", admin.getId());
                startFreshRequest();
                assertThatThrownBy(() -> mfaService.resetMfaByAdmin(stranger.getId(), adminIn.apply(adminMfaSession)))
                        .as("a user of another tenant")
                        .isInstanceOf(ResourceNotFoundException.class);
                startFreshRequest();

                mfaService.resetMfaByAdmin(target.getId(), adminIn.apply(adminMfaSession));
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }

            assertThat(jdbcTemplate.queryForMap(
                    "SELECT mfa_enabled, mfa_secret, mfa_pending_secret FROM users WHERE id = ?", target.getId()))
                    .containsEntry("mfa_enabled", false)
                    .containsEntry("mfa_secret", null)
                    .containsEntry("mfa_pending_secret", null);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?", Integer.class, target.getId())).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM auth_tokens WHERE user_id = ? AND mfa_verified_at IS NOT NULL",
                    Integer.class, target.getId())).isZero();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT email_type FROM auth_email_outbox WHERE user_id = ?", String.class, target.getId()))
                    .as("the outbox row survives the bulk updates after it").containsExactly("MFA_RESET");
            assertThat(revoked(targetKey)).as("personal API key (backlog #0-89)").isTrue();
            assertThatThrownBy(() -> authTokenService.rotateRefreshToken(targetRefresh))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> authTokenService.consumeToken(targetMfaLogin, AuthToken.Type.MFA_SESSION))
                    .isInstanceOf(BusinessException.class);

            assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                    Boolean.class, stranger.getId())).as("another tenant's user untouched").isTrue();
            assertThat(mfaSessionStatusService.check(admin.getId(), TENANT_ID, adminMfaSession))
                    .as("the admin's own session untouched").isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
        }

        @Test
        @DisplayName("an admin MFA reset with revokeKeysCreatedSince also revokes the tenant and integration keys "
                + "the user created, after the bulk revocation's clear (backlog #0-89, review)")
        void adminMfaResetRevokesCreatedKeys() {
            final User admin = persistUser("reset-keys-admin@example.com", List.of("ROLE_ADMIN"));
            admin.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            admin.enableMfa();
            userRepository.saveAndFlush(admin);
            final UUID adminSession = UUID.randomUUID();
            authTokenService.generateRefreshToken(admin, TENANT_ID, adminSession, Instant.now());
            jdbcTemplate.update("UPDATE users SET mfa_enabled_at = now() - INTERVAL '26 hours', "
                    + "mfa_enabled_notice_sent_at = now() - INTERVAL '25 hours' WHERE id = ?", admin.getId());

            final User target = persistUser("reset-keys-target@example.com", List.of("ROLE_ADMIN"));
            target.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            target.enableMfa();
            userRepository.saveAndFlush(target);
            final UUID personal = apiKey(target);
            final UserPrincipal asTarget = new UserPrincipal(target.getId(), TENANT_ID, target.getEmail(),
                    List.of("ROLE_ADMIN"), List.of(), List.of(), UUID.randomUUID());
            final UUID tenantKey;
            final UUID integrationId;
            TenantContext.set(TENANT_ID);
            try {
                tenantKey = apiKeyService.createApiKey(new CreateApiKeyRequest(
                        "made by intruder", ApiKeyType.TENANT,
                        List.of(ApiKeyScope.TEAMS_READ), null), asTarget).id();
                integrationId = integrationService.createIntegration(
                        new CreateIntegrationRequest(
                                "intruder-int-2", "generic", null, null), asTarget).id();
                entityManager.flush();
                startFreshRequest();

                mfaService.resetMfaByAdmin(target.getId(),
                        new UserPrincipal(admin.getId(), TENANT_ID, admin.getEmail(), List.of("ROLE_ADMIN"),
                                List.of(), List.of(), adminSession),
                        Instant.now().minus(Duration.ofHours(1)));
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }

            assertThat(revoked(personal)).as("personal key, by the reset itself").isTrue();
            assertThat(revoked(tenantKey)).as("tenant key the user created").isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT revoked_at IS NOT NULL FROM integrations WHERE id = ?", Boolean.class, integrationId))
                    .as("integration the user created").isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                    Boolean.class, target.getId())).isFalse();
        }

        /** An operator admin with MFA, backup codes and a live MFA session, committed (backlog #0-88). */
        private User committedOperatorWithMfa(String email) {
            final User user = User.forTesting(null, "platform-operator", email,
                    "hashed-password", true, List.of("ROLE_ADMIN"));
            user.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            user.enableMfa();
            final User saved = userRepository.saveAndFlush(user);
            mfaBackupCodeRepository.saveAndFlush(MfaBackupCode.create(saved, "backup-code-hash"));
            return saved;
        }

        private void deleteCommittedUser(UUID userId) {
            for (final String table : List.of("auth_email_outbox", "auth_tokens", "mfa_backup_codes")) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
            }
            jdbcTemplate.update("DELETE FROM api_keys WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("break-glass reset commits like the admin reset, audited with a confirmed send (backlog #0-88)")
        void breakGlassReset() {
            final User operator = committedOperatorWithMfa("break-glass-ok@example.com");
            final String refresh = authTokenService.generateRefreshToken(
                    operator, "platform-operator", UUID.randomUUID(), Instant.now());
            final UUID key = apiKeyRepository.saveAndFlush(ApiKey.createPersonal(
                    "platform-operator", "operator key", "b".repeat(64), "bbbbbbbb", List.of("teams:read"),
                    null, operator)).getId();
            try {
                mfaService.resetMfaBreakGlass(operator.getEmail(), "Jane Doe", "lost phone", "it@test-host", Duration.ofSeconds(30));

                assertThat(jdbcTemplate.queryForObject("SELECT revoked_at IS NOT NULL FROM api_keys WHERE id = ?",
                        Boolean.class, key)).as("personal API key (backlog #0-89)").isTrue();

                assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                        Boolean.class, operator.getId())).isFalse();
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?",
                        Integer.class, operator.getId())).isZero();
                assertThat(jdbcTemplate.queryForList("SELECT email_type FROM auth_email_outbox WHERE user_id = ?",
                        String.class, operator.getId())).containsExactly("MFA_RESET");
                assertThatThrownBy(() -> authTokenService.rotateRefreshToken(refresh))
                        .isInstanceOf(BusinessException.class);
                Mockito.verify(auditEventPublisher).publishAuthConfirmed(
                        ArgumentMatchers.eq(operator.getId()),
                        ArgumentMatchers.eq("platform-operator"),
                        ArgumentMatchers.eq(
                                AuditEventTypes.MFA_RESET_BREAK_GLASS),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.eq("break-glass:Jane Doe"),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(Duration.ofSeconds(30)));
            } finally {
                deleteCommittedUser(operator.getId());
            }
        }

        @Test
        @DisplayName("break-glass finds only platform-operator users: the same email in another tenant is not found (review of #0-88)")
        void breakGlassOperatorTenantOnly() {
            final User customerAdmin = User.forTesting(null, "acme-bg", "same-email@example.com",
                    "hashed-password", true, List.of("ROLE_ADMIN"));
            customerAdmin.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            customerAdmin.enableMfa();
            userRepository.saveAndFlush(customerAdmin);
            startFreshRequest();

            assertThatThrownBy(() -> mfaService.resetMfaBreakGlass("same-email@example.com", "Jane Doe",
                    "lost phone", "it@test-host", Duration.ofSeconds(30)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                    Boolean.class, customerAdmin.getId())).isTrue();
        }

        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("break-glass reset whose audit Kafka does not confirm changes nothing (backlog #0-88)")
        void breakGlassRollsBackWithoutAudit() {
            final User operator = committedOperatorWithMfa("break-glass-rollback@example.com");
            final String refresh = authTokenService.generateRefreshToken(
                    operator, "platform-operator", UUID.randomUUID(), Instant.now());
            org.mockito.BDDMockito.willThrow(new com.incidentplatform.shared.audit.AuditNotConfirmedException(
                            "not confirmed", null))
                    .given(auditEventPublisher).publishAuthConfirmed(
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.any(), ArgumentMatchers.any());
            try {
                assertThatThrownBy(() -> mfaService.resetMfaBreakGlass(
                        operator.getEmail(), "Jane Doe", "lost phone", "it@test-host", Duration.ofSeconds(30)))
                        .isInstanceOf(com.incidentplatform.shared.audit.AuditNotConfirmedException.class);

                assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled FROM users WHERE id = ?",
                        Boolean.class, operator.getId())).as("factor kept").isTrue();
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?",
                        Integer.class, operator.getId())).as("backup codes kept").isEqualTo(1);
                assertThat(jdbcTemplate.queryForList("SELECT email_type FROM auth_email_outbox WHERE user_id = ?",
                        String.class, operator.getId())).as("no email queued").isEmpty();
                assertThat(authTokenService.rotateRefreshToken(refresh).accessToken())
                        .as("session still live").isNotBlank();
            } finally {
                deleteCommittedUser(operator.getId());
            }
        }

        /**
         * RFC 6238 (HMAC-SHA1, 30 s step, 6 digits), written independently of
         * {@code TotpService} so this test does not verify the service against
         * itself. The service accepts ±1 step, so a step boundary between
         * computing and verifying the code does not make the test flaky.
         */
        private String currentTotpCode(String base32Secret) throws Exception {
            final long timeStep = Instant.now().getEpochSecond() / 30;
            final Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "RAW"));
            final byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array());
            final int offset = hash[hash.length - 1] & 0x0F;
            final int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        }

        /** RFC 4648 base32, no padding — the alphabet TOTP secrets use. */
        private byte[] base32Decode(String base32) {
            final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            int buffer = 0;
            int bits = 0;
            for (final char c : base32.toUpperCase().toCharArray()) {
                buffer = (buffer << 5) | alphabet.indexOf(c);
                bits += 5;
                if (bits >= 8) {
                    out.write((buffer >> (bits - 8)) & 0xFF);
                    bits -= 8;
                }
            }
            return out.toByteArray();
        }
    }

    /**
     * Backlog #0-51: {@code chk_auth_token_type} listed three token types
     * while {@link AuthToken.Type} had five, so every MFA token INSERT failed
     * on a real database and MFA login was a 500. Storing one token of every
     * enum value keeps the enum and the constraint in step: adding a type
     * without widening the constraint in a migration fails here.
     */
    @Nested
    @DisplayName("AuthToken types vs. chk_auth_token_type (backlog #0-51)")
    class TokenTypes {

        @Test
        @DisplayName("a token of every AuthToken.Type can be stored")
        void everyTypeCanBeStored() {
            final User user = persistUser("types@example.com", List.of("ROLE_RESPONDER"));

            for (final AuthToken.Type type : AuthToken.Type.values()) {
                authTokenRepository.saveAndFlush(AuthToken.create(
                        user, TENANT_ID, "hash-type-" + type, type,
                        Instant.now().plusSeconds(3600)));
            }

            assertThat(jdbcTemplate.queryForList(
                    "SELECT type FROM auth_tokens WHERE user_id = ?",
                    String.class, user.getId()))
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.Arrays.stream(AuthToken.Type.values())
                                    .map(Enum::name).toList());
        }
    }

    /**
     * Backlog #0-53: the latest-entry lookup returned {@code Optional}
     * from an {@code ORDER BY} query with no row limit, so a user with more than
     * one outbox entry of a type (every resent invite, every repeated password
     * reset) made it throw instead of returning the newest entry.
     */
    @Nested
    @DisplayName("AuthEmailOutboxRepository — latest entry (backlog #0-53)")
    class AuthEmailOutboxRepositoryTests {

        @Test
        @DisplayName("returns the newest of several entries")
        void returnsNewestOfSeveral() {
            final User user = persistUser("outbox@example.com", List.of("ROLE_RESPONDER"));
            final AuthEmailOutbox older = authEmailOutboxRepository.saveAndFlush(AuthEmailOutbox.request(
                    user, AuthEmailType.INVITE, java.time.Duration.ofDays(7)));
            jdbcTemplate.update("UPDATE auth_email_outbox SET created_at = created_at - INTERVAL '1 minute' "
                    + "WHERE id = ?", older.getId());
            final AuthEmailOutbox newest = authEmailOutboxRepository.saveAndFlush(AuthEmailOutbox.request(
                    user, AuthEmailType.INVITE, java.time.Duration.ofDays(7)));

            assertThat(authEmailOutboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    user.getId(), AuthEmailType.INVITE))
                    .map(AuthEmailOutbox::getId).contains(newest.getId());
        }
    }

    /**
     * Backlog #0-52: the auth email outbox as a send intent, against the real
     * schema — the two scheduler lanes, the conditional UPDATEs and the V19
     * CHECKs, each step of an attempt ({@code AuthEmailPersistenceService}) with
     * real tokens, the scheduler superseding older requests, resend-invite and
     * forgot-password next to a request still being retried, and the purge.
     */
    @Nested
    @DisplayName("AuthEmailOutbox as a send intent (backlog #0-52)")
    class AuthEmailOutboxSendIntent {

        private static final java.time.Duration INVITE_LIFETIME = java.time.Duration.ofDays(7);
        private static final java.time.Duration RESET_LIFETIME = java.time.Duration.ofMinutes(15);
        private static final java.time.Duration NO_TOLERANCE = java.time.Duration.ZERO;

        private final org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, 10);

        private AuthEmailOutbox request(User user, AuthEmailType type) {
            return authEmailOutboxRepository.saveAndFlush(AuthEmailOutbox.request(
                    user, type, type == AuthEmailType.INVITE ? INVITE_LIFETIME : RESET_LIFETIME));
        }

        private User invitedUser(String email) {
            return userRepository.saveAndFlush(User.forTesting(
                    null, TENANT_ID, email, null, true, List.of("ROLE_RESPONDER")));
        }

        private void ageBy(AuthEmailOutbox entry, String interval) {
            jdbcTemplate.update("UPDATE auth_email_outbox SET created_at = created_at - CAST(? AS INTERVAL), "
                    + "next_attempt_at = next_attempt_at - CAST(? AS INTERVAL) WHERE id = ?",
                    interval, interval, entry.getId());
        }

        private AuthEmailOutbox reload(AuthEmailOutbox entry) {
            entityManager.flush();
            entityManager.clear();
            return authEmailOutboxRepository.findById(entry.getId()).orElseThrow();
        }

        private List<String> statuses(User user, String emailType) {
            return jdbcTemplate.queryForList("""
                    SELECT status FROM auth_email_outbox
                    WHERE user_id = ? AND email_type = ? ORDER BY created_at
                    """, String.class, user.getId(), emailType);
        }

        @Test
        @DisplayName("the table holds no token: no raw_token or token_id column")
        void noTokenColumns() {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'auth_email_outbox'
                    """, String.class)).doesNotContain("raw_token", "token_id");
        }

        @Test
        @DisplayName("a request copies email and tenant and is due at once, with a deadline of its lifetime")
        void requestShape() {
            final User user = invitedUser("shape@example.com");

            final AuthEmailOutbox entry = reload(request(user, AuthEmailType.INVITE));

            assertThat(entry.getStatus()).isEqualTo(AuthEmailStatus.PENDING);
            assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(entry.getEmail()).isEqualTo("shape@example.com");
            assertThat(entry.getNextAttemptAt()).isEqualTo(entry.getCreatedAt());
            assertThat(java.time.Duration.between(entry.getCreatedAt(), entry.getDeadline()))
                    .isEqualTo(INVITE_LIFETIME);
        }

        @Test
        @DisplayName("PENDING lane: due entries, oldest due first; a future one waits")
        void pendingLane() {
            final AuthEmailOutbox older = request(invitedUser("lane-p1@example.com"), AuthEmailType.INVITE);
            ageBy(older, "1 minute");
            final AuthEmailOutbox newer = request(invitedUser("lane-p2@example.com"), AuthEmailType.INVITE);
            final AuthEmailOutbox future = request(invitedUser("lane-p3@example.com"), AuthEmailType.INVITE);
            jdbcTemplate.update("UPDATE auth_email_outbox SET next_attempt_at = NOW() + INTERVAL '1 hour' "
                    + "WHERE id = ?", future.getId());
            entityManager.clear();

            assertThat(authEmailOutboxRepository.findDuePending(Instant.now().plusSeconds(1), page))
                    .extracting(AuthEmailOutbox::getId)
                    .containsExactly(older.getId(), newer.getId());
        }

        @Test
        @DisplayName("retry lane: FAILED entries whose next attempt has come, none of the PENDING ones")
        void retryLane() {
            final Instant now = Instant.now();
            final AuthEmailOutbox due = request(invitedUser("lane-f1@example.com"), AuthEmailType.INVITE);
            final AuthEmailOutbox later = request(invitedUser("lane-f2@example.com"), AuthEmailType.INVITE);
            request(invitedUser("lane-f3@example.com"), AuthEmailType.INVITE);
            assertThat(authEmailOutboxRepository.markFailed(due.getId(), "smtp down", now.minusSeconds(5)))
                    .isEqualTo(1);
            assertThat(authEmailOutboxRepository.markFailed(later.getId(), "smtp down", now.plusSeconds(3600)))
                    .isEqualTo(1);
            entityManager.clear();

            assertThat(authEmailOutboxRepository.findDueFailed(now, page))
                    .extracting(AuthEmailOutbox::getId).containsExactly(due.getId());
            assertThat(reload(due).getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("the conditional UPDATEs change an entry only while it is open")
        void conditionalUpdates() {
            final AuthEmailOutbox entry = request(invitedUser("cond@example.com"), AuthEmailType.INVITE);

            assertThat(authEmailOutboxRepository.markSent(entry.getId(), Instant.now())).isEqualTo(1);
            assertThat(authEmailOutboxRepository.markSent(entry.getId(), Instant.now())).isZero();
            assertThat(authEmailOutboxRepository.markFailed(entry.getId(), "late", Instant.now())).isZero();
            assertThat(authEmailOutboxRepository.close(entry.getId(), AuthEmailStatus.SUPERSEDED, "late"))
                    .isZero();

            final AuthEmailOutbox sent = reload(entry);
            assertThat(sent.getStatus()).isEqualTo(AuthEmailStatus.SENT);
            assertThat(sent.getNextAttemptAt()).isNull();
            assertThat(sent.getSentAt()).isNotNull();
            assertThat(sent.getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("V19 rejects an open entry without a next attempt, and a terminal one with it")
        void nextAttemptCheck() {
            final AuthEmailOutbox entry = request(invitedUser("check@example.com"), AuthEmailType.INVITE);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE auth_email_outbox SET status = 'SENT' WHERE id = ?", entry.getId()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("prepareAttempt creates a working token and invalidates the user's earlier one")
        void prepareAttemptCreatesToken() {
            final User user = invitedUser("prepare@example.com");
            final String earlier = authTokenService.generateInviteToken(user, TENANT_ID);
            final AuthEmailOutbox entry = request(user, AuthEmailType.INVITE);
            entityManager.clear();

            final AuthEmailPersistenceService.Attempt attempt =
                    authEmailPersistenceService.prepareAttempt(entry, Instant.now(), NO_TOLERANCE);

            assertThat(attempt).isInstanceOf(AuthEmailPersistenceService.Attempt.Send.class);
            final String raw = ((AuthEmailPersistenceService.Attempt.Send) attempt).rawToken();
            assertThat(authTokenService.peekToken(raw, AuthToken.Type.INVITE).getUser().getId())
                    .isEqualTo(user.getId());
            assertThat(authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.INVITE, Instant.now()))
                    .singleElement()
                    .extracting(AuthToken::getId)
                    .isEqualTo(((AuthEmailPersistenceService.Attempt.Send) attempt).tokenId());
            assertThatThrownBy(() -> authTokenService.peekToken(earlier, AuthToken.Type.INVITE))
                    .as("the earlier link no longer works");
        }

        @Test
        @DisplayName("a failed attempt invalidates its token and schedules the next one")
        void recordFailedInvalidatesToken() {
            final User user = invitedUser("failed@example.com");
            final AuthEmailOutbox entry = request(user, AuthEmailType.INVITE);
            final AuthEmailPersistenceService.Attempt.Send send = (AuthEmailPersistenceService.Attempt.Send)
                    authEmailPersistenceService.prepareAttempt(entry, Instant.now(), NO_TOLERANCE);
            // timestamptz keeps microseconds and rounds the rest, so a nanosecond
            // Instant can come back 1 µs later; built at µs, it must come back equal.
            final Instant next = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

            assertThat(authEmailPersistenceService.recordFailed(
                    entry.getId(), send.tokenId(), "smtp down", next, Instant.now())).isTrue();

            assertThat(authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.INVITE, Instant.now())).isEmpty();
            final AuthEmailOutbox failed = reload(entry);
            assertThat(failed.getStatus()).isEqualTo(AuthEmailStatus.FAILED);
            assertThat(failed.getNextAttemptAt()).isEqualTo(next);
        }

        @Test
        @DisplayName("an older request is superseded by a newer one when the scheduler reaches it")
        void olderRequestSuperseded() {
            final User user = invitedUser("newer@example.com");
            final AuthEmailOutbox older = request(user, AuthEmailType.INVITE);
            ageBy(older, "1 minute");
            request(user, AuthEmailType.INVITE);
            entityManager.clear();
            final AuthEmailOutbox olderRead = authEmailOutboxRepository.findById(older.getId()).orElseThrow();

            assertThat(authEmailPersistenceService.prepareAttempt(olderRead, Instant.now(), NO_TOLERANCE))
                    .isEqualTo(new AuthEmailPersistenceService.Attempt.Closed(
                            AuthEmailStatus.SUPERSEDED, "replaced by a newer request"));
            assertThat(statuses(user, "INVITE")).containsExactly("SUPERSEDED", "PENDING");
        }

        @Test
        @DisplayName("an invite of a user who has meanwhile set a password is superseded")
        void acceptedInviteSuperseded() {
            final User user = invitedUser("accepted@example.com");
            final AuthEmailOutbox entry = request(user, AuthEmailType.INVITE);
            jdbcTemplate.update("UPDATE users SET password_hash = 'set' WHERE id = ?", user.getId());
            entityManager.clear();

            assertThat(authEmailPersistenceService.prepareAttempt(entry, Instant.now(), NO_TOLERANCE))
                    .isEqualTo(new AuthEmailPersistenceService.Attempt.Closed(
                            AuthEmailStatus.SUPERSEDED, "invite already accepted"));
        }

        @Test
        @DisplayName("an entry past its deadline is given up without a token being created")
        void deadlinePassed() {
            final User user = invitedUser("late@example.com");
            final AuthEmailOutbox entry = request(user, AuthEmailType.PASSWORD_RESET);

            final AuthEmailPersistenceService.Attempt attempt = authEmailPersistenceService.prepareAttempt(
                    entry, entry.getDeadline().plusSeconds(1), NO_TOLERANCE);

            assertThat(attempt).isInstanceOfSatisfying(AuthEmailPersistenceService.Attempt.Closed.class,
                    closed -> assertThat(closed.status()).isEqualTo(AuthEmailStatus.PERMANENTLY_FAILED));
            assertThat(authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.PASSWORD_RESET, Instant.now())).isEmpty();
        }

        /**
         * Before #0-52 both paths had to close a FAILED row before inserting
         * theirs (a partial unique index allowed one open row per user and
         * type), and lost to a concurrent scheduler write with a 409 or 500.
         * Now they only insert.
         */
        @Test
        @DisplayName("resend-invite next to a request still being retried just adds a request")
        void resendNextToFailed() {
            final User user = invitedUser("resend-failed@example.com");
            final AuthEmailOutbox failed = request(user, AuthEmailType.INVITE);
            ageBy(failed, "1 minute");
            authEmailOutboxRepository.markFailed(failed.getId(), "smtp down", Instant.now().plusSeconds(60));
            entityManager.clear();

            TenantContext.set(TENANT_ID);
            try {
                resendInviteService.resendInvite(user.getId());
                entityManager.flush();
            } finally {
                TenantContext.clear();
            }

            assertThat(statuses(user, "INVITE")).containsExactly("FAILED", "PENDING");
        }

        @Test
        @DisplayName("forgot-password next to a request still being retried just adds a request")
        void forgotPasswordNextToFailed() {
            final User user = persistUser("reset-failed@example.com", List.of("ROLE_RESPONDER"));
            final AuthEmailOutbox failed = request(user, AuthEmailType.PASSWORD_RESET);
            ageBy(failed, "1 minute");
            authEmailOutboxRepository.markFailed(failed.getId(), "smtp down", Instant.now().plusSeconds(60));
            entityManager.clear();

            forgotPasswordService.initiateReset("reset-failed@example.com", TENANT_ID);
            entityManager.flush();

            assertThat(statuses(user, "PASSWORD_RESET")).containsExactly("FAILED", "PENDING");
        }

        @Test
        @DisplayName("the purge deletes terminal entries older than the cutoff and keeps open ones")
        void purge() {
            final User user = invitedUser("purge@example.com");
            final AuthEmailOutbox sent = request(user, AuthEmailType.INVITE);
            authEmailOutboxRepository.markSent(sent.getId(), Instant.now());
            final AuthEmailOutbox open = request(user, AuthEmailType.PASSWORD_RESET);
            jdbcTemplate.update("UPDATE auth_email_outbox SET created_at = created_at - INTERVAL '40 days'");

            final int deleted = authEmailOutboxRepository.deleteTerminalCreatedBefore(
                    Instant.now().minus(java.time.Duration.ofDays(30)));

            assertThat(deleted).isEqualTo(1);
            assertThat(authEmailOutboxRepository.findById(open.getId())).isPresent();
        }
    }

    /**
     * Backlog #0-49: the operator admin reconciler against the real repositories,
     * {@code UserService} and {@code ResendInviteService} — the paths its unit
     * test mocks. Each step flushes and clears, as separate scheduler runs would.
     */
    @Nested
    @DisplayName("Operator admin reconciler (backlog #0-49)")
    class OperatorAdminReconciliation {

        private static final String OP_TENANT = ReservedTenants.PLATFORM_OPERATOR;
        private static final String OP_EMAIL = "ops-reconcile@example.com";

        private OperatorTenantBootstrap reconciler() {
            return new OperatorTenantBootstrap(OP_EMAIL, userRepository, authTokenRepository,
                    authEmailOutboxRepository, userService, resendInviteService,
                    tenantRepository, new SimpleMeterRegistry());
        }

        private void run() {
            reconciler().scheduledReconcile();
            entityManager.flush();
            entityManager.clear();
        }

        private User operatorAdmin() {
            return userRepository.findByEmailAndTenantId(OP_EMAIL, OP_TENANT).orElseThrow();
        }

        private List<String> inviteStatuses(User user) {
            return jdbcTemplate.queryForList("""
                    SELECT status FROM auth_email_outbox
                    WHERE user_id = ? AND email_type = 'INVITE' ORDER BY created_at
                    """, String.class, user.getId());
        }

        /** The scheduler's steps for the latest invite: create the token, then sent or given up. */
        private void finishLatestInvite(User user, boolean permanentlyFailed) {
            final AuthEmailOutbox latest = authEmailOutboxRepository
                    .findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                            user.getId(), AuthEmailType.INVITE).orElseThrow();
            final AuthEmailPersistenceService.Attempt.Send send =
                    (AuthEmailPersistenceService.Attempt.Send) authEmailPersistenceService.prepareAttempt(
                            latest, Instant.now(), java.time.Duration.ZERO);
            if (permanentlyFailed) {
                authEmailPersistenceService.recordGivenUp(latest.getId(), send.tokenId(), "smtp down",
                        Instant.now());
            } else {
                authEmailPersistenceService.recordSent(latest, Instant.now());
            }
            entityManager.flush();
            entityManager.clear();
        }

        @Test
        @DisplayName("first run invites the admin; once they accept, runs change nothing")
        void invitesThenStopsOnceAccepted() {
            run();
            final User user = operatorAdmin();
            assertThat(user.getRoleNames()).containsExactly("ROLE_ADMIN");
            assertThat(inviteStatuses(user)).containsExactly("PENDING");

            finishLatestInvite(user, false);
            final User accepting = operatorAdmin();
            accepting.setPasswordHash("accepted");
            userRepository.saveAndFlush(accepting);
            entityManager.clear();
            run();

            assertThat(userRepository.existsActiveAcceptedUserWithRole(OP_TENANT, Role.ROLE_ADMIN))
                    .isTrue();
            assertThat(inviteStatuses(user)).containsExactly("SENT");
        }

        @Test
        @DisplayName("a permanently failed invite is reissued and the old token stops working")
        void permanentlyFailedInviteIsReissued() {
            run();
            final User user = operatorAdmin();
            finishLatestInvite(user, true);
            assertThat(authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.INVITE, Instant.now()))
                    .as("the undelivered token was invalidated").isEmpty();

            run();

            assertThat(inviteStatuses(user)).containsExactly("PERMANENTLY_FAILED", "PENDING");
            finishLatestInvite(user, false);
            assertThat(authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.INVITE, Instant.now())).hasSize(1);
        }

        @Test
        @DisplayName("a sent invite with a valid token is left alone")
        void sentValidInviteIsLeftAlone() {
            run();
            final User user = operatorAdmin();
            finishLatestInvite(user, false);

            run();

            assertThat(inviteStatuses(user)).containsExactly("SENT");
        }

        @Test
        @DisplayName("a sent invite whose token expired is reissued")
        void expiredInviteIsReissued() {
            run();
            final User user = operatorAdmin();
            finishLatestInvite(user, false);
            jdbcTemplate.update("""
                    UPDATE auth_tokens SET expires_at = NOW() - INTERVAL '1 hour'
                    WHERE user_id = ? AND type = 'INVITE'
                    """, user.getId());

            run();

            assertThat(inviteStatuses(user)).containsExactly("SENT", "PENDING");
        }

        @Test
        @DisplayName("existsByTenantId does not see archived users (@SQLRestriction)")
        void existsByTenantIdIgnoresArchivedUsers() {
            run();
            final User user = operatorAdmin();
            user.archive();
            userRepository.saveAndFlush(user);
            entityManager.clear();

            assertThat(userRepository.existsByTenantId(OP_TENANT)).isFalse();
        }
    }

    /**
     * Backlog #0-80: provisioning against the real schema (V21), the real
     * {@code UserService} and the real transaction boundary. Each test uses its
     * own tenant id, since the rollback test runs outside the test transaction.
     */
    @Nested
    @DisplayName("Tenant provisioning (backlog #0-80)")
    class TenantProvisioning {

        private final UserPrincipal operator = new UserPrincipal(UUID.randomUUID(),
                ReservedTenants.PLATFORM_OPERATOR, "ops@example.com",
                List.of(SecurityRoles.ROLE_ADMIN), List.of());

        private String newTenantId() {
            return "t-" + UUID.randomUUID().toString().substring(0, 13);
        }

        @Test
        @DisplayName("creates the tenant row and an invited admin in the new tenant")
        void provisions() {
            final String tenantId = newTenantId();

            final ProvisionTenantResponse response = tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "Acme", "admin@acme.example"), operator);
            entityManager.flush();
            entityManager.clear();

            final var tenant = tenantRepository.findById(tenantId).orElseThrow();
            assertThat(tenant.getFirstAdminEmail()).isEqualTo("admin@acme.example");
            assertThat(tenant.getCreatedBy()).isEqualTo(operator.userId());
            // Set by the database (now(), the transaction's start), so compared
            // loosely against the JVM clock rather than to the instant.
            assertThat(tenant.getCreatedAt()).isCloseTo(Instant.now(),
                    within(1, ChronoUnit.MINUTES));
            final User admin = userRepository.findByEmailAndTenantId("admin@acme.example", tenantId)
                    .orElseThrow();
            assertThat(admin.getId()).isEqualTo(response.adminUserId());
            assertThat(admin.getRoleNames()).containsExactly(SecurityRoles.ROLE_ADMIN);
            assertThat(admin.getPasswordHash()).as("set only by accepting the invite").isNull();
            assertThat(authEmailOutboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    admin.getId(), AuthEmailType.INVITE)).map(AuthEmailOutbox::getStatus)
                    .contains(AuthEmailStatus.PENDING);
        }

        @Test
        @DisplayName("a second provisioning of the same id is refused and overwrites nothing")
        void duplicateRefused() {
            final String tenantId = newTenantId();
            tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "First", "first@acme.example"), operator);

            assertThatThrownBy(() -> tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "Second", "second@acme.example"), operator))
                    .isInstanceOf(BusinessException.class);
            entityManager.clear();
            assertThat(tenantRepository.findById(tenantId).orElseThrow().getDisplayName())
                    .isEqualTo("First");
            assertThat(userRepository.findByEmailAndTenantId("second@acme.example", tenantId)).isEmpty();
        }

        private List<String> inviteStatuses(String tenantId, String email) {
            return jdbcTemplate.queryForList("""
                    SELECT o.status FROM auth_email_outbox o JOIN users u ON u.id = o.user_id
                    WHERE u.tenant_id = ? AND u.email = ? AND o.email_type = 'INVITE'
                    ORDER BY o.created_at
                    """, String.class, tenantId, email);
        }

        @Test
        @DisplayName("reissue: a permanently failed first invite is queued again in the new tenant")
        void reissueAfterPermanentFailure() {
            final String tenantId = newTenantId();
            tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "Acme", "admin@reissue.example"), operator);
            entityManager.flush();
            entityManager.clear();
            // The scheduler's steps for an invite it gave up on (as in
            // OperatorAdminReconciliation): token created, then given up.
            final User admin = userRepository.findByEmailAndTenantId("admin@reissue.example", tenantId)
                    .orElseThrow();
            final AuthEmailOutbox invite = authEmailOutboxRepository
                    .findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(admin.getId(), AuthEmailType.INVITE)
                    .orElseThrow();
            final AuthEmailPersistenceService.Attempt.Send send =
                    (AuthEmailPersistenceService.Attempt.Send) authEmailPersistenceService.prepareAttempt(
                            invite, Instant.now(), java.time.Duration.ZERO);
            authEmailPersistenceService.recordGivenUp(invite.getId(), send.tokenId(), "smtp down",
                    Instant.now());
            entityManager.flush();
            entityManager.clear();

            tenantProvisioningService.reissueFirstAdminInvite(tenantId, operator);
            entityManager.flush();

            assertThat(inviteStatuses(tenantId, "admin@reissue.example"))
                    .containsExactly("PERMANENTLY_FAILED", "PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE tenant_id = ?", Integer.class, tenantId))
                    .as("no second user").isEqualTo(1);
        }

        @Test
        @DisplayName("reissue: an archived first admin is not revived, and no user is created")
        void reissueRefusedForArchivedFirstAdmin() {
            final String tenantId = newTenantId();
            tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "Acme", "admin@gone.example"), operator);
            entityManager.flush();
            jdbcTemplate.update("UPDATE users SET archived_at = now(), active = FALSE WHERE tenant_id = ?",
                    tenantId);
            entityManager.clear();

            assertThatThrownBy(() -> tenantProvisioningService.reissueFirstAdminInvite(tenantId, operator))
                    .isInstanceOf(BusinessException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
        }

        @Test
        @DisplayName("provision: an id whose only users are archived is refused, and its row rolled back")
        void provisionRefusedForIdWithArchivedUsers() {
            final String tenantId = newTenantId();
            jdbcTemplate.update("""
                    INSERT INTO users (id, tenant_id, email, active, archived_at)
                    VALUES (gen_random_uuid(), ?, 'old@acme.example', FALSE, now())
                    """, tenantId);

            assertThatThrownBy(() -> tenantProvisioningService.provision(
                    new ProvisionTenantRequest(tenantId, "Acme", "new@acme.example"), operator))
                    .isInstanceOf(BusinessException.class);
            assertThat(userRepository.findByEmailAndTenantId("new@acme.example", tenantId)).isEmpty();
        }

        @Test
        @DisplayName("list reports whether each tenant's admin has accepted")
        void listReportsAdminActive() {
            final String pendingTenant = newTenantId();
            final String activeTenant = newTenantId();
            tenantProvisioningService.provision(
                    new ProvisionTenantRequest(pendingTenant, "Pending", "a@pending.example"), operator);
            tenantProvisioningService.provision(
                    new ProvisionTenantRequest(activeTenant, "Active", "a@active.example"), operator);
            entityManager.flush();
            entityManager.clear();
            final User accepting = userRepository.findByEmailAndTenantId("a@active.example", activeTenant)
                    .orElseThrow();
            accepting.setPasswordHash("accepted");
            userRepository.saveAndFlush(accepting);

            final List<TenantDto> tenants = tenantProvisioningService.list(
                    org.springframework.data.domain.PageRequest.of(0, 100)).getContent();

            assertThat(tenants).filteredOn(t -> t.tenantId().equals(pendingTenant))
                    .singleElement().extracting(TenantDto::adminActive).isEqualTo(false);
            assertThat(tenants).filteredOn(t -> t.tenantId().equals(activeTenant))
                    .singleElement().extracting(TenantDto::adminActive).isEqualTo(true);
        }

        @Test
        @DisplayName("the operator tenant's bootstrap records its tenant row once")
        void operatorBootstrapRecordsTenant() {
            jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", ReservedTenants.PLATFORM_OPERATOR);
            final OperatorTenantBootstrap bootstrap = new OperatorTenantBootstrap("ops-row@example.com",
                    userRepository, authTokenRepository, authEmailOutboxRepository, userService,
                    resendInviteService, tenantRepository, new SimpleMeterRegistry());

            bootstrap.scheduledReconcile();
            bootstrap.scheduledReconcile();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tenants WHERE tenant_id = ?", Integer.class,
                    ReservedTenants.PLATFORM_OPERATOR)).isEqualTo(1);
        }

        /**
         * Outside the test transaction, so provisioning commits or rolls back on
         * its own. The tenants row is inserted first; a failure after it must take
         * the row with it, or the id would be taken by a tenant nobody can log in
         * to. The failure used here is the guard that follows the insert (an id
         * whose only user is archived, committed beforehand), so the row is
         * certainly written before the exception. Fails if provision() loses its
         * transaction: insertIfAbsent would then commit on its own (found in
         * review: an earlier version failed on the tenants insert itself and
         * proved nothing).
         */
        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("a failure after the tenant insert rolls the tenant row back")
        void failureRollsBackTenant() {
            final String tenantId = newTenantId();
            jdbcTemplate.update("""
                    INSERT INTO users (id, tenant_id, email, active, archived_at)
                    VALUES (gen_random_uuid(), ?, 'old@acme.example', FALSE, now())
                    """, tenantId);
            try {
                assertThatThrownBy(() -> tenantProvisioningService.provision(
                        new ProvisionTenantRequest(tenantId, "Acme", "new@acme.example"), operator))
                        .isInstanceOf(BusinessException.class);

                assertThat(tenantRepository.existsById(tenantId)).isFalse();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM users WHERE tenant_id = ?", Integer.class, tenantId))
                        .as("only the pre-existing archived user").isEqualTo(1);
            } finally {
                jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
            }
        }
    }

    /**
     * Backlog #0-83: what the platform API requires of a session's second
     * factor, on the real schema (V22) and the real token service, through the
     * life of a session. Defaults: MFA verified within 12 h, factor enabled at
     * least 24 h ago.
     */
    @Nested
    @DisplayName("Session MFA status (backlog #0-83)")
    class SessionMfaStatus {

        private User user;

        @org.junit.jupiter.api.BeforeEach
        void userWithEstablishedFactor() {
            user = persistUser("mfa-session-" + UUID.randomUUID() + "@example.com", List.of("ROLE_ADMIN"));
            user.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            user.enableMfa();
            userRepository.saveAndFlush(user);
            enrolledAgo(java.time.Duration.ofHours(50));
            noticeSentAgo(java.time.Duration.ofHours(48));
        }

        /**
         * The MFA_ENABLED notice of the enrolment, sent through the real
         * outbox path (request, then the scheduler's recordSent), which also
         * records it on the user; then dated back.
         */
        private void noticeSentAgo(java.time.Duration ago) {
            final AuthEmailOutbox notice = authEmailOutboxRepository.saveAndFlush(
                    AuthEmailOutbox.request(user, AuthEmailType.MFA_ENABLED, java.time.Duration.ofHours(24)));
            assertThat(authEmailPersistenceService.recordSent(notice, Instant.now())).isTrue();
            entityManager.flush();
            entityManager.clear();
            jdbcTemplate.update("UPDATE users SET mfa_enabled_notice_sent_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(ago)), user.getId());
        }

        private void enrolledAgo(java.time.Duration ago) {
            jdbcTemplate.update("UPDATE users SET mfa_enabled_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(ago)), user.getId());
        }

        private void flushAndClear() {
            entityManager.flush();
            entityManager.clear();
        }

        private MfaSessionStatusService.Status status(UUID session) {
            return mfaSessionStatusService.check(user.getId(), TENANT_ID, session);
        }

        @Test
        @DisplayName("an MFA session of an established factor is accepted; password-only, another user's and none are not")
        void recordedAtLogin() {
            final UUID mfaSession = UUID.randomUUID();
            final UUID passwordSession = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, mfaSession, Instant.now());
            authTokenService.generateRefreshToken(user, TENANT_ID, passwordSession, null);
            flushAndClear();

            assertThat(status(mfaSession)).isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
            assertThat(status(passwordSession)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
            assertThat(mfaSessionStatusService.check(UUID.randomUUID(), TENANT_ID, mfaSession))
                    .isEqualTo(MfaSessionStatusService.Status.NO_MFA);
            assertThat(mfaSessionStatusService.check(user.getId(), TENANT_ID, null))
                    .isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        }

        @Test
        @DisplayName("another tenant's id for the same user and session finds no session (tenant-scoped)")
        void otherTenant() {
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();

            assertThat(mfaSessionStatusService.check(user.getId(), "other-tenant", session))
                    .isEqualTo(MfaSessionStatusService.Status.NO_MFA);
            assertThat(authTokenService.isSessionLive(user.getId(), "other-tenant", session)).isFalse();
        }

        @Test
        @DisplayName("isSessionLive: live, then not after logout; an expired session is not live")
        void sessionLiveness() {
            final UUID loggedOut = UUID.randomUUID();
            final UUID expired = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, loggedOut, null);
            authTokenService.generateRefreshToken(user, TENANT_ID, expired, null);
            flushAndClear();
            jdbcTemplate.update("UPDATE auth_tokens SET expires_at = now() - INTERVAL '1 minute' "
                    + "WHERE session_id = ?", expired);

            assertThat(authTokenService.isSessionLive(user.getId(), TENANT_ID, loggedOut)).isTrue();
            assertThat(authTokenService.isSessionLive(user.getId(), TENANT_ID, expired)).isFalse();
            authTokenService.invalidateRefreshTokenForSession(user.getId(), loggedOut);
            flushAndClear();
            assertThat(authTokenService.isSessionLive(user.getId(), TENANT_ID, loggedOut)).isFalse();
            assertThat(authTokenService.isSessionLive(user.getId(), TENANT_ID, null)).isFalse();
        }

        @Test
        @DisplayName("a notice is recorded once, and not on an account whose MFA is now off")
        void noticeRecordingGuards() {
            final Instant requestedAt = Instant.now();
            assertThat(userRepository.recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, requestedAt, Instant.now()))
                    .as("already recorded by the fixture").isZero();

            jdbcTemplate.update("UPDATE users SET mfa_enabled_notice_sent_at = NULL, mfa_enabled = FALSE "
                    + "WHERE id = ?", user.getId());
            assertThat(userRepository.recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, requestedAt, Instant.now()))
                    .as("MFA is off").isZero();

            jdbcTemplate.update("UPDATE users SET mfa_enabled = TRUE WHERE id = ?", user.getId());
            assertThat(userRepository.recordMfaEnabledNoticeSent(user.getId(), "other-tenant", requestedAt, Instant.now()))
                    .as("another tenant's id").isZero();
            assertThat(userRepository.recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, requestedAt, Instant.now()))
                    .as("current enrolment, nothing recorded yet").isEqualTo(1);
        }

        @Test
        @DisplayName("MFA verified longer ago than the maximum session age is refused")
        void tooOld() {
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session,
                    Instant.now().minus(java.time.Duration.ofHours(13)));
            flushAndClear();

            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.MFA_TOO_OLD);
        }

        @Test
        @DisplayName("a factor whose notice went out less than the grace period ago is refused, even in an MFA session")
        void enrolledTooRecently() {
            noticeSentAgo(java.time.Duration.ofHours(1));
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();

            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.MFA_ENROLLED_TOO_RECENTLY);
        }

        @Test
        @DisplayName("a factor whose MFA_ENABLED notice was never sent is refused")
        void noticeNotDelivered() {
            jdbcTemplate.update("UPDATE users SET mfa_enabled_notice_sent_at = NULL WHERE id = ?", user.getId());
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();

            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.MFA_NOTICE_NOT_DELIVERED);
        }

        @Test
        @DisplayName("a verified session of an account whose MFA is now off, or has no enrolment time, is refused")
        void defensiveBranches() {
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();

            jdbcTemplate.update("UPDATE users SET mfa_enabled_at = NULL WHERE id = ?", user.getId());
            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.MFA_NOTICE_NOT_DELIVERED);

            jdbcTemplate.update("UPDATE users SET mfa_enabled = FALSE WHERE id = ?", user.getId());
            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        }

        @Test
        @DisplayName("the decision outlives the outbox purge: sent rows deleted, factor still accepted")
        void survivesOutboxPurge() {
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();
            jdbcTemplate.update("DELETE FROM auth_email_outbox WHERE user_id = ?", user.getId());

            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
        }

        @Test
        @DisplayName("a notice of an earlier enrolment never marks a new factor")
        void earlierNoticeDoesNotCount() {
            final AuthEmailOutbox earlier = authEmailOutboxRepository.saveAndFlush(
                    AuthEmailOutbox.request(user, AuthEmailType.MFA_ENABLED, java.time.Duration.ofHours(24)));
            jdbcTemplate.update("UPDATE auth_email_outbox SET created_at = now() - INTERVAL '1 hour' WHERE id = ?",
                    earlier.getId());
            entityManager.clear();
            // Re-enrol: the factor's time moves to now and the recorded notice is cleared.
            final User reloaded = userRepository.findById(user.getId()).orElseThrow();
            reloaded.disableMfa();
            reloaded.storePendingMfaSecret(mfaEncryptionService.encrypt(totpService.generateSecret()));
            reloaded.enableMfa();
            userRepository.saveAndFlush(reloaded);
            final AuthEmailOutbox earlierRow = authEmailOutboxRepository.findById(earlier.getId()).orElseThrow();

            authEmailPersistenceService.recordSent(earlierRow, Instant.now());
            flushAndClear();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT mfa_enabled_notice_sent_at FROM users WHERE id = ?", java.sql.Timestamp.class,
                    user.getId())).as("the earlier enrolment's notice").isNull();
        }

        /**
         * Every bulk UPDATE/DELETE that clears the persistence context flushes
         * it first (found in review): otherwise a change made earlier in the
         * same transaction, here to the user, is silently discarded. One case
         * per such query, so dropping {@code flushAutomatically} from any of
         * them fails here. The queries need not match a row: Spring Data
         * flushes and clears either way.
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(BulkUpdate.class)
        @DisplayName("a bulk update flushes first: an earlier change to the user in the transaction survives")
        void bulkUpdateKeepsEarlierChange(BulkUpdate bulkUpdate) {
            final User managed = userRepository.findById(user.getId()).orElseThrow();
            managed.setPasswordHash("changed-before-" + bulkUpdate);

            final UUID userId = user.getId();
            final Instant now = Instant.now();
            switch (bulkUpdate) {
                case REVOKE_PERSONAL_API_KEYS -> apiKeyRepository.revokeAllPersonalKeysForUser(userId, now);
                case TOUCH_API_KEY -> apiKeyRepository.touchLastUsedAt(UUID.randomUUID(), now, now);
                case INVALIDATE_ALL_REFRESH_TOKENS -> authTokenRepository.invalidateAllRefreshTokens(userId, now);
                case INVALIDATE_SESSION -> authTokenRepository.invalidateRefreshTokenForSession(
                        userId, UUID.randomUUID(), now);
                case INVALIDATE_OTHER_SESSIONS -> authTokenRepository.invalidateAllRefreshTokensExceptSession(
                        userId, UUID.randomUUID(), now);
                case DELETE_EXPIRED_TOKENS -> authTokenRepository.deleteExpiredAndUsed(now.minusSeconds(86_400));
                case CLEAR_MFA_VERIFIED -> authTokenRepository.clearMfaVerified(userId, TENANT_ID);
                case DELETE_BACKUP_CODES -> mfaBackupCodeRepository.deleteAllByUserId(userId);
                case RECORD_MFA_NOTICE -> userRepository.recordMfaEnabledNoticeSent(userId, TENANT_ID, now, now);
            }
            entityManager.clear();

            assertThat(passwordHashOfUser(userId)).isEqualTo("changed-before-" + bulkUpdate);
        }

        private String passwordHashOfUser(UUID userId) {
            return jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        }

        @Test
        @DisplayName("V23/V24/V25: the outbox accepts every AuthEmailType and still rejects an unknown one")
        void outboxTypeConstraint() {
            // Every type, so a new AuthEmailType without a migration fails here (as for token types, #0-51).
            for (final AuthEmailType type : AuthEmailType.values()) {
                authEmailOutboxRepository.saveAndFlush(
                        AuthEmailOutbox.request(user, type, java.time.Duration.ofHours(24)));
            }
            assertThat(jdbcTemplate.queryForList(
                    "SELECT email_type FROM auth_email_outbox WHERE user_id = ?", String.class, user.getId()))
                    .contains("MFA_ENABLED", "MFA_DISABLED", "MFA_RESET", "API_KEY_CREATED");

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    UPDATE auth_email_outbox SET email_type = 'SOMETHING_ELSE' WHERE user_id = ?
                    """, user.getId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_auth_email_outbox_type");
        }

        @Test
        @DisplayName("rotation keeps the original verification time and a password-only session stays one; logout ends it")
        void rotationKeepsLogoutEnds() {
            final UUID session = UUID.randomUUID();
            final Instant verifiedAt = Instant.now().minus(java.time.Duration.ofHours(2))
                    .truncatedTo(ChronoUnit.MICROS);
            final String raw = authTokenService.generateRefreshToken(user, TENANT_ID, session, verifiedAt);
            final UUID passwordSession = UUID.randomUUID();
            final String rawPassword = authTokenService.generateRefreshToken(user, TENANT_ID, passwordSession, null);
            flushAndClear();

            authTokenService.rotateRefreshToken(raw);
            authTokenService.rotateRefreshToken(rawPassword);
            flushAndClear();
            assertThat(status(session)).as("after rotation").isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT mfa_verified_at FROM auth_tokens
                    WHERE session_id = ? AND used_at IS NULL
                    """, java.sql.Timestamp.class, session).toInstant())
                    .as("rotation does not make the verification recent").isEqualTo(verifiedAt);
            assertThat(status(passwordSession)).as("password-only after rotation")
                    .isEqualTo(MfaSessionStatusService.Status.NO_MFA);

            authTokenService.invalidateRefreshTokenForSession(user.getId(), session);
            flushAndClear();
            assertThat(status(session)).as("after logout").isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        }

        @Test
        @DisplayName("disabling MFA ends it for every session at once")
        void disablingMfaEndsIt() {
            final UUID first = UUID.randomUUID();
            final UUID second = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, first, Instant.now());
            authTokenService.generateRefreshToken(user, TENANT_ID, second, Instant.now());
            flushAndClear();

            authTokenService.forgetMfaOfAllSessions(user.getId(), "other-tenant");
            flushAndClear();
            assertThat(status(first)).as("another tenant's id changes nothing")
                    .isEqualTo(MfaSessionStatusService.Status.ACCEPTED);

            authTokenService.forgetMfaOfAllSessions(user.getId(), TENANT_ID);
            flushAndClear();

            assertThat(status(first)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
            assertThat(status(second)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        }

        @Test
        @DisplayName("an expired session does not count")
        void expiredSession() {
            final UUID session = UUID.randomUUID();
            authTokenService.generateRefreshToken(user, TENANT_ID, session, Instant.now());
            flushAndClear();
            jdbcTemplate.update("UPDATE auth_tokens SET expires_at = now() - INTERVAL '1 minute' "
                    + "WHERE session_id = ?", session);

            assertThat(status(session)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        }
    }

    @Nested
    @DisplayName("Flyway migrations")
    class Migrations {

        /**
         * The @SpringBootTest context starting up at all already proves
         * every migration applied without error — this adds an explicit,
         * positive check on the table set, rather than relying solely on
         * "the context loaded" as an implicit signal.
         */
        @Test
        @DisplayName("core tables exist after migration")
        void coreTablesExist() {
            final List<String> tables = List.of(
                    "users", "teams", "team_members", "auth_tokens", "api_keys",
                    "slack_workspaces");

            for (final String table : tables) {
                final Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.tables
                                              WHERE table_name = ?
                        """, Integer.class, table);
                assertThat(count).as("table %s should exist", table).isEqualTo(1);
            }
        }
    }

    /**
     * The actual regression coverage for the confirmed historical bug —
     * see this class's own Javadoc for the full account.
     */
    @Nested
    @DisplayName("TeamMemberRepository — real JPQL")
    class TeamMemberRepositoryTests {

        @Test
        @DisplayName("findTeamIdsByUserIdAndTenantId returns all teams the user belongs to")
        void findTeamIdsByUserIdAndTenantId() {
            final User user = persistUser("jan@example.com", List.of("ROLE_RESPONDER"));
            final Team teamA = persistTeam("Team A");
            final Team teamB = persistTeam("Team B");
            persistTeamMember(teamA, user, TeamRole.RESPONDER);
            persistTeamMember(teamB, user, TeamRole.MANAGER);

            final List<UUID> result = teamMemberRepository
                    .findTeamIdsByUserIdAndTenantId(user.getId(), TENANT_ID);

            assertThat(result).containsExactlyInAnyOrder(teamA.getId(), teamB.getId());
        }

        /**
         * The exact query that was previously broken
         * ({@code tm.role} vs. the real {@code tm.teamRole} field) —
         * exercising it against a real Hibernate session is the whole
         * point of this file. Also verifies the MANAGER-only filtering
         * itself, not just that the query runs without throwing.
         */
        @Test
        @DisplayName("findManagedTeamIdsByUserIdAndTenantId returns only teams " +
                "where the user is MANAGER — the historically-broken query")
        void findManagedTeamIdsByUserIdAndTenantId() {
            final User user = persistUser("anna@example.com", List.of("ROLE_RESPONDER"));
            final Team managedTeam = persistTeam("Managed Team");
            final Team memberOnlyTeam = persistTeam("Member-only Team");
            persistTeamMember(managedTeam, user, TeamRole.MANAGER);
            persistTeamMember(memberOnlyTeam, user, TeamRole.RESPONDER);

            final List<UUID> result = teamMemberRepository
                    .findManagedTeamIdsByUserIdAndTenantId(user.getId(), TENANT_ID);

            assertThat(result).containsExactly(managedTeam.getId());
        }

        @Test
        @DisplayName("findManagedTeamIdsByUserIdAndTenantId returns empty when " +
                "the user manages no teams")
        void findManagedTeamIdsByUserIdAndTenantIdReturnsEmptyWhenNoneManaged() {
            final User user = persistUser("respondent@example.com", List.of("ROLE_RESPONDER"));
            final Team team = persistTeam("Some Team");
            persistTeamMember(team, user, TeamRole.RESPONDER);

            final List<UUID> result = teamMemberRepository
                    .findManagedTeamIdsByUserIdAndTenantId(user.getId(), TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("UserRepository — real JPQL and @SQLRestriction behavior")
    class UserRepositoryTests {

        @Test
        @DisplayName("findByEmailAndTenantId finds an active user")
        void findByEmailAndTenantIdFindsActiveUser() {
            persistUser("active@example.com", List.of("ROLE_RESPONDER"));

            final var result = userRepository
                    .findByEmailAndTenantId("active@example.com", TENANT_ID);

            assertThat(result).isPresent();
        }

        /**
         * Regression coverage for the {@code @SQLRestriction} behavior
         * documented on {@link UserRepository} itself — an archived user
         * must be automatically excluded from this query, with no
         * explicit {@code AndArchivedAtIsNull} needed in the method name.
         * A mocked repository cannot prove this: {@code @SQLRestriction}
         * is a Hibernate-level SQL rewrite, invisible to Mockito.
         */
        @Test
        @DisplayName("findByEmailAndTenantId excludes an archived user " +
                "(@SQLRestriction, real Hibernate behavior)")
        void findByEmailAndTenantIdExcludesArchivedUser() {
            final User user = persistUser("archived@example.com", List.of("ROLE_RESPONDER"));
            user.archive();
            userRepository.saveAndFlush(user);

            final var result = userRepository
                    .findByEmailAndTenantId("archived@example.com", TENANT_ID);

            assertThat(result).isEmpty();
        }

        /**
         * Regression coverage for the native query deliberately bypassing
         * {@code @SQLRestriction} — used by admin restore/anonymize
         * flows, which specifically need to reach an archived user that
         * {@link UserRepository#findByIdAndTenantId} would otherwise hide.
         */
        @Test
        @DisplayName("findAnyByIdAndTenantId finds an archived user — " +
                "the native query intentionally bypassing @SQLRestriction")
        void findAnyByIdAndTenantIdFindsArchivedUser() {
            final User user = persistUser("toRestore@example.com", List.of("ROLE_RESPONDER"));
            user.archive();
            userRepository.saveAndFlush(user);

            final var result = userRepository
                    .findAnyByIdAndTenantId(user.getId(), TENANT_ID);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("countActiveUsersWithRoleExcluding excludes the given user from the count")
        void countActiveUsersWithRoleExcludingExcludesGivenUser() {
            final User admin1 = persistUser("admin1@example.com", List.of("ROLE_ADMIN"));
            persistUser("admin2@example.com", List.of("ROLE_ADMIN"));

            final long count = userRepository.countActiveUsersWithRoleExcluding(
                    TENANT_ID, Role.ROLE_ADMIN, admin1.getId());

            // Only admin2 counted — admin1 is the excluded user.
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("countActiveUsersWithRoleExcluding does not count a different role")
        void countActiveUsersWithRoleExcludingDoesNotCountDifferentRole() {
            final User admin = persistUser("solo-admin@example.com", List.of("ROLE_ADMIN"));
            persistUser("responder@example.com", List.of("ROLE_RESPONDER"));

            final long count = userRepository.countActiveUsersWithRoleExcluding(
                    TENANT_ID, Role.ROLE_ADMIN, admin.getId());

            assertThat(count).isZero();
        }
    }

    @Nested
    @DisplayName("AuthTokenRepository — real JPQL")
    class AuthTokenRepositoryTests {

        @Test
        @DisplayName("findValidByHashAndType finds a non-expired, unused token")
        void findValidByHashAndTypeFindsValidToken() {
            final User user = persistUser("invitee@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken token = AuthToken.create(
                    user, TENANT_ID, "hash-abc123", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));
            authTokenRepository.saveAndFlush(token);

            final var result = authTokenRepository.findValidByHashAndType(
                    "hash-abc123", AuthToken.Type.INVITE, Instant.now());

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("findValidByHashAndType does not find an expired token")
        void findValidByHashAndTypeExcludesExpiredToken() {
            final User user = persistUser("expired@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken token = AuthToken.create(
                    user, TENANT_ID, "hash-expired", AuthToken.Type.PASSWORD_RESET,
                    Instant.now().minusSeconds(3600)); // already expired
            authTokenRepository.saveAndFlush(token);

            final var result = authTokenRepository.findValidByHashAndType(
                    "hash-expired", AuthToken.Type.PASSWORD_RESET, Instant.now());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findValidByHashAndType does not find an already-used token")
        void findValidByHashAndTypeExcludesUsedToken() {
            final User user = persistUser("used@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken token = AuthToken.forTesting(
                    user, TENANT_ID, "hash-used", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), Instant.now()); // usedAt set
            authTokenRepository.saveAndFlush(token);

            final var result = authTokenRepository.findValidByHashAndType(
                    "hash-used", AuthToken.Type.INVITE, Instant.now());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findValidByUserIdAndType finds only valid tokens for that user and type")
        void findValidByUserIdAndTypeFiltersCorrectly() {
            final User user = persistUser("multi-token@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken validInvite = AuthToken.create(
                    user, TENANT_ID, "hash-1", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));
            final AuthToken expiredInvite = AuthToken.create(
                    user, TENANT_ID, "hash-2", AuthToken.Type.INVITE,
                    Instant.now().minusSeconds(3600));
            final AuthToken validResetToken = AuthToken.create(
                    user, TENANT_ID, "hash-3", AuthToken.Type.PASSWORD_RESET,
                    Instant.now().plusSeconds(3600));
            authTokenRepository.saveAndFlush(validInvite);
            authTokenRepository.saveAndFlush(expiredInvite);
            authTokenRepository.saveAndFlush(validResetToken);

            final List<AuthToken> result = authTokenRepository.findValidByUserIdAndType(
                    user.getId(), AuthToken.Type.INVITE, Instant.now());

            assertThat(result).extracting(AuthToken::getTokenHash)
                    .containsExactly("hash-1");
        }

        /**
         * Regression coverage for the {@code @Modifying} bulk-delete
         * query — verifies it removes exactly the expired/used rows and
         * leaves valid ones untouched, not just that it runs without
         * throwing.
         */
        @Test
        @DisplayName("deleteExpiredAndUsed removes expired and used tokens, keeps valid ones")
        void deleteExpiredAndUsedRemovesOnlyStaleTokens() {
            final User user = persistUser("cleanup@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken expired = AuthToken.create(
                    user, TENANT_ID, "hash-expired-cleanup", AuthToken.Type.INVITE,
                    Instant.now().minusSeconds(3600));
            final AuthToken used = AuthToken.forTesting(
                    user, TENANT_ID, "hash-used-cleanup", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), Instant.now());
            final AuthToken valid = AuthToken.create(
                    user, TENANT_ID, "hash-valid-cleanup", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));
            authTokenRepository.saveAndFlush(expired);
            authTokenRepository.saveAndFlush(used);
            authTokenRepository.saveAndFlush(valid);

            final int deleted = authTokenRepository.deleteExpiredAndUsed(Instant.now());

            assertThat(deleted).isEqualTo(2);
            assertThat(authTokenRepository.findById(valid.getId())).isPresent();
            assertThat(authTokenRepository.findById(expired.getId())).isEmpty();
            assertThat(authTokenRepository.findById(used.getId())).isEmpty();
        }

        @Test
        @DisplayName("invalidateAllRefreshTokens invalidates every valid REFRESH " +
                "token for the user, leaves other types and other users alone")
        void invalidateAllRefreshTokensInvalidatesOnlyThatUsersRefreshTokens() {
            final User user = persistUser("logout-all@example.com", List.of("ROLE_RESPONDER"));
            final User otherUser = persistUser("other-user@example.com", List.of("ROLE_RESPONDER"));
            final AuthToken refresh1 = AuthToken.create(
                    user, TENANT_ID, "hash-refresh-1", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600));
            final AuthToken refresh2 = AuthToken.create(
                    user, TENANT_ID, "hash-refresh-2", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600));
            final AuthToken inviteToken = AuthToken.create(
                    user, TENANT_ID, "hash-invite-untouched", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));
            final AuthToken otherUsersRefresh = AuthToken.create(
                    otherUser, TENANT_ID, "hash-other-user-refresh", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600));
            authTokenRepository.saveAndFlush(refresh1);
            authTokenRepository.saveAndFlush(refresh2);
            authTokenRepository.saveAndFlush(inviteToken);
            authTokenRepository.saveAndFlush(otherUsersRefresh);

            final int invalidated = authTokenRepository.invalidateAllRefreshTokens(
                    user.getId(), Instant.now());

            assertThat(invalidated).isEqualTo(2);
            assertThat(authTokenRepository.findById(refresh1.getId())
                    .orElseThrow().isUsed()).isTrue();
            assertThat(authTokenRepository.findById(refresh2.getId())
                    .orElseThrow().isUsed()).isTrue();
            assertThat(authTokenRepository.findById(inviteToken.getId())
                    .orElseThrow().isUsed())
                    .as("non-REFRESH tokens must be untouched")
                    .isFalse();
            assertThat(authTokenRepository.findById(otherUsersRefresh.getId())
                    .orElseThrow().isUsed())
                    .as("a different user's REFRESH token must be untouched")
                    .isFalse();
        }

        @Test
        @DisplayName("invalidateRefreshTokenForSession invalidates only the " +
                "matching session, leaves other sessions for the same user alone")
        void invalidateRefreshTokenForSessionInvalidatesOnlyThatSession() {
            final User user = persistUser("logout-one-session@example.com",
                    List.of("ROLE_RESPONDER"));
            final UUID targetSession = UUID.randomUUID();
            final UUID otherSession = UUID.randomUUID();
            final AuthToken targetToken = AuthToken.create(
                    user, TENANT_ID, "hash-target-session", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600), targetSession);
            final AuthToken otherToken = AuthToken.create(
                    user, TENANT_ID, "hash-other-session", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600), otherSession);
            authTokenRepository.saveAndFlush(targetToken);
            authTokenRepository.saveAndFlush(otherToken);

            final int invalidated = authTokenRepository.invalidateRefreshTokenForSession(
                    user.getId(), targetSession, Instant.now());

            assertThat(invalidated).isEqualTo(1);
            assertThat(authTokenRepository.findById(targetToken.getId())
                    .orElseThrow().isUsed()).isTrue();
            assertThat(authTokenRepository.findById(otherToken.getId())
                    .orElseThrow().isUsed())
                    .as("a different session's token must be untouched")
                    .isFalse();
        }

        /**
         * The actual regression coverage for the NULL-safety concern
         * documented on {@code invalidateAllRefreshTokensExceptSession}'s
         * own Javadoc — the same class of gap already found and fixed in
         * oncall-service's own schedule-overlap query. A plain
         * {@code t.sessionId <> :sessionId} would evaluate to UNKNOWN
         * (not TRUE) for a row where {@code session_id} is NULL under
         * SQL's three-valued logic, silently exempting that row from
         * ever being invalidated by this "invalidate everything except"
         * query. Only a real database can catch this — Mockito has no
         * SQL NULL semantics to get wrong in the first place.
         */
        @Test
        @DisplayName("invalidateAllRefreshTokensExceptSession invalidates a token " +
                "with a NULL sessionId too — NULL-safety regression coverage")
        void invalidateAllRefreshTokensExceptSessionHandlesNullSessionIdCorrectly() {
            final User user = persistUser("change-password@example.com",
                    List.of("ROLE_RESPONDER"));
            final UUID currentSession = UUID.randomUUID();
            final UUID otherSession = UUID.randomUUID();
            final AuthToken currentSessionToken = AuthToken.create(
                    user, TENANT_ID, "hash-current-session", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600), currentSession);
            final AuthToken otherSessionToken = AuthToken.create(
                    user, TENANT_ID, "hash-other-session-except", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600), otherSession);
            // A token with no sessionId at all — e.g. issued before this
            // claim existed, or via a code path that forgot to set it.
            final AuthToken noSessionToken = AuthToken.create(
                    user, TENANT_ID, "hash-null-session", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(3600), null);
            authTokenRepository.saveAndFlush(currentSessionToken);
            authTokenRepository.saveAndFlush(otherSessionToken);
            authTokenRepository.saveAndFlush(noSessionToken);

            final int invalidated = authTokenRepository
                    .invalidateAllRefreshTokensExceptSession(
                            user.getId(), currentSession, Instant.now());

            assertThat(invalidated).isEqualTo(2);
            assertThat(authTokenRepository.findById(currentSessionToken.getId())
                    .orElseThrow().isUsed())
                    .as("the current session's own token must survive")
                    .isFalse();
            assertThat(authTokenRepository.findById(otherSessionToken.getId())
                    .orElseThrow().isUsed())
                    .as("a genuinely different session must be invalidated")
                    .isTrue();
            assertThat(authTokenRepository.findById(noSessionToken.getId())
                    .orElseThrow().isUsed())
                    .as("a NULL-sessionId token must NOT be silently exempted")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("ApiKeyRepository — real JPQL")
    class ApiKeyRepositoryTests {

        /**
         * The most security-critical query in this whole file — runs on
         * every single API-key-authenticated request.
         */
        @Test
        @DisplayName("findActiveByHash finds a non-revoked key")
        void findActiveByHashFindsActiveKey() {
            final ApiKey key = ApiKey.createTenant(
                    TENANT_ID, "CI key", "hash-active-key", "ak_live_",
                    List.of("incidents:read"), Instant.now().plusSeconds(3600 * 24 * 365));
            apiKeyRepository.saveAndFlush(key);

            final var result = apiKeyRepository.findActiveByHash("hash-active-key");

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("findActiveByHash does not find a revoked key")
        void findActiveByHashExcludesRevokedKey() {
            final ApiKey key = ApiKey.createTenant(
                    TENANT_ID, "Revoked key", "hash-revoked-key", "ak_live_",
                    List.of("incidents:read"), Instant.now().plusSeconds(3600 * 24 * 365));
            key.revoke();
            apiKeyRepository.saveAndFlush(key);

            final var result = apiKeyRepository.findActiveByHash("hash-revoked-key");

            assertThat(result).isEmpty();
        }

        /**
         * Backlog #0-16: last_used_at is written by one conditional UPDATE, at
         * most once per interval. The condition is in SQL, so only a real
         * database proves it.
         */
        @Test
        @DisplayName("touchLastUsedAt writes when unset or older than the threshold, not when recent")
        void touchLastUsedAtIsConditional() {
            final ApiKey key = apiKeyRepository.saveAndFlush(ApiKey.createTenant(
                    TENANT_ID, "Usage key", "hash-usage-key", "ak_live_",
                    List.of("alerts:ingest"), null));
            final Instant t0 = Instant.parse("2026-09-24T10:00:00Z");

            assertThat(apiKeyRepository.touchLastUsedAt(key.getId(), t0, t0.minusSeconds(300)))
                    .as("first use, last_used_at is NULL").isEqualTo(1);

            final Instant t1 = t0.plusSeconds(60);
            assertThat(apiKeyRepository.touchLastUsedAt(key.getId(), t1, t1.minusSeconds(300)))
                    .as("used 60 s ago, inside the 5 min interval").isZero();

            final Instant t2 = t0.plusSeconds(301);
            assertThat(apiKeyRepository.touchLastUsedAt(key.getId(), t2, t2.minusSeconds(300)))
                    .as("used 301 s ago, outside the interval").isEqualTo(1);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_used_at FROM api_keys WHERE id = ?",
                    java.sql.Timestamp.class, key.getId()).toInstant()).isEqualTo(t2);
        }
    }
}
