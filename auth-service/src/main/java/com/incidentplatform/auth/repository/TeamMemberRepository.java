package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TeamMemberRepository
        extends JpaRepository<TeamMember, TeamMember.TeamMemberId> {

    List<TeamMember> findByTeamId(UUID teamId);

    List<TeamMember> findByUserId(UUID userId);

    Optional<TeamMember> findByTeamIdAndUserId(UUID teamId, UUID userId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Returns all team UUIDs that a user belongs to within a tenant.
     * Used by AuthService.login() to populate teamIds in the JWT.
     */
    @Query("SELECT tm.team.id FROM TeamMember tm " +
            "WHERE tm.user.id = :userId " +
            "AND tm.team.tenantId = :tenantId")
    List<UUID> findTeamIdsByUserIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);

     /**
     * Returns team UUIDs where the user holds {@code TeamRole.MANAGER},
     * within a tenant — a subset of {@link #findTeamIdsByUserIdAndTenantId}.
     * Used by AuthService.login() (and the other token-generation call
     * sites — AuthTokenService.rotateRefreshToken, MfaService) to populate
     * the {@code managedTeamIds} JWT claim, which oncall-service and this
     * service's own TeamController/TeamService use to authorize
     * team-scoped actions (on-call schedule create/delete, team membership
     * management) for Managers without requiring tenant-wide ROLE_ADMIN.
     *
     * <h2>Fixed: wrong field name — auth-service failed to start in CI</h2>
     * Originally referenced {@code tm.role}, but {@link TeamMember}'s
     * actual attribute is {@code teamRole} (mapped to the
     * {@code team_role} column — see that entity's own Javadoc on why
     * it's not just called {@code role}: to stay distinct from the
     * tenant-level {@code Role} on {@code UserRole}). Spring Data JPA
     * validates {@code @Query} JPQL against the entity model at
     * application startup (during {@code EntityManagerFactory}
     * initialization, before any request is ever served) — this surfaced
     * as {@code auth-service} failing to start at all
     * (UnsatisfiedDependencyException wrapping Hibernate's
     * UnknownPathException) in the Docker Compose smoke test, not as a
     * unit test failure, since every existing test mocks this repository
     * and Mockito never parses real JPQL. Same class of bug, same reason
     * it stayed invisible until now, as several previously-found issues
     * in oncall-service (see that module's backlog item #22 and its
     * Testcontainers integration test) — a JPQL string is only as
     * trustworthy as the environment that has actually tried to run it
     * against a real Hibernate session.
     */
    @Query("SELECT tm.team.id FROM TeamMember tm " +
            "WHERE tm.user.id = :userId " +
            "AND tm.team.tenantId = :tenantId " +
            "AND tm.teamRole = TeamRole.MANAGER")
    List<UUID> findManagedTeamIdsByUserIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("tenantId") String tenantId);

    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Removes all team memberships for a user.
     * Called by {@code UserManagementService.anonymizeUser()} to remove
     * the user from all teams before anonymizing personal data.
     */
    void deleteByUserId(UUID userId);
}