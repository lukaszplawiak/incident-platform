package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndTenantId(String email, String tenantId);

    /**
     * Lists all users in a tenant — paginated.
     * Tenant isolation is enforced at query level, not in application code.
     */
    Page<User> findByTenantId(String tenantId, Pageable pageable);

    /**
     * Finds a specific user by id within a tenant.
     * Returns empty if the user exists but belongs to a different tenant —
     * indistinguishable from "not found" (no information leakage).
     */
    Optional<User> findByIdAndTenantId(UUID id, String tenantId);
}