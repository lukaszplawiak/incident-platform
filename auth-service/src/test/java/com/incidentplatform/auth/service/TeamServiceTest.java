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
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService")
class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditEventPublisher auditEventPublisher;

    private TeamService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID TEAM_ID  = UUID.randomUUID();
    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeamService(
                teamRepository, teamMemberRepository,
                userRepository, auditEventPublisher);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── createTeam ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createTeam")
    class CreateTeam {

        @Test
        @DisplayName("creates team and returns TeamDto")
        void createsTeam() {
            given(teamRepository.existsByNameAndTenantId("backend-team", TENANT_ID))
                    .willReturn(false);
            given(teamRepository.save(any())).willAnswer(i -> {
                // Simulate JPA id assignment on persist
                return Team.forTesting(UUID.randomUUID(), TENANT_ID, "backend-team");
            });

            final TeamDto result = service.createTeam(
                    new CreateTeamRequest("backend-team", "Backend engineers"),
                    buildPrincipal(ADMIN_ID));

            assertThat(result.name()).isEqualTo("backend-team");
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.id()).isNotNull();
            then(teamRepository).should().save(any(Team.class));
        }

        @Test
        @DisplayName("throws 409 when team name already exists")
        void throwsOnDuplicateName() {
            given(teamRepository.existsByNameAndTenantId("backend-team", TENANT_ID))
                    .willReturn(true);

            assertThatThrownBy(() ->
                    service.createTeam(
                            new CreateTeamRequest("backend-team", null),
                            buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(teamRepository).should(never()).save(any());
        }
    }

    // ── deleteTeam ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("archiveTeam")
    class ArchiveTeam {

        @Test
        @DisplayName("archives team")
        void archivesTeam() {
            final Team team = Team.forTesting(TEAM_ID, TENANT_ID, "backend-team");
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(team));
            given(teamRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.archiveTeam(TEAM_ID, buildPrincipal(ADMIN_ID));

            final ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
            then(teamRepository).should().save(captor.capture());
            assertThat(captor.getValue().isArchived()).isTrue();
            assertThat(captor.getValue().getArchivedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 404 when team not found")
        void throws404WhenNotFound() {
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.archiveTeam(TEAM_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }


    // ── restoreTeam ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("restoreTeam")
    class RestoreTeam {

        @Test
        @DisplayName("restores archived team")
        void restoresArchivedTeam() {
            final Team archived = Team.forTesting(TEAM_ID, TENANT_ID, "backend-team");
            archived.archive();
            given(teamRepository.findArchivedByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(archived));
            given(teamRepository.existsByNameAndTenantId("backend-team", TENANT_ID))
                    .willReturn(false);
            given(teamRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final TeamDto result = service.restoreTeam(TEAM_ID, buildPrincipal(ADMIN_ID));

            assertThat(result.name()).isEqualTo("backend-team");
            final ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
            then(teamRepository).should().save(captor.capture());
            assertThat(captor.getValue().isArchived()).isFalse();
        }

        @Test
        @DisplayName("throws 409 when active team with same name exists")
        void throws409OnNameConflict() {
            final Team archived = Team.forTesting(TEAM_ID, TENANT_ID, "backend-team");
            archived.archive();
            given(teamRepository.findArchivedByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(archived));
            given(teamRepository.existsByNameAndTenantId("backend-team", TENANT_ID))
                    .willReturn(true);

            assertThatThrownBy(() ->
                    service.restoreTeam(TEAM_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(com.incidentplatform.shared.exception.BusinessException.class)
                    .extracting(e -> ((com.incidentplatform.shared.exception.BusinessException) e)
                            .getHttpStatus())
                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("throws 404 when team not found in archived state")
        void throws404WhenNotArchived() {
            given(teamRepository.findArchivedByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.restoreTeam(TEAM_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(com.incidentplatform.shared.exception.ResourceNotFoundException.class);
        }
    }

    // ── addMember ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addMember")
    class AddMember {

        @Test
        @DisplayName("adds member with specified role")
        void addsMember() {
            final Team team = Team.forTesting(TEAM_ID, TENANT_ID, "backend-team");
            final User user = User.forTesting(USER_ID, TENANT_ID, "user@test.com",
                    null, true, List.of("ROLE_RESPONDER"));

            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(team));
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID))
                    .willReturn(false);
            given(teamMemberRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final TeamMemberDto result = service.addMember(
                    TEAM_ID,
                    new AddTeamMemberRequest(USER_ID, TeamRole.RESPONDER),
                    buildPrincipal(ADMIN_ID));

            assertThat(result.teamRole()).isEqualTo(TeamRole.RESPONDER);
            assertThat(result.userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("throws 409 when user already member")
        void throwsOnDuplicateMember() {
            final Team team = Team.forTesting(TEAM_ID, TENANT_ID, "backend-team");
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(team));
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(User.forTesting(USER_ID, TENANT_ID,
                            "u@t.com", null, true, List.of())));
            given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID))
                    .willReturn(true);

            assertThatThrownBy(() ->
                    service.addMember(TEAM_ID,
                            new AddTeamMemberRequest(USER_ID, TeamRole.RESPONDER),
                            buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── removeMember ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("removes existing member")
        void removesMember() {
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(
                            Team.forTesting(TEAM_ID, TENANT_ID, "team")));
            given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID))
                    .willReturn(true);

            service.removeMember(TEAM_ID, USER_ID, buildPrincipal(ADMIN_ID));

            then(teamMemberRepository).should()
                    .deleteByTeamIdAndUserId(TEAM_ID, USER_ID);
        }

        @Test
        @DisplayName("throws 404 when member not found")
        void throws404WhenMemberNotFound() {
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.of(
                            Team.forTesting(TEAM_ID, TENANT_ID, "team")));
            given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID))
                    .willReturn(false);

            assertThatThrownBy(() ->
                    service.removeMember(TEAM_ID, USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UserPrincipal buildPrincipal(UUID userId) {
        return new UserPrincipal(userId, TENANT_ID, "admin@test.com",
                List.of("ROLE_ADMIN"), List.of());
    }
}