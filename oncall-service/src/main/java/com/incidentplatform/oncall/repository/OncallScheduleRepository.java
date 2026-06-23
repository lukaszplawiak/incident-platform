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

    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            """)
    boolean existsOverlappingForCreate(
            @Param("tenantId") String tenantId,
            @Param("role") String role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt);

    @Query("""
            SELECT COUNT(s) > 0 FROM OncallSchedule s
            WHERE s.tenantId = :tenantId
            AND s.role = :role
            AND s.startsAt < :endsAt
            AND s.endsAt > :startsAt
            AND s.id != :excludeId
            """)
    boolean existsOverlapping(
            @Param("tenantId") String tenantId,
            @Param("role") String role,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("excludeId") UUID excludeId);
}