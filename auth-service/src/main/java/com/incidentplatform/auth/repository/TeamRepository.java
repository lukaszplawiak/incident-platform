package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Team} entities.
 *
 * <h2>Soft-delete filtering</h2>
 * {@code @SQLRestriction("deleted_at IS NULL")} on {@link Team} ensures all
 * queries automatically exclude soft-deleted teams.
 */
@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByTenantId(String tenantId);

    Optional<Team> findByIdAndTenantId(UUID id, String tenantId);

    boolean existsByNameAndTenantId(String name, String tenantId);
}