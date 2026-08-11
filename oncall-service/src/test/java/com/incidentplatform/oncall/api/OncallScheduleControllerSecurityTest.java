package com.incidentplatform.oncall.api;

import com.incidentplatform.oncall.config.SecurityConfig;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link OncallScheduleController}.
 *
 * <p>Role matrix under test:
 * <pre>
 * Endpoint                        RESPONDER   ADMIN   INGESTOR   SERVICE
 * GET  /schedules                    ✅         ✅       ❌         ❌
 * GET  /schedules/{id}               ✅         ✅       ❌         ❌
 * POST /schedules                    ❌         ✅       ❌         ❌
 * DELETE /schedules/{id}             ❌         ✅       ❌         ❌
 * GET  /by-slack/{id}    authenticated only (ROLE_SERVICE allowed)
 * </pre>
 *
 * <p>Unauthenticated requests return {@code 401 Unauthorized} via
 * {@link UnauthorizedEntryPoint} registered in
 * {@link com.incidentplatform.shared.security.SharedSecurityAutoConfiguration#buildCommonSecurity}.
 */
@WebMvcTest(OncallScheduleController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=oncall-service"
})
@DisplayName("OncallScheduleController — security")
class OncallScheduleControllerSecurityTest {

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.oncall.api",
            "com.incidentplatform.oncall.config",
            "com.incidentplatform.oncall.config",
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

    @MockitoBean
    private OncallScheduleService service;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    @MockitoBean
    private IncidentEventKafkaSender incidentEventKafkaSender;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID SCHEDULE_ID = UUID.randomUUID();

    private static final String VALID_CREATE_REQUEST = """
            {
              "userId": "user-1",
              "userName": "Jan Kowalski",
              "email": "jan@example.com",
              "phone": "+48100200300",
              "slackUserId": "U0123456789",
              "role": "PRIMARY",
              "startsAt": "2099-01-01T00:00:00Z",
              "endsAt": "2099-01-08T00:00:00Z",
              "notes": "test"
            }
            """;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── Unauthenticated — 401 ─────────────────────────────────────────────

    @Nested
    @DisplayName("unauthenticated requests")
    class Unauthenticated {

        @Test
        @DisplayName("GET /schedules — 401 without token")
        void getSchedules_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /schedules/{id} — 401 without token")
        void getById_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /schedules — 401 without token")
        void create_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("DELETE /schedules/{id} — 401 without token")
        void delete_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── ROLE_INGESTOR — 403 ───────────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_INGESTOR — forbidden on all schedule endpoints")
    class IngestorRole {

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("GET /schedules — 403 for INGESTOR")
        void getSchedules_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("POST /schedules — 403 for INGESTOR")
        void create_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("DELETE /schedules/{id} — 403 for INGESTOR")
        void delete_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ROLE_RESPONDER — read allowed, write reaches the service ──────────

    /**
     * <h2>Fixed: write tests here previously asserted 403 for RESPONDER</h2>
     * Before the Manager role feature, {@code POST}/{@code DELETE
     * /schedules} were {@code ROLE_ADMIN}-only at the URL level, so any
     * RESPONDER correctly got 403 before ever reaching the controller
     * method. That URL-level gate was deliberately relaxed to
     * {@code hasRole('ADMIN') or hasRole('RESPONDER')} as part of that
     * feature — every {@code TeamRole.MANAGER} also holds
     * {@code ROLE_RESPONDER} (see {@code OncallScheduleController.create}'s
     * Javadoc), so Managers need to reach the controller at all before the
     * real, fine-grained decision (ADMIN, or a Manager of the specific
     * team) can be made.
     *
     * <p>That fine-grained decision lives in
     * {@code OncallScheduleService.requireAdminOrTeamManager} — which
     * this controller-level test cannot exercise, because {@code service}
     * is a {@code @MockitoBean} here: whatever it's stubbed to return, it
     * returns, regardless of which principal the controller actually
     * passed it. The real Manager-vs-plain-Responder distinction is
     * covered thoroughly at the service layer instead — see
     * {@code OncallScheduleServiceTest.TeamManagerAuthorization}.
     * What THIS test class can still correctly verify: a plain RESPONDER
     * now reaches the service at all (previously it couldn't), and the
     * controller correctly returns whatever the (mocked) service decides.
     */
    @Nested
    @DisplayName("ROLE_RESPONDER — read allowed, write reaches the service " +
            "(fine-grained Manager check happens there — see OncallScheduleServiceTest)")
    class ResponderRole {

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("GET /schedules — 200 for RESPONDER")
        void getSchedules_returns200() throws Exception {
            given(service.getSchedules(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("GET /schedules/{id} — 200 for RESPONDER")
        void getById_returns200() throws Exception {
            given(service.getById(eq(SCHEDULE_ID), any()))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(get("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("POST /schedules — reaches the service for RESPONDER " +
                "(URL-level gate no longer blocks it; fine-grained Manager " +
                "check happens in the service, not tested here)")
        void create_reachesServiceForResponder() throws Exception {
            given(service.create(any(), any(), any()))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("DELETE /schedules/{id} — reaches the service for RESPONDER " +
                "(same reasoning as the create test above)")
        void delete_reachesServiceForResponder() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isNoContent());
        }
    }

    // ── ROLE_ADMIN — full access ──────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_ADMIN — full access")
    class AdminRole {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("GET /schedules — 200 for ADMIN")
        void getSchedules_returns200() throws Exception {
            given(service.getSchedules(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("POST /schedules — 201 for ADMIN")
        void create_returns201() throws Exception {
            given(service.create(any(), any(), any()))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("DELETE /schedules/{id} — 204 for ADMIN")
        void delete_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isNoContent());
        }
    }

    // ── ROLE_SERVICE — by-slack accessible, schedules forbidden ──────────

    @Nested
    @DisplayName("ROLE_SERVICE — only authenticated-only endpoints accessible")
    class ServiceRole {

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /by-slack/{id} — 204 for SERVICE (authenticated-only endpoint)")
        void findBySlackUserId_returns204ForService() throws Exception {
            given(service.findBySlackUserId(any(), any()))
                    .willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/oncall/by-slack/{id}", "U0123456789"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /schedules — 403 for SERVICE")
        void getSchedules_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("POST /schedules — 403 for SERVICE")
        void create_returns403ForService() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }
    }

    // ── GET /current — SERVICE, ADMIN only ──────────────────────────────────
    //
    // Regression coverage for the /current vs /current/all inconsistency:
    // these two paths look related but have deliberately different role
    // requirements (see SecurityConfig's class Javadoc for the reasoning),
    // and until now neither had any test coverage at all.

    @Nested
    @DisplayName("GET /current — SERVICE and ADMIN only")
    class CurrentOncallEndpoint {

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("204 for SERVICE")
        void returns204ForService() throws Exception {
            given(service.getAllCurrentOncall(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("204 for ADMIN")
        void returns204ForAdmin() throws Exception {
            given(service.getAllCurrentOncall(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("403 for RESPONDER — internal service-to-service endpoint, not for end users")
        void returns403ForResponder() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("403 for INGESTOR")
        void returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("401 without token")
        void returns401Unauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── GET /current/all — RESPONDER, ADMIN only ────────────────────────────
    //
    // Previously had no dedicated SecurityConfig rule at all — silently fell
    // through to "any authenticated user", meaning SERVICE and INGESTOR
    // could reach it too, by accident. Now explicit and narrower.

    @Nested
    @DisplayName("GET /current/all — RESPONDER and ADMIN only")
    class CurrentOncallAllEndpoint {

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("200 for RESPONDER")
        void returns200ForResponder() throws Exception {
            given(service.getAllCurrentOncallForTeam(any(), any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("200 for ADMIN")
        void returns200ForAdmin() throws Exception {
            given(service.getAllCurrentOncallForTeam(any(), any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("403 for SERVICE — no known internal caller needs this, only the frontend does")
        void returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("403 for INGESTOR")
        void returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("401 without token")
        void returns401Unauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isUnauthorized());
        }
    }

    private OncallScheduleDto buildScheduleDto() {
        return new OncallScheduleDto(
                SCHEDULE_ID, TENANT_ID, null, "user-1", "Jan Kowalski",
                "jan@example.com", "+48100200300", "U0123456789", "PRIMARY",
                Instant.now(), Instant.now().plusSeconds(86400),
                "test schedule", Instant.now()
        );
    }
}