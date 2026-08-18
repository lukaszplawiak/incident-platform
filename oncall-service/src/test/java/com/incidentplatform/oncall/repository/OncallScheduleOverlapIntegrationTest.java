package com.incidentplatform.oncall.repository;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres integration tests for {@link OncallScheduleRepository} —
 * backlog item #22. Before this file, {@code oncall-service} had zero DB
 * integration tests of any kind; every existing test (including
 * {@code OncallScheduleServiceTest}) mocks the repository. Two things
 * specifically motivated closing this gap, both from the same earlier
 * review pass and both impossible to verify against a mock:
 *
 * <ol>
 *   <li>The {@code excl_oncall_schedule_overlap} {@code EXCLUDE USING gist}
 *       constraint (V4 migration) — added as the real, atomic guarantee
 *       behind {@code OncallScheduleService.create}'s application-level
 *       overlap check, for the race window between that check and
 *       {@code save()}. A mocked repository can simulate
 *       {@code DataIntegrityViolationException} being thrown, but cannot
 *       prove the actual SQL constraint — its exact column list, its
 *       {@code COALESCE} NULL-handling for tenant-wide schedules, its
 *       {@code tstzrange(...) WITH &&} overlap operator — is correct.</li>
 *   <li>{@code existsOverlappingForCreate}'s NULL-safe {@code teamId}
 *       scoping (fixed in the same review pass: previously ignored
 *       {@code teamId} entirely, incorrectly blocking unrelated teams
 *       from having overlapping schedules). A mocked-repository unit
 *       test can prove the service passes the right arguments to the
 *       query method; it cannot prove the JPQL itself — particularly the
 *       {@code (s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS
 *       NULL))} NULL-safe comparison — evaluates correctly against real
 *       data.</li>
 * </ol>
 *
 * <p>Also serves as the module's Flyway verification: if any of the four
 * migrations failed to apply cleanly against a fresh database, the
 * {@code @SpringBootTest} context itself would fail to start, failing
 * every test in this class immediately.
 */
@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding"
})
@DisplayName("OncallScheduleRepository — real Postgres integration")
class OncallScheduleOverlapIntegrationTest {

    // postgres:16-alpine — same image version used in docker/docker-compose.yml,
    // for consistency between what's tested here and what actually runs.
    @Container
    @ServiceConnection
    // withReuse(true): once enabled locally (~/.testcontainers.properties,
    // testcontainers.reuse.enable=true), this container survives between
    // local test runs instead of restarting from scratch every time — pure
    // dev-loop speedup, no effect on CI (reuse is opt-in and typically off
    // there, so CI behavior is unchanged). Added for consistency with
    // AuthRepositoryIntegrationTest (auth-service, backlog #34), which
    // established this as the standard for every Testcontainers-based
    // integration test in this codebase going forward.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withReuse(true);

    @Autowired
    private OncallScheduleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TENANT_ID = "test-tenant";
    private static final Instant STARTS_AT = Instant.parse("2026-06-01T09:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-06-08T09:00:00Z");

    private OncallSchedule buildSchedule(UUID teamId, OncallRole role,
                                         Instant startsAt, Instant endsAt) {
        return OncallSchedule.create(
                TENANT_ID, teamId, "user-1", "Jan Kowalski", "jan@example.com",
                "+48100200300", "U0123456789", role, startsAt, endsAt,
                "Integration test schedule");
    }

    @Nested
    @DisplayName("Flyway migrations")
    class Migrations {

        /**
         * The @SpringBootTest context starting up at all already proves the
         * four migrations applied without error — this adds an explicit,
         * positive assertion that the specific constraint from V4 exists,
         * rather than relying solely on "the context loaded" as an implicit
         * signal.
         */
        @Test
        @DisplayName("V4 migration's EXCLUDE constraint exists on oncall_schedules")
        void excludeConstraintExists() {
            final Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conname = 'excl_oncall_schedule_overlap'
                    """, Integer.class);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("btree_gist extension is installed")
        void btreeGistExtensionInstalled() {
            final Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'
                    """, Integer.class);

            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("excl_oncall_schedule_overlap constraint — real DB enforcement")
    class ExcludeConstraint {

        @Test
        @DisplayName("rejects a second overlapping schedule for the same tenant+team+role")
        void rejectsOverlapForSameTeamAndRole() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final OncallSchedule overlapping = buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT.plusSeconds(3600), ENDS_AT.plusSeconds(3600));

            assertThatThrownBy(() -> repository.saveAndFlush(overlapping))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * The actual regression test for the point-2 fix (missing teamId
         * scoping): two DIFFERENT teams with overlapping windows and the
         * same role must NOT conflict — this is exactly the scenario that
         * was previously, incorrectly, always blocked.
         */
        @Test
        @DisplayName("allows overlapping schedules for the same role across different teams")
        void allowsOverlapAcrossDifferentTeams() {
            final UUID teamA = UUID.randomUUID();
            final UUID teamB = UUID.randomUUID();

            repository.saveAndFlush(buildSchedule(teamA, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            // Same role, fully overlapping window, DIFFERENT team — must succeed.
            repository.saveAndFlush(buildSchedule(teamB, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            assertThat(repository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects overlap between two tenant-wide (null team) schedules")
        void rejectsOverlapForTwoNullTeamSchedules() {
            repository.saveAndFlush(buildSchedule(null, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final OncallSchedule overlapping = buildSchedule(null, OncallRole.PRIMARY,
                    STARTS_AT.plusSeconds(3600), ENDS_AT.plusSeconds(3600));

            assertThatThrownBy(() -> repository.saveAndFlush(overlapping))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("allows a non-overlapping schedule for the same team and role")
        void allowsNonOverlappingScheduleForSameTeam() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            // Starts exactly when the first one ends — adjacent, not overlapping.
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    ENDS_AT, ENDS_AT.plusSeconds(3600 * 24 * 7)));

            assertThat(repository.count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("existsOverlappingForCreate — real JPQL against real data")
    class ExistsOverlappingForCreate {

        @Test
        @DisplayName("returns false for overlapping windows on different teams — " +
                "the actual regression test for the point-2 fix")
        void returnsFalseForDifferentTeams() {
            final UUID teamA = UUID.randomUUID();
            final UUID teamB = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamA, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, teamB, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);

            assertThat(overlaps).isFalse();
        }

        @Test
        @DisplayName("returns true for overlapping windows on the same team")
        void returnsTrueForSameTeam() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);

            assertThat(overlaps).isTrue();
        }

        @Test
        @DisplayName("returns true for overlapping windows when both are tenant-wide (null team) — " +
                "the NULL-safe comparison regression test")
        void returnsTrueForBothNullTeam() {
            repository.saveAndFlush(buildSchedule(null, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, null, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);

            assertThat(overlaps).isTrue();
        }

        @Test
        @DisplayName("returns false when one schedule is tenant-wide and the other is team-specific — " +
                "NULL is its own scope, not a wildcard")
        void returnsFalseForNullTeamVsSpecificTeam() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(null, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);

            assertThat(overlaps).isFalse();
        }

        @Test
        @DisplayName("returns false for non-overlapping (adjacent) time windows on the same team")
        void returnsFalseForNonOverlappingWindows() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, teamId, OncallRole.PRIMARY,
                    ENDS_AT, ENDS_AT.plusSeconds(3600 * 24 * 7));

            assertThat(overlaps).isFalse();
        }

        @Test
        @DisplayName("returns false for a different role on the same team, same window")
        void returnsFalseForDifferentRole() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlappingForCreate(
                    TENANT_ID, teamId, OncallRole.SECONDARY, STARTS_AT, ENDS_AT);

            assertThat(overlaps).isFalse();
        }
    }

    /**
     * Coverage for {@code findCurrentOncallByRole}/
     * {@code findCurrentOncallByTeamAndRole} — added after this same
     * integration suite's {@code existsOverlappingForCreate} tests
     * revealed a {@code role} parameter type bug ({@code String} declared
     * vs. {@code OncallRole} required by Hibernate), which turned out to
     * affect these two methods identically. Both are the query behind
     * {@code OncallScheduleService.getCurrentOncall}/
     * {@code getCurrentOncallForTeam} — the two most-used lookups in this
     * service, called by notification-service and escalation-service —
     * so this is arguably the most important coverage in this file.
     */
    @Nested
    @DisplayName("findCurrentOncallByRole / findCurrentOncallByTeamAndRole — real JPQL")
    class FindCurrentOncall {

        @Test
        @DisplayName("findCurrentOncallByRole finds an active schedule for the current time")
        void findCurrentOncallByRoleFindsActiveSchedule() {
            final Instant now = Instant.now();
            repository.saveAndFlush(buildSchedule(null, OncallRole.PRIMARY,
                    now.minusSeconds(3600), now.plusSeconds(3600)));

            final var result = repository.findCurrentOncallByRole(
                    TENANT_ID, OncallRole.PRIMARY, now);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("findCurrentOncallByRole returns empty when no active schedule exists")
        void findCurrentOncallByRoleReturnsEmptyWhenNoneActive() {
            final Instant now = Instant.now();
            // Schedule exists but is in the future — not active "now".
            repository.saveAndFlush(buildSchedule(null, OncallRole.PRIMARY,
                    now.plusSeconds(3600), now.plusSeconds(7200)));

            final var result = repository.findCurrentOncallByRole(
                    TENANT_ID, OncallRole.PRIMARY, now);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findCurrentOncallByTeamAndRole finds an active schedule for the given team")
        void findCurrentOncallByTeamAndRoleFindsActiveSchedule() {
            final Instant now = Instant.now();
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
                    now.minusSeconds(3600), now.plusSeconds(3600)));

            final var result = repository.findCurrentOncallByTeamAndRole(
                    TENANT_ID, teamId, OncallRole.PRIMARY, now);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("findCurrentOncallByTeamAndRole does not match a different team's schedule")
        void findCurrentOncallByTeamAndRoleDoesNotMatchDifferentTeam() {
            final Instant now = Instant.now();
            repository.saveAndFlush(buildSchedule(UUID.randomUUID(), OncallRole.PRIMARY,
                    now.minusSeconds(3600), now.plusSeconds(3600)));

            final var result = repository.findCurrentOncallByTeamAndRole(
                    TENANT_ID, UUID.randomUUID(), OncallRole.PRIMARY, now);

            assertThat(result).isEmpty();
        }
    }

    /**
     * Real-Postgres coverage for backlog #43's supersede pattern — the
     * one part of this feature that genuinely cannot be verified against
     * a mocked repository: whether the {@code excl_oncall_schedule_overlap}
     * constraint's {@code WHERE (status = 'ACTIVE')} partial-index syntax
     * (migration V5) is actually valid PostgreSQL and behaves as intended.
     * If this migration's SQL were subtly wrong (a typo in the WHERE
     * clause, an unsupported partial-EXCLUDE combination), the
     * {@code @SpringBootTest} context itself would fail to start — but
     * that alone wouldn't prove the constraint does what it's SUPPOSED to
     * (scope correctly), only that it applied without a syntax error.
     */
    @Nested
    @DisplayName("supersede pattern — excl_oncall_schedule_overlap scoped to ACTIVE (backlog #43)")
    class SupersedePattern {

        /**
         * The core guarantee this whole pattern exists for: a SUPERSEDED
         * row (kept for history, not deleted) must never block a new
         * ACTIVE row from occupying the exact same window it used to
         * occupy — otherwise OncallScheduleService.supersede's own
         * old.markSuperseded() + save(replacement) sequence, within one
         * transaction, would fail every single time (the two rows always
         * overlap by construction — the whole point of an edit is
         * "replace this window").
         */
        @Test
        @DisplayName("a SUPERSEDED row does not conflict with an overlapping ACTIVE replacement")
        void supersededRowDoesNotConflictWithReplacement() {
            final UUID teamId = UUID.randomUUID();
            final OncallSchedule original = buildSchedule(
                    teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);
            repository.saveAndFlush(original);

            original.markSuperseded();
            final OncallSchedule replacement = OncallSchedule.createSuperseding(
                    original.getId(), TENANT_ID, teamId, "user-2", "Anna Nowak",
                    "anna@example.com", "+48100200301", "U9876543210",
                    OncallRole.PRIMARY, STARTS_AT, ENDS_AT, "Replacement");

            // Same identical window as the original — this MUST succeed
            // despite the physical row still being present in the table.
            repository.saveAndFlush(original);
            repository.saveAndFlush(replacement);

            assertThat(repository.count()).isEqualTo(2);
        }

/**
 * Sanity check that scoping the constraint to ACTIVE didn't
 * accidentally disable it altogether — two genuinely conflicting
 * ACTIVE rows must still be rejected, exactly as before V5.
 */
@Test
@DisplayName("two ACTIVE rows with overlapping windows still conflict")
void twoActiveRowsStillConflict() {
    final UUID teamId = UUID.randomUUID();
    repository.saveAndFlush(buildSchedule(teamId, OncallRole.PRIMARY,
            STARTS_AT, ENDS_AT));

    final OncallSchedule overlapping = buildSchedule(teamId, OncallRole.PRIMARY,
            STARTS_AT.plusSeconds(3600), ENDS_AT.plusSeconds(3600));

    assertThatThrownBy(() -> repository.saveAndFlush(overlapping))
            .isInstanceOf(DataIntegrityViolationException.class);
}

        /**
         * Regression test for existsOverlapping's excludeId parameter —
         * previously dead code (backlog #43's Javadoc on that method) —
         * now genuinely exercised: the row being excluded must not count
         * as a conflict against itself.
         */
        @Test
        @DisplayName("existsOverlapping excludes the given id from its own conflict check")
        void existsOverlappingExcludesGivenId() {
            final UUID teamId = UUID.randomUUID();
            final OncallSchedule existing = buildSchedule(
                    teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);
            repository.saveAndFlush(existing);

            final boolean overlapsExcludingSelf = repository.existsOverlapping(
                    TENANT_ID, teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT,
                    existing.getId());

            assertThat(overlapsExcludingSelf).isFalse();
        }

        @Test
        @DisplayName("existsOverlapping still detects a conflict against a DIFFERENT row")
        void existsOverlappingDetectsConflictAgainstDifferentRow() {
            final UUID teamId = UUID.randomUUID();
            repository.saveAndFlush(buildSchedule(
                    teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT));

            final boolean overlaps = repository.existsOverlapping(
                    TENANT_ID, teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT,
                    UUID.randomUUID()); // excluding an unrelated, non-existent id

            assertThat(overlaps).isTrue();
        }

        @Test
        @DisplayName("findCurrentOncallByRole ignores a SUPERSEDED row even during its original window")
        void findCurrentOncallByRoleIgnoresSupersededRow() {
            final Instant now = Instant.now();
            final OncallSchedule original = buildSchedule(null, OncallRole.PRIMARY,
                    now.minusSeconds(3600), now.plusSeconds(3600));
            repository.saveAndFlush(original);

            original.markSuperseded();
            repository.saveAndFlush(original);

            final var result = repository.findCurrentOncallByRole(
                    TENANT_ID, OncallRole.PRIMARY, now);

            assertThat(result).isEmpty();
        }
    }

    /**
     * Real-Postgres coverage for backlog #44's soft-delete — verifies
     * migration V6's CHECK constraint accepts CANCELLED, and that the
     * excl_oncall_schedule_overlap constraint (already scoped to
     * status = ACTIVE since V5) correctly treats a CANCELLED row the
     * same way it already treats SUPERSEDED — excluded from conflict
     * checks, with no further migration needed for that part.
     */
    @Nested
    @DisplayName("soft-delete — CANCELLED status (backlog #44)")
    class CancelledStatus {

        @Test
        @DisplayName("a CANCELLED row does not conflict with an overlapping ACTIVE replacement")
        void cancelledRowDoesNotConflictWithNewActiveRow() {
            final UUID teamId = UUID.randomUUID();
            final OncallSchedule original = buildSchedule(
                    teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);
            repository.saveAndFlush(original);

            original.cancel();
            repository.saveAndFlush(original);

            // Same identical window as the cancelled entry — must succeed.
            final OncallSchedule replacement = buildSchedule(
                    teamId, OncallRole.PRIMARY, STARTS_AT, ENDS_AT);
            repository.saveAndFlush(replacement);

            assertThat(repository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("findCurrentOncallByRole ignores a CANCELLED row even during its original window")
        void findCurrentOncallByRoleIgnoresCancelledRow() {
            final Instant now = Instant.now();
            final OncallSchedule schedule = buildSchedule(null, OncallRole.PRIMARY,
                    now.minusSeconds(3600), now.plusSeconds(3600));
            repository.saveAndFlush(schedule);

            schedule.cancel();
            repository.saveAndFlush(schedule);

            final var result = repository.findCurrentOncallByRole(
                    TENANT_ID, OncallRole.PRIMARY, now);

            assertThat(result).isEmpty();
        }
    }
}