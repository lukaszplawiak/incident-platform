package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.SlackWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlackWorkspaceRepository extends JpaRepository<SlackWorkspace, UUID> {

    /**
     * The tenant's one active Slack workspace, if any — enforced by the
     * partial unique index on {@code (tenant_id) WHERE revoked_at IS NULL}
     * (migration V17).
     */
    Optional<SlackWorkspace> findActiveByTenantIdAndRevokedAtIsNull(String tenantId);

    Optional<SlackWorkspace> findByIdAndTenantId(UUID id, String tenantId);
}
