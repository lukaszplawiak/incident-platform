package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities.
 *
 * <h2>Soft-delete filtering</h2>
 * {@code @SQLRestriction("archived_at IS NULL AND anonymized_at IS NULL")}
 * on {@link User} automatically excludes archived and anonymized users from
 * all Hibernate queries. No {@code AndArchivedAtIsNull} suffix is needed.
 *
 * <h2>Bypassing the restriction</h2>
 * To read archived or anonymized users (e.g. for restore or admin audit),
 * use native queries annotated with {@code @Query(nativeQuery = true)} —
 * these bypass Hibernate's {@code @SQLRestriction}.
 *
 * <h2>@EntityGraph on findByEmailAndTenantId</h2>
 * Login is the only call site that immediately needs roles (for JWT claims).
 * {@code @EntityGraph} issues a single LEFT JOIN FETCH instead of N+1.
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

    /**
     * Finds any user by id and tenant regardless of archived/anonymized state.
     *
     * <p>Used by:
     * <ul>
     *   <li>{@code UserManagementService.restoreUser()} — needs archived user</li>
     *   <li>{@code UserManagementService.anonymizeUser()} — needs archived user</li>
     * </ul>
     *
     * <p>Native query bypasses {@code @SQLRestriction} intentionally.
     * Must NOT be used in normal application flows — only for admin operations
     * on non-active users.
     */
    @Query(value = "SELECT * FROM users WHERE id = :id AND tenant_id = :tenantId",
            nativeQuery = true)
    Optional<User> findAnyByIdAndTenantId(
            @Param("id") UUID id,
            @Param("tenantId") String tenantId);
}