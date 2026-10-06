package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenants as records (backlog #0-80). Writes go only through
 * {@link #insertIfAbsent}; see {@link Tenant} for why not {@code save()}.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /**
     * Inserts the tenant unless its id is taken, atomically: two operators
     * creating the same tenant at once get one 1 and one 0, never two rows and
     * never an overwrite.
     *
     * <p>{@code @Transactional}: joins the caller's transaction (provisioning),
     * and gives a caller without one (the operator tenant's bootstrap) its own
     * read-write transaction; Spring Data's default for query methods is
     * read-only.
     *
     * @return 1 if inserted, 0 if a tenant with this id already exists
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO tenants (tenant_id, display_name, first_admin_email, created_at, created_by)
            VALUES (:tenantId, :displayName, :firstAdminEmail, now(), :createdBy)
            ON CONFLICT (tenant_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") String tenantId,
                       @Param("displayName") String displayName,
                       @Param("firstAdminEmail") String firstAdminEmail,
                       @Param("createdBy") UUID createdBy);

    Page<Tenant> findAllByOrderByCreatedAtDescTenantIdAsc(Pageable pageable);

    /**
     * Suspends an ACTIVE tenant, or changes the mode, reason or note of a
     * SUSPENDED one (backlog #0-82): one statement guarded by the status, so a
     * tenant being offboarded, or one another operator resumed meanwhile in a
     * way that no longer matches, is not touched. {@code suspended_at} keeps
     * the first suspension's time when only the mode changes.
     *
     * <p>Native, as {@link Tenant} is {@code @Immutable}; it clears and flushes
     * the persistence context (CLAUDE.md, backlog #0-83).
     *
     * @return 1 if the tenant is now suspended as asked, 0 if it was in no state to be
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tenants SET status = 'SUSPENDED', suspension_mode = :mode, suspension_reason = :reason,
                   suspension_note = :note, suspended_by = :operator,
                   suspended_at = CASE WHEN status = 'SUSPENDED' THEN suspended_at ELSE now() END,
                   status_changed_at = now()
            WHERE tenant_id = :tenantId AND status IN ('ACTIVE', 'SUSPENDED')
            """, nativeQuery = true)
    int suspend(@Param("tenantId") String tenantId, @Param("mode") String mode,
                @Param("reason") String reason, @Param("note") String note,
                @Param("operator") UUID operator);

    /**
     * The tenant's status and suspension mode, share-locked until the
     * transaction ends (backlog #0-82, found in review): the check of a sign-in
     * (login, refresh, finishing an MFA login) and of an invite or reset. A
     * suspension takes {@link #findByIdForUpdate}'s lock, which conflicts with
     * this one, so a sign-in and a suspension cannot interleave: either the
     * sign-in commits first and the suspension's session cleanup sees the
     * refresh token it created, or the suspension commits first and the sign-in
     * reads SUSPENDED. Without it, a login that read ACTIVE could insert its
     * refresh token after the cleanup ran, and that session would come back on
     * resume. Callers take it before they consume or otherwise lock a token:
     * a suspension locks the tenant row, then the tenant's token rows, and a
     * sign-in locking a token first would deadlock with it (found in review,
     * reproduced). {@code FOR SHARE} (not {@code FOR KEY SHARE}, which does not
     * conflict with {@code FOR NO KEY UPDATE}); share locks do not block each
     * other, so concurrent sign-ins of one tenant do not wait on one another.
     * Native and an interface projection, so no {@link Tenant} enters the
     * persistence context. Empty for a tenant without a row.
     */
    @Query(value = "SELECT status AS status, suspension_mode AS mode FROM tenants "
            + "WHERE tenant_id = :tenantId FOR SHARE", nativeQuery = true)
    Optional<LockedTenantStatus> findStatusForSignIn(@Param("tenantId") String tenantId);

    /**
     * Bounds how long the rest of this transaction waits for a lock
     * ({@code set_config(..., true)} is {@code SET LOCAL}: it ends with the
     * transaction). Run before {@link #findStatusForSignIn}, so a sign-in queued
     * behind a long suspension gives its pooled connection back instead of
     * holding it until the suspension commits (backlog #0-82, found in review:
     * the pool is small, and a stuck suspension would otherwise starve every
     * tenant's sign-ins).
     *
     * @param timeout a PostgreSQL duration, e.g. {@code "3s"}
     */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);

    /**
     * Undoes {@link #setLocalLockTimeout} for the rest of the transaction:
     * back to the value a {@code RESET} would give ({@code pg_settings.reset_val},
     * the session's own default), so only the tenant lookup waits at most the
     * sign-in timeout (found in review: a later lock wait in the same sign-in,
     * such as a double-submitted MFA token, would otherwise time out as a 500).
     * It restores the session default, not whatever the transaction had before:
     * nothing sets {@code lock_timeout} earlier in a sign-in, nor on the pool's
     * connections; a caller that ever does must read {@code current_setting}
     * first and restore that instead.
     */
    @Query(value = "SELECT set_config('lock_timeout', "
            + "(SELECT reset_val FROM pg_settings WHERE name = 'lock_timeout'), true)", nativeQuery = true)
    String resetLocalLockTimeout();

    /** Row of {@link #findStatusForSignIn}: the columns as stored. */
    interface LockedTenantStatus {
        String getStatus();

        String getMode();
    }

    /**
     * The tenant, row-locked until the transaction ends (backlog #0-82, found
     * in review): suspend and resume read the state they change — the previous
     * mode decides whether sessions are ended, and goes into both audit
     * trails — so two operators acting on one tenant at once are serialised
     * here instead of both reading the same "before" and auditing a
     * transition that never happened.
     *
     * <p>Waits, no NOWAIT (unlike {@code UserRepository.findByIdAndTenantIdForUpdate}):
     * a rare operator action, behind the platform rate limiter, where the
     * second operator should see the first one's result, not an error.
     * {@code FOR NO KEY UPDATE}, so rows referencing the tenant by foreign key
     * are not blocked by it.
     */
    @Query(value = "SELECT * FROM tenants WHERE tenant_id = :tenantId FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Tenant> findByIdForUpdate(@Param("tenantId") String tenantId);

    /**
     * Resumes a SUSPENDED tenant (backlog #0-82), clearing the suspension's
     * details in the same statement.
     *
     * @return 1 if the tenant was suspended and is now active, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tenants SET status = 'ACTIVE', suspension_mode = NULL, suspension_reason = NULL,
                   suspension_note = NULL, suspended_at = NULL, suspended_by = NULL, status_changed_at = now()
            WHERE tenant_id = :tenantId AND status = 'SUSPENDED'
            """, nativeQuery = true)
    int resume(@Param("tenantId") String tenantId);

    /**
     * The tenant's status and suspension mode, for the per-request check
     * ({@code TenantStatusProvider}): a primary-key lookup of two columns.
     * Empty for a tenant without a row.
     */
    @Query("SELECT new com.incidentplatform.auth.repository.TenantStatusView(t.status, t.suspensionMode, "
            + "t.suspendedAt) "
            + "FROM Tenant t WHERE t.tenantId = :tenantId")
    Optional<TenantStatusView> findStatus(@Param("tenantId") String tenantId);
}
