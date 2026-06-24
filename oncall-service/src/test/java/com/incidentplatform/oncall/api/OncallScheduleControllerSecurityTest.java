package com.incidentplatform.oncall.api;

import com.incidentplatform.oncall.config.SecurityConfig;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link OncallScheduleController} — verifies that
 * {@code @PreAuthorize} annotations enforce the intended role matrix:
 *
 * <pre>
 * Endpoint                        RESPONDER   ADMIN   INGESTOR   SERVICE
 * GET  /schedules                    ✅         ✅       ❌         ❌
 * GET  /schedules/{id}               ✅         ✅       ❌         ❌
 * POST /schedules                    ❌         ✅       ❌         ❌
 * DELETE /schedules/{id}             ❌         ✅       ❌         ❌
 * GET  /by-slack/{id}    authenticated only (called by service tokens)
 * GET  /current          URL-level: SERVICE or ADMIN (not tested here —
 *                        that rule lives in SecurityFilterChain, not @PreAuthorize)
 * </pre>
 *
 * <p>See {@link PostmortemControllerSecurityTest} for detailed rationale
 * on why {@code @WebMvcTest} is required for security tests.
 */
@WebMvcTest(OncallScheduleController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H"
})
@DisplayName("OncallScheduleController — security")
class OncallScheduleControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OncallScheduleService service;

    @MockitoBean
    private JwtUtils jwtUtils;

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

    // ── ROLE_INGESTOR — 403 on all schedule endpoints ────────────────────

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

    // ── ROLE_RESPONDER — read allowed, write forbidden ────────────────────

    @Nested
    @DisplayName("ROLE_RESPONDER — read allowed, write forbidden")
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
        @DisplayName("POST /schedules — 403 for RESPONDER (admin-only operation)")
        void create_returns403() throws Exception {
            // Creating schedules is restricted to ROLE_ADMIN — RESPONDER cannot
            // manage who is on-call, only view it.
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("DELETE /schedules/{id} — 403 for RESPONDER (admin-only operation)")
        void delete_returns403() throws Exception {
            // Deleting schedules is restricted to ROLE_ADMIN.
            mockMvc.perform(delete("/api/v1/oncall/schedules/{id}", SCHEDULE_ID))
                    .andExpect(status().isForbidden());
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
            given(service.create(any(), any()))
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

    // ── ROLE_SERVICE — authenticated, by-slack accessible ────────────────

    @Nested
    @DisplayName("ROLE_SERVICE — only authenticated endpoints accessible")
    class ServiceRole {

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /by-slack/{id} — 204 for SERVICE (no schedule found)")
        void findBySlackUserId_returns204ForService() throws Exception {
            // by-slack is protected only by anyRequest().authenticated() —
            // no @PreAuthorize, so ROLE_SERVICE can reach it.
            given(service.findBySlackUserId(any(), any()))
                    .willReturn(java.util.Optional.empty());

            mockMvc.perform(get("/api/v1/oncall/by-slack/{id}", "U0123456789"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /schedules — 403 for SERVICE (responder endpoint)")
        void getSchedules_returns403ForService() throws Exception {
            mockMvc.perform(get("/api/v1/oncall/schedules"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("POST /schedules — 403 for SERVICE (admin-only)")
        void create_returns403ForService() throws Exception {
            mockMvc.perform(post("/api/v1/oncall/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_REQUEST))
                    .andExpect(status().isForbidden());
        }
    }

    private OncallScheduleDto buildScheduleDto() {
        return new OncallScheduleDto(
                SCHEDULE_ID,
                TENANT_ID,
                "user-1",
                "Jan Kowalski",
                "jan@example.com",
                "+48100200300",
                "U0123456789",
                "PRIMARY",
                Instant.now(),
                Instant.now().plusSeconds(86400),
                "test schedule",
                Instant.now()
        );
    }
}