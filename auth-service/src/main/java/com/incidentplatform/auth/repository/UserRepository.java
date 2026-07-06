package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 *
 * <h2>Soft-delete filtering</h2>
 * All query methods filter {@code deleted_at IS NULL} — expressed explicitly
 * in the method name via the {@code AndDeletedAtIsNull} suffix. This makes
 * the soft-delete filter visible at every call site rather than hiding it
 * inside a {@code @Query} annotation with a misleading method name.
 *
 * <p>The raw {@link #findById(Object)} inherited from {@link JpaRepository}
 * intentionally does NOT filter deleted users — it allows internal operations
 * to locate a deleted record if genuinely needed (e.g. data recovery tooling).
 * Application code must always use the methods below, which enforce both
 * tenant isolation and soft-delete filtering.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a non-deleted user by email within a tenant.
     * Used by the login flow and the duplicate-email guard in user creation.
     * Returns empty for deleted users — they cannot log in or block re-invites.
     */
    Optional<User> findByEmailAndTenantIdAndDeletedAtIsNull(
            String email, String tenantId);

    /**
     * Lists all non-deleted users in a tenant — paginated.
     * Soft-deleted users are excluded from the result set.
     */
    Page<User> findByTenantIdAndDeletedAtIsNull(
            String tenantId, Pageable pageable);

    /**
     * Finds a specific non-deleted user by id within a tenant.
     * Returns empty if the user does not exist in this tenant, belongs to a
     * different tenant, or has been soft-deleted — indistinguishable from
     * "not found" (no information leakage about deleted users or cross-tenant ids).
     */
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(
            UUID id, String tenantId);
}