package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    /**
     * Finds a valid (non-expired, non-used) token by its hash and type.
     * Used by accept-invite and reset-password endpoints.
     */
    @Query("""
            SELECT t FROM AuthToken t
            WHERE t.tokenHash = :hash
              AND t.type = :type
              AND t.usedAt IS NULL
              AND t.expiresAt > :now
            """)
    Optional<AuthToken> findValidByHashAndType(
            @Param("hash") String hash,
            @Param("type") AuthToken.Type type,
            @Param("now") Instant now);


    /**
     * Finds all valid (non-expired, non-used) INVITE tokens for a user.
     * Used by the resend-invite flow to invalidate existing tokens before
     * generating a new one — prevents multiple valid invite links being
     * active simultaneously.
     */
    @Query("""
            SELECT t FROM AuthToken t
            WHERE t.user.id = :userId
              AND t.type = :type
              AND t.usedAt IS NULL
              AND t.expiresAt > :now
            """)
    List<AuthToken> findValidByUserIdAndType(
            @Param("userId") UUID userId,
            @Param("type") AuthToken.Type type,
            @Param("now") Instant now);

    /**
     * Atomically marks a token used, but only if it is not already used —
     * backlog #53. Returns the number of rows affected: {@code 1} if this
     * call won the race and the token is now claimed, {@code 0} if some
     * other concurrent call already claimed it first.
     *
     * <h2>Why a conditional UPDATE instead of {@code @Version}</h2>
     * {@code AuthTokenService.consumeToken()} was a classic check-then-act:
     * read the token (confirming {@code usedAt IS NULL}), then separately
     * save it after calling {@code markUsed()} — with nothing preventing
     * two concurrent calls (e.g. a client double-submit, or a replayed
     * request) from both passing the read check before either write
     * commits, letting a single-use token be consumed twice and both
     * callers proceed with whatever action that token was meant to gate
     * exactly once (issuing a new token pair, completing an invite, etc.).
     *
     * <p>{@code @Version} (the pattern already used for {@code User} in
     * this same service, and for {@code EscalationTask}/{@code OncallSchedule}/
     * {@code Postmortem} elsewhere in this codebase) was considered first,
     * for consistency — but doesn't fit this entity's actual shape as
     * well as it fits those. Those entities have several independently-
     * mutated fields and multiple genuinely different writers reasonably
     * racing over the whole row; {@code @Version} protects the row
     * generically. {@code AuthToken} has exactly one mutable field and
     * exactly one lifecycle transition ({@code usedAt}: null → set,
     * exactly once) — the actual invariant that needs protecting is
     * narrower and more specific than "detect any conflicting write to
     * this row," and a plain {@code @Version} column would have required
     * {@code saveAndFlush(...)} to reliably force the conflict to surface
     * synchronously (since {@code consumeToken()} is called from several
     * places that are themselves already {@code @Transactional}, meaning
     * a plain {@code save()} could join that caller's transaction and
     * defer its flush past where a catch block could reasonably sit).
     *
     * <p>A single conditional {@code UPDATE ... WHERE used_at IS NULL}
     * encodes the real invariant directly, requires no new column, and
     * needs no dependency on Hibernate's flush timing to behave
     * correctly — the affected-row count is unambiguous the moment this
     * statement executes, in any transactional context.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now
            WHERE t.id = :id
              AND t.usedAt IS NULL
            """)
    int markUsedIfUnused(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Bulk-invalidates every valid REFRESH token for a user — used by
     * {@code PasswordService.resetPassword()} (no session to preserve;
     * the user isn't authenticated during a recovery flow) and by
     * {@code AuthTokenService.invalidateAllRefreshTokens()}, this
     * repository method's own only caller.
     *
     * <h2>Fixed: replaces a load-then-loop-then-save pattern</h2>
     * {@code AuthTokenService.invalidateAllRefreshTokens} previously
     * loaded every matching token, then called {@code token.markUsed()}
     * + {@code tokenRepository.save(token)} for each one individually —
     * N reads plus N writes for N active sessions. A single bulk
     * {@code UPDATE} does the same work as one SQL statement, matching
     * the same pattern already established for
     * {@link #markUsedIfUnused}/{@link #deleteExpiredAndUsed} in this
     * same repository — introduced here as a natural side effect of
     * adding the two session-aware variants below with the correct,
     * efficient style, rather than leaving this one as the odd one out.
     *
     * @return the number of tokens invalidated
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now
            WHERE t.user.id = :userId
              AND t.type = 'REFRESH'
              AND t.usedAt IS NULL
            """)
    int invalidateAllRefreshTokens(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Invalidates exactly one session's REFRESH token — the precise
     * counterpart to {@link #markUsedIfUnused}'s single-token precision,
     * but scoped by session rather than by token ID. Used by
     * {@code LogoutService}, which already revokes the current access
     * token precisely (by its own {@code jti}, via
     * {@code TokenRevocationService}/Redis) — this does the same for the
     * paired refresh token, rather than invalidating every session the
     * user has, as the pre-{@code sessionId} code had to.
     *
     * @return the number of tokens invalidated (0 or 1 in practice, since
     *         a session has at most one active refresh token at a time)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now
            WHERE t.user.id = :userId
              AND t.type = 'REFRESH'
              AND t.usedAt IS NULL
              AND t.sessionId = :sessionId
            """)
    int invalidateRefreshTokenForSession(
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    /**
     * Invalidates every session's REFRESH token EXCEPT the given one —
     * used by {@code PasswordService.changePassword()}: a defensive
     * response to a possible compromise should terminate every other
     * session while leaving the one the user is actively, legitimately
     * using right now (they just proved their current password) alone.
     *
     * <h2>{@code t.sessionId IS NULL OR t.sessionId <> :sessionId}, not a
     * plain {@code <>}</h2>
     * SQL's three-valued logic means {@code NULL <> :sessionId} evaluates
     * to UNKNOWN, not TRUE, for any row where {@code session_id} is
     * NULL — such a row would silently, incorrectly be excluded from
     * this "invalidate everything except" query, exempting it from ever
     * being invalidated by this method. In practice every new REFRESH
     * token going forward always has a session_id (set at generation
     * time in {@code AuthTokenService}), but this defends against ever
     * silently exempting a token from invalidation if some future code
     * path ever forgot to set it — the same class of NULL-safety gap
     * already found and fixed in oncall-service's own schedule-overlap
     * query, applied here as the same defensive habit rather than
     * relying on an application-level invariant alone.
     *
     * @return the number of tokens invalidated
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now
            WHERE t.user.id = :userId
              AND t.type = 'REFRESH'
              AND t.usedAt IS NULL
              AND (t.sessionId IS NULL OR t.sessionId <> :sessionId)
            """)
    int invalidateAllRefreshTokensExceptSession(
            @Param("userId") UUID userId,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    /**
     * Deletes all expired or used tokens — intended for scheduled cleanup.
     * Keeps the table lean without touching active tokens.
     *
     * <h2>Fixed (backlog #34): {@code clearAutomatically = true}</h2>
     * {@code @Modifying} DELETE/UPDATE queries execute directly at the SQL
     * level, bypassing Hibernate's persistence context entirely — an
     * entity already loaded/saved earlier in the same transaction stays
     * in that context, unaware the underlying row was just deleted.
     * Without {@code clearAutomatically}, a subsequent {@code findById}
     * (or any other read) for that same entity within the same
     * transaction would return the stale, already-deleted in-memory
     * object instead of correctly reflecting its removal — Hibernate
     * checks the persistence context before ever re-querying the
     * database. Confirmed as a real, reproducible issue by
     * {@code AuthRepositoryIntegrationTest} (real Postgres, real
     * Hibernate session) — exactly the class of bug a mocked-repository
     * test cannot catch, since Mockito has no persistence context to get
     * out of sync in the first place.
     *
     * <p>Currently benign in production — {@code AuthTokenCleanupScheduler}
     * (the only caller) does nothing else with {@code AuthToken} in the
     * same transaction — but {@code clearAutomatically = true} is the
     * standard, defensive default for this exact class of query
     * regardless, protecting any future code added to that same
     * transactional scope.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            DELETE FROM AuthToken t
            WHERE t.usedAt IS NOT NULL
               OR t.expiresAt < :threshold
            """)
    int deleteExpiredAndUsed(@Param("threshold") Instant threshold);
}