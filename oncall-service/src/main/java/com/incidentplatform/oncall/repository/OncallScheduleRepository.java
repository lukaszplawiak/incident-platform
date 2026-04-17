package com.incidentplatform.oncall.repository;

import com.incidentplatform.oncall.domain.OncallSchedule;
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

    List<OncallSchedule> findByTenantIdOrderByStartsAtDesc(String tenantId);

    Optional<OncallSchedule> findByIdAndTenantId(UUID id, String tenantId);

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