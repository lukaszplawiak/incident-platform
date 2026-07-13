package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.AddTeamMemberRequest;
import com.incidentplatform.auth.dto.CreateTeamRequest;
import com.incidentplatform.auth.dto.TeamDto;
import com.incidentplatform.auth.dto.TeamMemberDto;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.TeamRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final AuditEventPublisher auditEventPublisher;

    public TeamService(TeamRepository teamRepository,
                       TeamMemberRepository teamMemberRepository,
                       UserRepository userRepository,
                       AuditEventPublisher auditEventPublisher) {
        this.teamRepository       = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository       = userRepository;
        this.auditEventPublisher  = auditEventPublisher;
    }

    // ── createTeam ────────────────────────────────────────────────────────

    @Transactional
    public TeamDto createTeam(CreateTeamRequest request, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        if (teamRepository.existsByNameAndTenantId(request.name(), tenantId)) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Team with name '" + request.name() + "' already exists in this tenant",
                    HttpStatus.CONFLICT);
        }

        final Team team = Team.create(tenantId, request.name(), request.description());

        // Use returned instance — JPA assigns id after INSERT
        final Team saved = teamRepository.save(team);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.TEAM_CREATED,
                "auth-service",
                principal.userId().toString(),
                "Team created: " + saved.getName(),
                Map.of("teamId",   saved.getId() != null ? saved.getId().toString() : "pending",
                        "teamName", saved.getName()));

        log.info("Team created: teamId={}, name={}, tenant={}, by={}",
                saved.getId(), saved.getName(), tenantId, principal.userId());

        return TeamDto.from(saved);
    }

    // ── listTeams ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamDto> listTeams() {
        return teamRepository.findByTenantId(TenantContext.get())
                .stream()
                .map(TeamDto::from)
                .toList();
    }

    // ── getTeam ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TeamDto getTeam(UUID teamId) {
        return TeamDto.from(requireTeam(teamId));
    }

    // ── deleteTeam ────────────────────────────────────────────────────────

    @Transactional
    public void deleteTeam(UUID teamId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        final Team team = requireTeam(teamId);

        team.softDelete();
        teamRepository.save(team);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.TEAM_DELETED,
                "auth-service",
                principal.userId().toString(),
                "Team soft-deleted: " + team.getName(),
                Map.of("teamId",   teamId.toString(),
                        "teamName", team.getName()));

        log.info("Team soft-deleted: teamId={}, name={}, tenant={}, by={}",
                teamId, team.getName(), tenantId, principal.userId());
    }

    // ── addMember ─────────────────────────────────────────────────────────

    @Transactional
    public TeamMemberDto addMember(UUID teamId,
                                   AddTeamMemberRequest request,
                                   UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        final Team team = requireTeam(teamId);

        final User user = userRepository.findByIdAndTenantId(
                        request.userId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", request.userId().toString()));

        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, request.userId())) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "User is already a member of this team",
                    HttpStatus.CONFLICT);
        }

        final TeamMember member = TeamMember.create(team, user, request.teamRole());
        final TeamMember saved  = teamMemberRepository.save(member);

        auditEventPublisher.publishAuth(
                request.userId(), tenantId,
                AuditEventTypes.TEAM_MEMBER_ADDED,
                "auth-service",
                principal.userId().toString(),
                "User added to team: " + team.getName(),
                Map.of("teamId",   teamId.toString(),
                        "teamRole", request.teamRole().name()));

        log.info("Member added: userId={}, teamId={}, role={}, tenant={}, by={}",
                request.userId(), teamId, request.teamRole(),
                tenantId, principal.userId());

        return TeamMemberDto.from(saved);
    }

    // ── removeMember ──────────────────────────────────────────────────────

    @Transactional
    public void removeMember(UUID teamId, UUID userId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        requireTeam(teamId);

        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new ResourceNotFoundException("TeamMember",
                    teamId + "/" + userId);
        }

        teamMemberRepository.deleteByTeamIdAndUserId(teamId, userId);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.TEAM_MEMBER_REMOVED,
                "auth-service",
                principal.userId().toString(),
                "User removed from team",
                Map.of("teamId", teamId.toString()));

        log.info("Member removed: userId={}, teamId={}, tenant={}, by={}",
                userId, teamId, tenantId, principal.userId());
    }

    // ── listMembers ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TeamMemberDto> listMembers(UUID teamId) {
        requireTeam(teamId);
        return teamMemberRepository.findByTeamId(teamId)
                .stream()
                .map(TeamMemberDto::from)
                .toList();
    }

    // ── updateMemberRole ──────────────────────────────────────────────────

    @Transactional
    public TeamMemberDto updateMemberRole(UUID teamId, UUID userId,
                                          TeamRole newRole,
                                          UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        requireTeam(teamId);

        final TeamMember member = teamMemberRepository
                .findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TeamMember", teamId + "/" + userId));

        member.updateRole(newRole);
        final TeamMember saved = teamMemberRepository.save(member);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.TEAM_MEMBER_ROLE_UPDATED,
                "auth-service",
                principal.userId().toString(),
                "Team member role updated",
                Map.of("teamId",  teamId.toString(),
                        "newRole", newRole.name()));

        log.info("Member role updated: userId={}, teamId={}, newRole={}, by={}",
                userId, teamId, newRole, principal.userId());

        return TeamMemberDto.from(saved);
    }

    // ── private ───────────────────────────────────────────────────────────

    private Team requireTeam(UUID teamId) {
        return teamRepository.findByIdAndTenantId(teamId, TenantContext.get())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Team", teamId.toString()));
    }
}