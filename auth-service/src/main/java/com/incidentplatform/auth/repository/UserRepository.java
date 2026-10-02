package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
     * Whether a tenant has any user that is neither archived nor anonymized —
     * {@code @SQLRestriction} applies to derived queries too (checked in
     * {@code AuthRepositoryIntegrationTest}; this Javadoc used to say archived
     * users were included). Used by {@code OperatorTenantBootstrap} (backlog
     * #0-16, #0-49) to tell an empty operator tenant, where it invites the first
     * admin, from one whose users need a human.
     */
    boolean existsByTenantId(String tenantId);

    /**
     * Whether a tenant has, or ever had, any user, archived and anonymized ones
     * included. Native, to bypass {@code @SQLRestriction} on purpose: tenant
     * provisioning (backlog #0-80) must not hand a new admin a tenant id whose
     * users and data already exist, even if nobody in it is active any more.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM users WHERE tenant_id = :tenantId)",
            nativeQuery = true)
    boolean existsAnyByTenantId(@Param("tenantId") String tenantId);

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
     * The same user, row-locked until the transaction ends (backlog #0-89,
     * found in review): the API key creation checks (active-key caps and the
     * hourly limit) read counts and then insert, so parallel requests of one
     * user would all pass them. With the creator locked first, only one of a
     * user's parallel creations proceeds; other users are not affected.
     *
     * <p>NOWAIT (found in review): a second request finding the row locked
     * fails at once instead of waiting, as each waiter would hold one of the
     * few pooled connections and a burst of them could starve the whole
     * service. Callers turn the failure into a 429
     * ({@code ApiKeyCreationLimit.lockingCreator}).
     *
     * <p>{@code FOR NO KEY UPDATE}, not {@code FOR UPDATE} (found in review):
     * every uncommitted row referencing the user through a foreign key (a
     * login's token, a reset's outbox row, a team membership) holds
     * {@code FOR KEY SHARE} on it, which conflicts with {@code FOR UPDATE}, so
     * those would have turned a creation into a spurious 429. {@code FOR NO KEY
     * UPDATE} conflicts only with itself and with writes to the row (an
     * {@code UPDATE users} in flight, rare and short), which still yield a 429.
     *
     * <p>Native, so it repeats the entity's {@code @SQLRestriction} (archived
     * and anonymized users are not found).
     */
    @Query(value = "SELECT * FROM users WHERE id = :id AND tenant_id = :tenantId "
            + "AND archived_at IS NULL AND anonymized_at IS NULL FOR NO KEY UPDATE NOWAIT",
            nativeQuery = true)
    Optional<User> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") String tenantId);

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

    /**
     * Counts active (non-archived, non-anonymized, {@code active=true})
     * users in a tenant holding the given role, excluding one specific
     * user.
     *
     * <p>Used by {@code UserManagementService}'s "last admin" guard —
     * checking "if I exclude this user, are there other active admins
     * left?" is exactly the question that needs answering before removing
     * ROLE_ADMIN, deactivating, or archiving someone: excluding the
     * subject of the operation and counting the rest tells you whether
     * the operation would leave the tenant with zero administrators.
     * {@code role} is bound as a typed {@link Role} parameter, not a
     * String — see the type-mismatch bug documented in
     * {@code OncallScheduleRepository.existsOverlappingForCreate} for why
     * a raw String parameter for an enum-mapped JPQL comparison is a real
     * risk, not just a style preference.
     */
    @Query("""
            SELECT COUNT(DISTINCT u) FROM User u
            JOIN u.roles r
            WHERE u.tenantId = :tenantId
            AND r.role = :role
            AND u.active = true
            AND u.id != :excludeUserId
            """)
    long countActiveUsersWithRoleExcluding(
            @Param("tenantId") String tenantId,
            @Param("role") Role role,
            @Param("excludeUserId") UUID excludeUserId);

    /**
     * Whether a tenant has an active user holding the role who has accepted
     * their invite (has a password) — i.e. someone who can actually log in.
     *
     * <p>Used by {@code OperatorTenantBootstrap} (backlog #0-49): the operator
     * tenant is set up only once such an admin exists; a user who was invited
     * but never accepted does not count.
     */
    @Query("""
            SELECT COUNT(u) > 0 FROM User u
            JOIN u.roles r
            WHERE u.tenantId = :tenantId
            AND r.role = :role
            AND u.active = true
            AND u.passwordHash IS NOT NULL
            """)
    boolean existsActiveAcceptedUserWithRole(
            @Param("tenantId") String tenantId,
            @Param("role") Role role);

    /**
     * Of the given tenants, those that have an active user with this role who
     * has set a password (backlog #0-80: the operator's tenant list shows
     * whether each tenant's first admin has accepted). One query for a page of
     * tenants instead of one per tenant.
     */
    @Query("""
            SELECT DISTINCT u.tenantId FROM User u
            JOIN u.roles r
            WHERE u.tenantId IN :tenantIds
            AND r.role = :role
            AND u.active = true
            AND u.passwordHash IS NOT NULL
            """)
    List<String> findTenantIdsWithActiveAcceptedUserWithRole(
            @Param("tenantIds") Collection<String> tenantIds,
            @Param("role") Role role);

    /**
     * Records that the MFA_ENABLED notice of the user's current factor was
     * sent (backlog #0-83). Applies only if the notice belongs to the current
     * enrolment: MFA still enabled, enabled no later than the notice was
     * requested, and no notice recorded yet. A notice of an earlier factor
     * therefore never marks a new one. Bumps {@code version}, as
     * {@code @Version} would. Tenant-scoped like every query, though a user
     * id alone is unique.
     *
     * @return 1 if recorded, 0 if the notice is not the current factor's
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE User u
            SET u.mfaEnabledNoticeSentAt = :sentAt, u.version = u.version + 1
            WHERE u.id = :userId
              AND u.tenantId = :tenantId
              AND u.mfaEnabled = true
              AND u.mfaEnabledAt <= :requestedAt
              AND u.mfaEnabledNoticeSentAt IS NULL
            """)
    int recordMfaEnabledNoticeSent(@Param("userId") UUID userId,
                                   @Param("tenantId") String tenantId,
                                   @Param("requestedAt") Instant requestedAt,
                                   @Param("sentAt") Instant sentAt);
}
