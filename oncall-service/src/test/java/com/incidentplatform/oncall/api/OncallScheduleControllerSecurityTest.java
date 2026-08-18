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
import com.incidentplatform.shared.security.UserPrincipal;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
 * POST /schedules                  reaches      ✅       ❌         ❌
 *                                  service*
 * DELETE /schedules/{id}           reaches      ✅       ❌         ❌
 *                                  service*
 * GET  /by-slack/{id}    authenticated only (ROLE_SERVICE allowed)
 * GET  /current                      ❌         ✅       ❌         ✅
 * GET  /current/all                  ✅         ✅       ❌         ❌
 * </pre>
 *
 * <p>*POST/DELETE /schedules: URL-level gate admits RESPONDER (every
 * {@code TeamRole.MANAGER} also holds {@code ROLE_RESPONDER} — see
 * {@link OncallScheduleController#create}'s Javadoc), but the real
 * fine-grained decision (ADMIN, or a Manager of the specific team) is
 * made in {@code OncallScheduleService.requireAdminOrTeamManager}, not
 * testable here since {@code service} is mocked — see
 * {@code OncallScheduleServiceTest.TeamManagerAuthorization} for that
 * coverage. Fixed: this table previously still showed RESPONDER as ❌ for
 * both, left over from before the Manager role feature relaxed the
 * URL-level gate — a stale table, not just stale tests (see below).
 *
 * <p>Unauthenticated requests return {@code 401 Unauthorized} via
 * {@link UnauthorizedEntryPoint} registered in
 * {@link com.incidentplatform.shared.security.SharedSecurityAutoConfiguration#buildCommonSecurity}.
 *
 * <h2>Fixed: previously used @WithMockUser throughout</h2>
 * All 21 {@code @WithMockUser}-based test methods in the previous version
 * of this file authenticated as a generic Spring Security {@code User},
 * not this app's {@link UserPrincipal} record —
 * {@code @AuthenticationPrincipal UserPrincipal principal} silently
 * resolves to {@code null} on that type mismatch (Spring's default
 * {@code errorOnInvalidType=false}). Of this controller's methods, only
 * {@code create} and {@code delete} actually take
 * {@code @AuthenticationPrincipal UserPrincipal principal} and forward it
 * to the (mocked) service — so the null value never crashed those four
 * tests ({@code ResponderRole.create/delete},
 * {@code AdminRole.create/delete}), it just meant any assertion on that
 * argument could only ever use {@code any()}, never proving the correct
 * principal actually reached the service. Fixed using the same
 * {@code principal(String... roles)} pattern already established in
 * {@code IncidentControllerSecurityTest}/{@code AuthControllerSecurityTest}/
 * {@code UserControllerSecurityTest} — a real {@link UserPrincipal}-typed
 * {@code Authentication} — applied throughout this file for consistency,
 * not just in the four tests where it was strictly necessary.
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
    private static final UUID PRINCIPAL_USER_ID = UUID.randomUUID();

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

    // Backlog #43 — same shape as VALID_CREATE_REQUEST, since
    // UpdateOncallScheduleRequest deliberately mirrors
    // CreateOncallScheduleRequest's fields exactly.
    private static final String VALID_SUPERSEDE_REQUEST = """
            {
              "userId": "user-2",
              "userName": "Anna Nowak",
              "email": "anna@example.com",
              "phone": "+48100200301",
              "slackUserId": "U9876543210",
              "role": "SECONDARY",
              "startsAt": "2099-01-01T00:00:00Z",
              "endsAt": "2099-01-08T00:00:00Z",
              "notes": "replacement"
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

    /**
     * Builds a real {@link UserPrincipal}-typed authentication for one or
     * more roles, applied to a single {@code mockMvc.perform(...)} call via
     * {@code .with(principal("ROLE_RESPONDER"))}. See this class's Javadoc
     * for why this replaces {@code @WithMockUser} throughout this file.
     */
    private static RequestPostProcessor principal(String... roles) {
        final UserPrincipal userPrincipal = buildPrincipal(roles);
        final List<GrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(
                userPrincipal, null, authorities));
    }

    private static UserPrincipal buildPrincipal(String... roles) {
        return new UserPrincipal(
                PRINCIPAL_USER_ID, TENANT_ID, "user@example.com", List.of(roles), List.of());
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

        @Test
        @DisplayName("POST /schedules/{id}/supersede — 401 without token")
        void supersede_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules/{id}/supersede", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_SUPERSEDE_REQUEST))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── ROLE_INGESTOR — 403 ───────────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_INGESTOR — forbidden on all schedule endpoints")
    class IngestorRole {

        @Test
        @DisplayName("GET /schedules — 403 for INGESTOR")
        void getSchedules_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules")
                            .with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /schedules — 403 for INGESTOR")
        void create_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .with(principal("ROLE_INGESTOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /schedules/{id} — 403 for INGESTOR")
        void delete_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID)
                            .with(principal("ROLE_INGESTOR")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /schedules/{id}/supersede — 403 for INGESTOR")
        void supersede_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules/{id}/supersede", SCHEDULE_ID)
                            .with(principal("ROLE_INGESTOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_SUPERSEDE_REQUEST))
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
     * {@code OncallScheduleServiceTest.TeamManagerAuthorization}. What
     * THIS test class can still correctly verify, now that it uses a real
     * {@code UserPrincipal} instead of {@code @WithMockUser}: a plain
     * RESPONDER reaches the service at all (previously it couldn't), AND
     * that the exact, correct principal is what the controller actually
     * forwards to it — not just {@code any()}.
     */
    @Nested
    @DisplayName("ROLE_RESPONDER — read allowed, write reaches the service " +
            "(fine-grained Manager check happens there — see OncallScheduleServiceTest)")
    class ResponderRole {

        @Test
        @DisplayName("GET /schedules — 200 for RESPONDER")
        void getSchedules_returns200() throws Exception {
            given(service.getSchedules(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/oncall/schedules")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /schedules/{id} — 200 for RESPONDER")
        void getById_returns200() throws Exception {
            given(service.getById(eq(SCHEDULE_ID), any()))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(get("/api/v1/oncall/schedules/{id}", SCHEDULE_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());
        }
        @Test
        @DisplayName("POST /schedules — reaches the service for RESPONDER with the " +
                "correct principal (URL-level gate no longer blocks it; fine-grained " +
                "Manager check happens in the service, not tested here)")
        void create_reachesServiceForResponder() throws Exception {
            given(service.create(any(), any(), eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isCreated());

            then(service).should().create(
                    any(), any(), eq(buildPrincipal("ROLE_RESPONDER")));
        }

        @Test
        @DisplayName("DELETE /schedules/{id} — reaches the service for RESPONDER with the " +
                "correct principal (same reasoning as the create test above)")
        void delete_reachesServiceForResponder() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isNoContent());

            then(service).should().delete(
                    eq(SCHEDULE_ID), any(), eq(buildPrincipal("ROLE_RESPONDER")));
        }

        @Test
        @DisplayName("POST /schedules/{id}/supersede — reaches the service for RESPONDER " +
                "with the correct principal (same reasoning as the create test above)")
        void supersede_reachesServiceForResponder() throws Exception {
            given(service.supersede(eq(SCHEDULE_ID), any(), any(),
                    eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules/{id}/supersede", SCHEDULE_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_SUPERSEDE_REQUEST))
                    .andExpect(status().isOk());

            then(service).should().supersede(
                    eq(SCHEDULE_ID), any(), any(), eq(buildPrincipal("ROLE_RESPONDER")));
        }
    }

    // ── ROLE_ADMIN — full access ──────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_ADMIN — full access")
    class AdminRole {

        @Test
        @DisplayName("GET /schedules — 200 for ADMIN")
        void getSchedules_returns200() throws Exception {
            given(service.getSchedules(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/oncall/schedules")
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /schedules — 201 for ADMIN with the correct principal")
        void create_returns201() throws Exception {
            given(service.create(any(), any(), eq(buildPrincipal("ROLE_ADMIN"))))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules")
                    .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isCreated());

            then(service).should().create(
                    any(), any(), eq(buildPrincipal("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("DELETE /schedules/{id} — 204 for ADMIN with the correct principal")
        void delete_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());

            then(service).should().delete(
                    eq(SCHEDULE_ID), any(), eq(buildPrincipal("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("POST /schedules/{id}/supersede — 200 for ADMIN with the correct principal")
        void supersede_returns200() throws Exception {
            given(service.supersede(eq(SCHEDULE_ID), any(), any(),
                    eq(buildPrincipal("ROLE_ADMIN"))))
                    .willReturn(buildScheduleDto());

            mockMvc.perform(post("/api/v1/oncall/schedules/{id}/supersede", SCHEDULE_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_SUPERSEDE_REQUEST))
                    .andExpect(status().isOk());

            then(service).should().supersede(
                    eq(SCHEDULE_ID), any(), any(), eq(buildPrincipal("ROLE_ADMIN")));
        }
    }

    // ── ROLE_SERVICE — by-slack accessible, schedules forbidden ──────────

    @Nested
    @DisplayName("ROLE_SERVICE — only authenticated-only endpoints accessible")
    class ServiceRole {

        @Test
        @DisplayName("GET /by-slack/{id} — 204 for SERVICE (authenticated-only endpoint)")
        void findBySlackUserId_returns204ForService() throws Exception {
            given(service.findBySlackUserId(any(), any()))
                    .willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/oncall/by-slack/{id}", "U0123456789")
                            .with(principal("ROLE_SERVICE")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("GET /schedules — 403 for SERVICE")
        void getSchedules_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules")
                            .with(principal("ROLE_SERVICE")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /schedules — 403 for SERVICE")
        void create_returns403ForService() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .with(principal("ROLE_SERVICE"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /schedules/{id}/supersede — 403 for SERVICE")
        void supersede_returns403ForService() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules/{id}/supersede", SCHEDULE_ID)
                            .with(principal("ROLE_SERVICE"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_SUPERSEDE_REQUEST))
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
        @DisplayName("204 for SERVICE")
        void returns204ForService() throws Exception {
            given(service.getAllCurrentOncall(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current")
                            .with(principal("ROLE_SERVICE")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204 for ADMIN")
        void returns204ForAdmin() throws Exception {
            given(service.getAllCurrentOncall(any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current")
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("403 for RESPONDER — internal service-to-service endpoint, not for end users")
        void returns403ForResponder() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("403 for INGESTOR")
        void returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current")
                            .with(principal("ROLE_INGESTOR")))
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
        @DisplayName("200 for RESPONDER")
        void returns200ForResponder() throws Exception {
            given(service.getAllCurrentOncallForTeam(any(), any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .with(principal("ROLE_RESPONDER"))
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 for ADMIN")
        void returns200ForAdmin() throws Exception {
            given(service.getAllCurrentOncallForTeam(any(), any())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .with(principal("ROLE_ADMIN"))
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("403 for SERVICE — no known internal caller needs this, only the frontend does")
        void returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .with(principal("ROLE_SERVICE"))
                            .param("teamId", UUID.randomUUID().toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("403 for INGESTOR")
        void returns403ForIngestor() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/current/all")
                            .with(principal("ROLE_INGESTOR"))
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
                "test schedule", Instant.now(), "ACTIVE", null
        );
    }
}