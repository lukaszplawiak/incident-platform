package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Integration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    /**
     * Lists all active integrations for a tenant (management UI).
     */
    @Query("SELECT i FROM Integration i " +
            "LEFT JOIN FETCH i.team " +
            "WHERE i.tenantId = :tenantId AND i.revokedAt IS NULL " +
            "ORDER BY i.createdAt DESC")
    List<Integration> findActiveByTenantId(@Param("tenantId") String tenantId);

    Optional<Integration> findByIdAndTenantId(UUID id, String tenantId);

    boolean existsByNameAndTenantId(String name, String tenantId);
}