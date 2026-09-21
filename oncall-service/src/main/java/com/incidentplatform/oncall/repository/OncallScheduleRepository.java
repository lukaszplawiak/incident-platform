package com.incidentplatform.oncall.repository;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.domain.OncallScheduleStatus;
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

    /**
     * Fixed: {@code role} was declared {@code String} here, but
     * {@code OncallSchedule.role} is mapped as the {@code OncallRole}
     * enum. Same class of bug, same fix, as documented in detail on
     * {@link #existsOverlappingForCreate} — this method has the identical
     * {@code s.role = :role} JPQL pattern, so it was equally broken: every
     * call to {@code OncallScheduleService.getCurrentOncall} (i.e. every
     * {@code GET /current?role=...} request without a {@code teamId} —
     * one of the two most-used endpoints in this service, called by both
     * notification-service and escalation-service) would have thrown
     * {@code InvalidDataAccessApiUsageException}.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.startsAt DESC
            """)
    Optional<OncallSchedule> findCurrentOncallByRole(
            @Param("tenantId") String tenantId,
            @Param("role") OncallRole role,
            @Param("now") Instant now);

    /**
     * Finds the current on-call person for a specific team and role.
     *
     * <p>This is the primary query used by the EscalationScheduler:
     * "who is PRIMARY on-call for backend-team right now?".
     *
     * <p>Called via HTTP from escalation-service with circuit breaker.
     * Covered by index: idx_oncall_schedules_team_role_time.
     *
     * <p>Fixed: same {@code role} type bug as {@link #findCurrentOncallByRole}
     * — see its Javadoc.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.teamId = :teamId
            AND s.role = :role
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.startsAt DESC
            """)
    Optional<OncallSchedule> findCurrentOncallByTeamAndRole(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") OncallRole role,
            @Param("now") Instant now);

    /**
     * Returns all current on-call entries for a specific team
     * (all roles: PRIMARY, SECONDARY, MANAGER).
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.teamId = :teamId
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
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
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.role ASC
            """)
    List<OncallSchedule> findAllCurrentOncall(
            @Param("tenantId") String tenantId,
            @Param("now") Instant now);

    /**
     * Fixed: replaces findByTenantIdOrderByStartsAtDesc, which had no way
     * to filter by status — see OncallScheduleController.getSchedules's
     * own comment for the pagination-degradation problem this caused as
     * SUPERSEDED/CANCELLED rows (kept indefinitely for history) piled up
     * alongside ACTIVE ones. status is nullable — null means no filter,
     * matching this file's own established (:teamId IS NULL OR ...)
     * pattern for an optional parameter (see existsOverlappingForCreate),
     * and IncidentSpecification's identical convention in incident-service.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND (:status IS NULL OR s.status = :status)
            ORDER BY s.startsAt DESC
            """)
    Page<OncallSchedule> findByTenantIdAndOptionalStatus(
            @Param("tenantId") String tenantId,
            @Param("status") OncallScheduleStatus status,
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
     * Finds the ACTIVE schedule entries a user is on right now, within one
     * tenant. Added for backlog #0-1: an escalation notification has to reach
     * the person escalation-service chose ({@code escalateTo}), not the
     * PRIMARY, so notification-service looks that person's contact details
     * up by user id.
     *
     * <p>Both {@code tenantId} and {@code userId} are mandatory and are
     * matched together. {@code escalateTo} is an unverified id read from a
     * Kafka payload; a lookup by user id alone could send one tenant's
     * incident to another tenant's user.
     *
     * <p>Returns a {@link List}, not an {@code Optional}, because one user can
     * legitimately hold several concurrent entries (for example PRIMARY for
     * team A and SECONDARY for team B): the exclusion constraint of V4/V5 only
     * forbids overlaps per tenant, team and role. An {@code Optional} would
     * throw {@code IncorrectResultSizeDataAccessException} for such a user.
     * The order is deterministic: most recently started first, then by id.
     *
     * <p>"Current" is the same predicate as {@link #findCurrentOncallByRole}:
     * status ACTIVE and {@code startsAt <= now < endsAt}. Covered by
     * {@code idx_oncall_schedules_user (tenant_id, user_id)}; the remaining
     * filters run over the few rows one user has.
     */
    @Query("""
            SELECT s FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.userId = :userId
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND s.startsAt <= :now
            AND s.endsAt > :now
            ORDER BY s.startsAt DESC, s.id
            """)
    List<OncallSchedule> findCurrentByTenantIdAndUserId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("now") Instant now);

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
     *
     * <p>Fixed separately: {@code role} was declared {@code String} here,
     * but {@code OncallSchedule.role} is mapped as the {@code OncallRole}
     * enum — Hibernate infers a JPQL parameter's expected type from its
     * usage in the query ({@code s.role = :role}), and strictly rejects a
     * {@code String} bound to a slot it resolved as {@code OncallRole}.
     * This was a genuine, live production bug: {@code
     * OncallScheduleService.create()} called this method with the raw
     * {@code String} from the request, before converting it to
     * {@code OncallRole} — meaning every real call to this method (i.e.
     * every attempt to create an on-call schedule) would have thrown
     * {@code InvalidDataAccessApiUsageException}. Never caught before
     * because every existing test mocked this repository — Mockito
     * doesn't perform Hibernate's runtime JPQL type checking, so the
     * mismatch was invisible until a real-Postgres integration test
     * exercised this method for the first time.
     * <p>Fixed (backlog #43): scoped to {@code status = ACTIVE} —
     * without this, a superseded row (kept for history, not deleted —
     * see {@code OncallScheduleStatus}'s Javadoc) would still count as a
     * conflict against a brand-new schedule created for the exact same
     * historical window, even though it no longer represents anything
     * currently in effect.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND (s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS NULL))
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            """)
    boolean existsOverlappingForCreate(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") OncallRole role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt);

    /**
     * Same teamId-scoping fix, same {@code role} type fix, and same
     * {@code status = ACTIVE} scoping as {@link #existsOverlappingForCreate}
     * — see its Javadoc for all three.
     *
     * <p>{@code excludeId} — previously unused dead code (no caller
     * existed for it) until backlog #43's supersede pattern:
     * {@code OncallScheduleService.supersede(...)} passes the old row's
     * id here specifically so it can check "does the proposed replacement
     * window overlap with anything else ACTIVE" without the old row (which,
     * at the moment this check runs, is still ACTIVE — it isn't marked
     * SUPERSEDED until immediately afterward, in the same transaction)
     * counting as a conflict against itself.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.status = com.incidentplatform.oncall.domain.OncallScheduleStatus.ACTIVE
            AND (s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS NULL))
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            AND s.id != :excludeId
            """)
    boolean existsOverlapping(
            @Param("tenantId") String tenantId,
            @Param("teamId") UUID teamId,
            @Param("role") OncallRole role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("excludeId") UUID excludeId);
}