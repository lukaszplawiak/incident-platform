package com.incidentplatform.incident.api;

import com.incidentplatform.incident.config.SecurityConfig;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.AssignIncidentRequest;
import com.incidentplatform.incident.dto.AssignTeamRequest;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.UpdateStatusCommand;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link IncidentController} — the platform's main
 * controller, previously with no test coverage of any kind (neither
 * security nor functional).
 *
 * <p>Every endpoint here requires {@code hasRole('RESPONDER') or
 * hasRole('ADMIN')} — no endpoint in this controller is reachable by
 * ROLE_SERVICE or ROLE_INGESTOR. Written after fixing two bugs this
 * absence of coverage had let through undetected:
 * <ul>
 *   <li>{@code GET /{id}/history} used {@code hasRole('ROLE_RESPONDER')} —
 *       Spring's hasRole() auto-prepends "ROLE_", so this checked for
 *       authority "ROLE_ROLE_RESPONDER", which no user ever has. The
 *       endpoint was unreachable by anyone, including admins.</li>
 *   <li>{@code PATCH/DELETE /{id}/team} had no {@code @PreAuthorize} at
 *       all and no team-membership check anywhere — any authenticated
 *       user of any role could reassign or unassign any incident's team,
 *       regardless of their own team membership.</li>
 * </ul>
 *
 * <h2>Why NOT plain {@code @WithMockUser}</h2>
 * {@code @WithMockUser} authenticates as a generic
 * {@code org.springframework.security.core.userdetails.User}, not this
 * app's {@link UserPrincipal} record. {@code @AuthenticationPrincipal
 * UserPrincipal principal} silently resolves to {@code null} on a type
 * mismatch (Spring's default {@code errorOnInvalidType=false}) rather
 * than failing loudly — an endpoint that dereferences {@code
 * principal.userId()} directly (like {@code assignIncident}/{@code
 * updateStatus}) then throws a NullPointerException (500), while one
 * that only forwards {@code principal} into a fully-mocked service call
 * (like {@code assignTeam}/{@code unassignTeam}) doesn't crash but also
 * isn't really testing anything about the principal. Using a real
 * {@link UserPrincipal}-typed {@code Authentication} via {@link
 * #principal} avoids both failure modes.
 *
 * <h2>Why @Import includes UnauthorizedEntryPoint</h2>
 * See PostmortemControllerSecurityTest's identical note — a mocked
 * UnauthorizedEntryPoint would silently turn 401s into 200s.
 *
 * <h2>Why a nested @SpringBootApplication + explicit @ContextConfiguration</h2>
 * IncidentServiceApplication's broad @ComponentScan pulls in WebSocket,
 * Kafka and ShedLock beans not available in the web slice, so a scoped
 * inner TestApplication is used instead. @ContextConfiguration pins
 * Spring to this specific class — without it, Spring auto-detects
 * @SpringBootConfiguration classes on the classpath, and with more than
 * one *SecurityTest in this package (each declaring its own nested
 * TestApplication), that auto-detection becomes ambiguous and fails
 * with "Found multiple @SpringBootConfiguration annotated classes".
 */
@WebMvcTest(IncidentController.class)
@ContextConfiguration(classes = IncidentControllerSecurityTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=incident-service"
})
@DisplayName("IncidentController — security")
class IncidentControllerSecurityTest {

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.incident.api",
            "com.incidentplatform.incident.config",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    static class TestApplication {

        @org.springframework.context.annotation.Bean
        public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
            return new JwtAuthFilter(jwtUtils);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IncidentQueryService queryService;

    @MockitoBean
    private IncidentCommandService commandService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    // Pulled in transitively via shared.security/shared.observability scan —
    // mocked to avoid requiring Kafka infrastructure in the web slice.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    @MockitoBean
    private IncidentEventKafkaSender incidentEventKafkaSender;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private IncidentDto buildIncidentDto() {
        final Incident incident = new Incident(
                TENANT_ID, "High CPU", "CPU exceeded 95%",
                Severity.HIGH, SourceType.OPS, "prometheus",
                "fp-" + UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().minusSeconds(60));
        return IncidentDto.from(incident);
    }

    /**
     * Builds a real {@link UserPrincipal}-typed authentication for one or
     * more roles, applied to a single {@code mockMvc.perform(...)} call via
     * {@code .with(principal("ROLE_RESPONDER"))}. See the class Javadoc for
     * why this replaces {@code @WithMockUser} in this file.
     */
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
        @DisplayName("GET /incidents — 401 without token")
        void list_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/incidents"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /incidents/{id} — 401 without token")
        void getById_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/incidents/{id}", INCIDENT_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /incidents/{id}/history — 401 without token")
        void getHistory_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/incidents/{id}/history", INCIDENT_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/status — 401 without token")
        void updateStatus_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/incidents/{id}/status", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStatusCommand(IncidentStatus.ACKNOWLEDGED, null))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/assignee — 401 without token")
        void assign_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/incidents/{id}/assignee", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignIncidentRequest(USER_ID))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/team — 401 without token")
        void assignTeam_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignTeamRequest(TEAM_ID))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /incidents/{id}/team — 401 without token")
        void unassignTeam_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/incidents/{id}/team", INCIDENT_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── RESPONDER — 200 for every endpoint ──────────────────────────────────

    @Nested
    @DisplayName("RESPONDER role")
    class ResponderRole {

        @Test
        @DisplayName("GET /incidents — 200")
        void list_returns200() throws Exception {
            given(queryService.findAll(anyString(), any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/incidents").with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /incidents/{id} — 200")
        void getById_returns200() throws Exception {
            given(queryService.findById(any(), anyString())).willReturn(buildIncidentDto());

            mockMvc.perform(get("/api/v1/incidents/{id}", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /incidents/{id}/history — 200")
        void getHistory_returns200() throws Exception {
            given(queryService.findHistory(any(), anyString())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/incidents/{id}/history", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/status — 200")
        void updateStatus_returns200() throws Exception {
            given(commandService.updateStatus(any(), any(), any(), anyString()))
                    .willReturn(buildIncidentDto());

            mockMvc.perform(patch("/api/v1/incidents/{id}/status", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStatusCommand(IncidentStatus.ACKNOWLEDGED, null))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/assignee — 200")
        void assign_returns200() throws Exception {
            given(commandService.assignTo(any(), any(), any(), anyString()))
                    .willReturn(buildIncidentDto());

            mockMvc.perform(patch("/api/v1/incidents/{id}/assignee", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignIncidentRequest(USER_ID))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/team — 200 when the service allows it")
        void assignTeam_returns200() throws Exception {
            given(commandService.assignTeam(any(), any(), any(), anyString()))
                    .willReturn(buildIncidentDto());

            mockMvc.perform(patch("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignTeamRequest(TEAM_ID))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/team — 403 when the service rejects for non-membership")
        void assignTeam_returns403WhenNotTeamMember() throws Exception {
            given(commandService.assignTeam(any(), any(), any(), anyString()))
                    .willThrow(BusinessException.notTeamMember(TEAM_ID));

            mockMvc.perform(patch("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignTeamRequest(TEAM_ID))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /incidents/{id}/team — 200")
        void unassignTeam_returns200() throws Exception {
            given(commandService.unassignTeam(any(), any(), anyString()))
                    .willReturn(buildIncidentDto());

            mockMvc.perform(delete("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }
    }

    // ── ADMIN — 200 for every endpoint ──────────────────────────────────────

    @Nested
    @DisplayName("ADMIN role")
    class AdminRole {

        @Test
        @DisplayName("GET /incidents — 200")
        void list_returns200() throws Exception {
            given(queryService.findAll(anyString(), any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/incidents").with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /incidents/{id}/history — 200 (regression test for the ROLE_ prefix bug)")
        void getHistory_returns200() throws Exception {
            given(queryService.findHistory(any(), anyString())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/incidents/{id}/history", INCIDENT_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/team — 200 even for a team ADMIN doesn't belong to")
        void assignTeam_returns200RegardlessOfMembership() throws Exception {
            given(commandService.assignTeam(any(), any(), any(), anyString()))
                    .willReturn(buildIncidentDto());

            mockMvc.perform(patch("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignTeamRequest(TEAM_ID))))
                    .andExpect(status().isOk());
        }
    }

    // ── Wrong role — 403 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("wrong role")
    class WrongRole {

        @Test
        @DisplayName("GET /incidents — 403 for SERVICE (not a human-facing role for this controller)")
        void list_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/incidents").with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /incidents — 403 for INGESTOR")
        void list_returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/incidents").with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /incidents/{id}/team — 403 for SERVICE")
        void assignTeam_returns403ForService() throws Exception {
            mockMvc.perform(patch("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_SERVICE"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AssignTeamRequest(TEAM_ID))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /incidents/{id}/team — 403 for INGESTOR")
        void unassignTeam_returns403ForIngestor() throws Exception {
            mockMvc.perform(delete("/api/v1/incidents/{id}/team", INCIDENT_ID)
                            .with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }
    }
}