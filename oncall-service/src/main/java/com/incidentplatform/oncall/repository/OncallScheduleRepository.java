package com.incidentplatform.oncall.repository;

import com.incidentplatform.oncall.domain.OncallSchedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OncallScheduleRepository
        extends JpaRepository<OncallSchedule, UUID> {

    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.startsAt DESC
            """)
    Optional<OncallSchedule> findCurrentOncallByRole(
            @Param("tenantId") String tenantId,
            @Param("role") String role,
            @Param("now") Instant now);

    /**
     * Finds the current on-call person for a specific team and role.
     *
     * <p>This is the primary query used by the EscalationScheduler:
     * "who is PRIMARY on-call for backend-team right now?".
     *
     * <p>Called via HTTP from escalation-service with circuit breaker.
     * Covered by index: idx_oncall_schedules_team_role_time.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.teamId = :teamId
            AND s.role = :role
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.startsAt DESC
            """)
    Optional<OncallSchedule> findCurrentOncallByTeamAndRole(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") String role,
            @Param("now") Instant now);

    /**
     * Returns all current on-call entries for a specific team
     * (all roles: PRIMARY, SECONDARY, MANAGER).
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.teamId = :teamId
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.role ASC
            """)
    List<OncallSchedule> findAllCurrentOncallForTeam(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("now") Instant now);

    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.role ASC
            """)
    List<OncallSchedule> findAllCurrentOncall(
            @Param("tenantId") String tenantId,
            @Param("now") Instant now);

    /**
     * Returns a paginated list of all schedules for the given tenant,
     * ordered by most recent first. Replaces the previous unbounded
     * {@code List<OncallSchedule>} variant — see Problem 8 fix.
     */
    Page<OncallSchedule> findByTenantIdOrderByStartsAtDesc(String tenantId,
                                                           Pageable pageable);

    Optional<OncallSchedule> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * Finds the most recent on-call schedule entry for the given Slack user
     * within the specified tenant.
     *
     * <p>The {@code tenantId} filter is required for correct multi-tenant
     * isolation — {@code slackUserId} is only unique within a Slack workspace,
     * and two tenants sharing the same workspace would have colliding IDs
     * without this filter. The result is used by {@code notification-service}
     * to map a Slack ACK button click to the internal system user ID.
     *
     * <p>Covered by composite index
     * {@code idx_oncall_schedules_tenant_slack (tenant_id, slack_user_id,
     * starts_at DESC)} — see {@code V2__add_index_oncall_slack_user.sql}.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.slackUserId = :slackUserId
            ORDER BY s.startsAt DESC
            """)
    List<OncallSchedule> findByTenantIdAndSlackUserId(
            @Param("tenantId") String tenantId,
            @Param("slackUserId") String slackUserId);

    /**
     * Fixed: previously did not filter by {@code teamId} at all, only
     * {@code tenantId} + {@code role} — meaning two different teams in the
     * same tenant could never both have, say, a PRIMARY on-call at the
     * same time, even though they're unrelated teams and shouldn't
     * conflict. Two schedules only genuinely conflict when they're for
     * the same team (or both tenant-wide, {@code teamId IS NULL} — treated
     * as its own scope, not a wildcard matching every team).
     *
     * <p>{@code teamId} comparison is NULL-safe by necessity: SQL/JPQL
     * evaluates {@code NULL = NULL} as UNKNOWN, not {@code TRUE}, so a
     * naive {@code s.teamId = :teamId} would silently exclude every row
     * whenever {@code :teamId} is null — disabling the overlap check
     * entirely for tenant-wide schedules rather than scoping it correctly.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND (s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS NULL))
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            """)
    boolean existsOverlappingForCreate(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") String role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt);

    /** Same teamId-scoping fix as {@link #existsOverlappingForCreate} — see its Javadoc. */
    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND (s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS NULL))
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            AND s.id != :excludeId
            """)
    boolean existsOverlapping(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") String role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("excludeId") UUID excludeId);
}