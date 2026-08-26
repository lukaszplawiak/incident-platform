package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.dto.AddTeamMemberRequest;
import com.incidentplatform.auth.dto.CreateTeamRequest;
import com.incidentplatform.auth.dto.TeamDto;
import com.incidentplatform.auth.dto.TeamMemberDto;
import com.incidentplatform.auth.service.TeamService;
import com.incidentplatform.shared.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link TeamController}.
 *
 * <h2>Fixed (backlog #63): read endpoints now require an explicit role</h2>
 * {@code listTeams}/{@code getTeam}/{@code listMembers} previously had no
 * {@code @PreAuthorize} at all — reachable by any authenticated
 * principal, including non-human accounts like {@code ROLE_SERVICE}/
 * {@code ROLE_INGESTOR}. Now require {@code ROLE_RESPONDER} or
 * {@code ROLE_ADMIN}, matching {@code postmortem-service}'s
 * {@code PostmortemController} convention — see {@link TeamController}'s
 * own Javadoc for the full reasoning. The {@code IngestorRole} nested
 * class below is the actual regression coverage for this fix.
 *
 * <p>Write endpoints (create/archive/restore team, add/remove member,
 * update member role) remain ADMIN only (or {@code TeamRole.MANAGER}
 * for the specific team, on membership endpoints) — unchanged by this fix.
 *
 * <h2>Why a real UserPrincipal-typed Authentication, not @WithMockUser</h2>
 * {@code @WithMockUser} authenticates as a generic
 * {@code org.springframework.security.core.userdetails.User}, not this
 * app's {@link UserPrincipal} record — {@code @AuthenticationPrincipal
 * UserPrincipal principal} then silently resolves to {@code null}
 * (Spring's default {@code errorOnInvalidType=false}). Every write
 * endpoint here takes {@code @AuthenticationPrincipal UserPrincipal
 * principal} and forwards it straight into {@link TeamService} — with a
 * null principal that would still "pass" against a fully-mocked service
 * (Mockito's {@code any()} accepts null), silently testing nothing.
 * {@link #principal} builds a real, correctly-typed {@code Authentication}
 * instead. Found and fixed for IncidentControllerSecurityTest first —
 * see that file for the full account, including two now-corrected
 * SecurityTest files (Auth/UserControllerSecurityTest) that may have the
 * same latent gap wherever they dereference the principal (tracked
 * separately, not fixed here).
 *
 * <h2>Why no nested TestApplication</h2>
 * Unlike incident-service's *SecurityTest files, this mirrors the
 * existing pattern already established in this package
 * (AuthControllerSecurityTest, UserControllerSecurityTest): plain
 * {@code @WebMvcTest(TeamController.class)} against the real
 * AuthServiceApplication, no scoped inner config needed here.
 */
@WebMvcTest(TeamController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("TeamController — security")
class TeamControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TeamService teamService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @MockitoBean
    private TokenRevocationChecker tokenRevocationChecker;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TeamDto buildTeamDto() {
        return new TeamDto(TEAM_ID, TENANT_ID, "Platform Team", "desc", Instant.now());
    }

    private TeamMemberDto buildTeamMemberDto() {
        return new TeamMemberDto(USER_ID, "member@acme.com", TeamRole.RESPONDER, Instant.now());
    }

    /** See class Javadoc for why this replaces {@code @WithMockUser}. */
    private static RequestPostProcessor principal(String... roles) {
        final UserPrincipal userPrincipal = new UserPrincipal(
                USER_ID, TENANT_ID, "user@acme.com", List.of(roles), List.of());
        final List<GrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(
                userPrincipal, null, authorities));
    }

    // ── Unauthenticated — 401 for every endpoint ────────────────────────────

    @Nested
    @DisplayName("unauthenticated requests")
    class Unauthenticated {

        @Test
        @DisplayName("POST /teams — 401")
        void createTeam_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/teams")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateTeamRequest("Platform Team", null))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /teams — 401")
        void listTeams_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/teams"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /teams/{id} — 401")
        void getTeam_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}", TEAM_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /teams/{id} — 401")
        void archiveTeam_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}", TEAM_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /teams/{id}/restore — 401")
        void restoreTeam_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{teamId}/restore", TEAM_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /teams/{id}/members — 401")
        void addMember_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{teamId}/members", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AddTeamMemberRequest(USER_ID, TeamRole.RESPONDER))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /teams/{id}/members — 401")
        void listMembers_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}/members", TEAM_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PATCH /teams/{id}/members/{userId}/role — 401")
        void updateMemberRole_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/teams/{teamId}/members/{userId}/role", TEAM_ID, USER_ID)
                            .param("teamRole", "MANAGER"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /teams/{id}/members/{userId} — 401")
        void removeMember_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", TEAM_ID, USER_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── RESPONDER — read endpoints 200, write endpoints 403 ─────────────────

    @Nested
    @DisplayName("RESPONDER role")
    class ResponderRole {

        @Test
        @DisplayName("GET /teams — 200")
        void listTeams_returns200() throws Exception {
            given(teamService.listTeams()).willReturn(List.of(buildTeamDto()));

            mockMvc.perform(get("/api/v1/teams").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /teams/{id} — 200")
        void getTeam_returns200() throws Exception {
            given(teamService.getTeam(TEAM_ID)).willReturn(buildTeamDto());

            mockMvc.perform(get("/api/v1/teams/{teamId}", TEAM_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /teams/{id}/members — 200")
        void listMembers_returns200() throws Exception {
            given(teamService.listMembers(TEAM_ID)).willReturn(List.of(buildTeamMemberDto()));

            mockMvc.perform(get("/api/v1/teams/{teamId}/members", TEAM_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /teams — 403 (create is ADMIN only)")
        void createTeam_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/teams")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateTeamRequest("Platform Team", null))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /teams/{id} — 403 (archive is ADMIN only)")
        void archiveTeam_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}", TEAM_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }


        @Test
        @DisplayName("POST /teams/{id}/restore — 403 (restore is ADMIN only)")
        void restoreTeam_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{teamId}/restore", TEAM_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /teams/{id}/members — 403 (add member is ADMIN only)")
        void addMember_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/teams/{teamId}/members", TEAM_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AddTeamMemberRequest(USER_ID, TeamRole.RESPONDER))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /teams/{id}/members/{userId}/role — 403 (ADMIN only)")
        void updateMemberRole_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/teams/{teamId}/members/{userId}/role", TEAM_ID, USER_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .param("teamRole", "MANAGER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /teams/{id}/members/{userId} — 403 (remove member is ADMIN only)")
        void removeMember_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", TEAM_ID, USER_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ADMIN — 200/2xx for every endpoint ───────────────────────────────────

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {

        @Test
        @DisplayName("POST /teams — 201")
        void createTeam_returns201() throws Exception {
            given(teamService.createTeam(any(), any())).willReturn(buildTeamDto());

            mockMvc.perform(post("/api/v1/teams")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateTeamRequest("Platform Team", null))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("DELETE /teams/{id} — 204")
        void archiveTeam_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}", TEAM_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST /teams/{id}/restore — 200")
        void restoreTeam_returns200() throws Exception {
            given(teamService.restoreTeam(any(), any())).willReturn(buildTeamDto());

            mockMvc.perform(post("/api/v1/teams/{teamId}/restore", TEAM_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /teams/{id}/members — 201")
        void addMember_returns201() throws Exception {
            given(teamService.addMember(any(), any(), any())).willReturn(buildTeamMemberDto());

            mockMvc.perform(post("/api/v1/teams/{teamId}/members", TEAM_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AddTeamMemberRequest(USER_ID, TeamRole.RESPONDER))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("PATCH /teams/{id}/members/{userId}/role — 200")
        void updateMemberRole_returns200() throws Exception {
            given(teamService.updateMemberRole(any(), any(), any(), any()))
                    .willReturn(buildTeamMemberDto());

            mockMvc.perform(patch("/api/v1/teams/{teamId}/members/{userId}/role", TEAM_ID, USER_ID)
                            .with(principal("ROLE_ADMIN"))
                            .param("teamRole", "MANAGER"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /teams/{id}/members/{userId} — 204")
        void removeMember_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", TEAM_ID, USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());
        }
    }

    // ── INGESTOR — the actual regression coverage for backlog #63 ──────────

    /**
     * Before this fix, all three of these would have returned 200 for
     * ANY authenticated role, including this one — a machine/integration
     * account with no legitimate reason to browse team structure or
     * membership. This class is the actual regression test.
     */
    @Nested
    @DisplayName("INGESTOR role (backlog #63)")
    class IngestorRole {

        @Test
        @DisplayName("GET /teams — 403")
        void listTeams_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/teams").with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /teams/{id} — 403")
        void getTeam_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}", TEAM_ID)
                            .with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /teams/{id}/members — 403")
        void listMembers_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/teams/{teamId}/members", TEAM_ID)
                            .with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }
    }
}