package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        "mfa.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
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
    @Autowired private JdbcTemplate jdbcTemplate;

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
                    "users", "teams", "team_members", "auth_tokens", "api_keys");

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
    }
}