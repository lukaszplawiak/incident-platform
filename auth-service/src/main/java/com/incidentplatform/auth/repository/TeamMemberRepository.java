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

    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
}