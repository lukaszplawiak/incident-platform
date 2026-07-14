package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Team} entities.
 *
 * <h2>Archiving filter</h2>
 * {@code @SQLRestriction("archived_at IS NULL")} on {@link Team} excludes
 * archived teams from all Hibernate queries automatically.
 *
 * <h2>Bypassing for restore</h2>
 * {@link #findArchivedByIdAndTenantId} uses a native query to bypass the
 * restriction — needed by {@code TeamService.restoreTeam()}.
 */
@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByTenantId(String tenantId);

    Optional<Team> findByIdAndTenantId(UUID id, String tenantId);

    boolean existsByNameAndTenantId(String name, String tenantId);

    /**
     * Finds an archived team by id and tenant.
     * Bypasses {@code @SQLRestriction} via native query.
     * Used exclusively by {@code TeamService.restoreTeam()}.
     */
    @Query(value = "SELECT * FROM teams WHERE id = :id AND tenant_id = :tenantId " +
            "AND archived_at IS NOT NULL",
            nativeQuery = true)
    Optional<Team> findArchivedByIdAndTenantId(
            @Param("id") UUID id,
            @Param("tenantId") String tenantId);
}