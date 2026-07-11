package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 *
 * <h2>Soft-delete filtering</h2>
 * Soft-delete filtering is handled globally by
 * {@code @SQLRestriction("deleted_at IS NULL")} on the {@link User} entity.
 * All Hibernate queries — including inherited {@link JpaRepository} methods
 * like {@code findById()} and {@code findAll()} — automatically exclude
 * soft-deleted users. No {@code AndDeletedAtIsNull} suffix is needed.
 *
 * <p>Exception: if a native {@code @Query(nativeQuery = true)} is ever added,
 * it must include {@code AND deleted_at IS NULL} explicitly — native SQL
 * bypasses Hibernate filters.
 *
 * <h2>@EntityGraph on findByEmailAndTenantId</h2>
 * Roles are loaded lazily ({@code FetchType.LAZY}) to prevent N+1 queries
 * on list endpoints. The login query is the only call site that needs roles
 * immediately (for JWT claims), so only this method eagerly joins roles
 * via {@code @EntityGraph}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a non-deleted user by email within a tenant, eagerly loading roles.
     *
     * <p>{@code @EntityGraph} issues a single LEFT JOIN FETCH on {@code roles}
     * instead of a separate SELECT — avoids N+1 for the login flow which
     * immediately calls {@link User#getRoleNames()} to build JWT claims.
     *
     * <p>Used by: login flow, duplicate-email guard in user creation.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmailAndTenantId(String email, String tenantId);

    /**
     * Lists all non-deleted users in a tenant — paginated.
     * Roles are NOT eagerly loaded — list endpoints don't need them.
     */
    Page<User> findByTenantId(String tenantId, Pageable pageable);

    /**
     * Finds a specific non-deleted user by id within a tenant.
     * Returns empty if the user does not exist, belongs to a different tenant,
     * or has been soft-deleted — indistinguishable (no information leakage).
     */
    Optional<User> findByIdAndTenantId(UUID id, String tenantId);
}