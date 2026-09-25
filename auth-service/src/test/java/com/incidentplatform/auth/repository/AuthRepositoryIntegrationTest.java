package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.SlackWorkspace;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.UserRole;
import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.ResetPasswordRequest;
import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.auth.service.AesEncryptionService;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.auth.service.InviteService;
import com.incidentplatform.auth.service.MfaService;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.TotpService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired private SlackWorkspaceRepository slackWorkspaceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthEmailOutboxRepository authEmailOutboxRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private InviteService inviteService;
    @Autowired private PasswordService passwordService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MfaService mfaService;
    @Autowired private TotpService totpService;
    @Autowired @Qualifier("mfaEncryptionService") private AesEncryptionService mfaEncryptionService;

    // The service-level tests (backlog #0-50) publish audit events; this
    // context has no Kafka, and what is published is not under test here.
    @MockitoBean private AuditEventPublisher auditEventPublisher;
    // MFA lockout state lives in Redis, which this context does not have;
    // an unconfigured mock means "not locked", and lockout is not under test.
    @MockitoBean private BruteForceProtectionService bruteForceProtectionService;

    private static final String TENANT_ID = "test-tenant";

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
                    user, TENANT_ID, UUID.randomUUID());
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
                    user, TENANT_ID, UUID.randomUUID());
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

        private AuthEmailOutbox persistInvite(User user, String hash) {
            final AuthToken token = authTokenRepository.saveAndFlush(AuthToken.create(
                    user, TENANT_ID, hash, AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600)));
            return authEmailOutboxRepository.saveAndFlush(
                    AuthEmailOutbox.invitePending(user, token, "raw-" + hash));
        }

        @Test
        @DisplayName("returns the newest of several entries")
        void returnsNewestOfSeveral() {
            final User user = persistUser("outbox@example.com", List.of("ROLE_RESPONDER"));
            // At most one PENDING/FAILED entry per user and type (partial unique
            // index, V7), so the older one is done, as it is before a resend.
            final AuthEmailOutbox older = persistInvite(user, "hash-outbox-1");
            older.markPermanentlyFailed("smtp down");
            authEmailOutboxRepository.saveAndFlush(older);
            final AuthEmailOutbox newest = persistInvite(user, "hash-outbox-2");

            assertThat(authEmailOutboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    user.getId(), AuthEmailType.INVITE))
                    .map(AuthEmailOutbox::getId).contains(newest.getId());
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
