package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.config.SecurityConfig;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.security.JwtAuthFilter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link PostmortemController} — verifies that
 * {@code @PreAuthorize} annotations are enforced correctly and that
 * the defense-in-depth model (Layer 1: authenticated, Layer 2: role check)
 * works as intended.
 *
 * <h2>Why @WebMvcTest, not plain Mockito unit tests</h2>
 * {@code @PreAuthorize} is implemented via Spring AOP proxies. When a test
 * instantiates a controller directly ({@code new PostmortemController(...)})
 * and calls its methods, no proxy is involved — the annotation is silently
 * ignored. Role checks can only be tested through the full Spring MVC
 * dispatch pipeline, which is what {@code @WebMvcTest} provides without
 * loading the entire application context (no database, no Kafka).
 *
 * <h2>Why @TestPropertySource</h2>
 * {@link JwtUtils} requires {@code jwt.secret} to initialise. In a
 * {@code @WebMvcTest} slice the application.yml is loaded but environment
 * variables are not available, so the property must be supplied via
 * {@code @TestPropertySource}. {@link JwtAuthFilter} is still present in
 * the security filter chain but {@code @WithMockUser} bypasses it entirely
 * by pre-populating the {@link org.springframework.security.core.context.SecurityContext}
 * before the request reaches any filter — the filter runs but finds an
 * already-authenticated context and sets no new authentication.
 */
@WebMvcTest(PostmortemController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H"
})
@DisplayName("PostmortemController — security")
class PostmortemControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostmortemService postmortemService;

    // JwtUtils is a @Component in shared — it is auto-discovered and requires
    // jwt.secret; we let Spring create the real bean (supplied via
    // @TestPropertySource above) so that JwtAuthFilter initialises correctly.
    // JwtAuthFilter itself is a @Component and is registered automatically.
    @MockitoBean
    private JwtUtils jwtUtils;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

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
        @DisplayName("GET /postmortems — 401 without token")
        void listPostmortems_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /postmortems/incident/{id} — 401 without token")
        void getByIncidentId_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems/incident/{id}", INCIDENT_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PATCH /postmortems/incident/{id} — 401 without token")
        void updateContent_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── ROLE_INGESTOR — 403 (authenticated but wrong role) ───────────────

    @Nested
    @DisplayName("ROLE_INGESTOR — forbidden on all endpoints")
    class IngestorRole {

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("GET /postmortems — 403 for INGESTOR")
        void listPostmortems_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("GET /postmortems/incident/{id} — 403 for INGESTOR")
        void getByIncidentId_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems/incident/{id}", INCIDENT_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("PATCH /postmortems/incident/{id} — 403 for INGESTOR")
        void updateContent_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ROLE_SERVICE — 403 ───────────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_SERVICE — forbidden on all endpoints")
    class ServiceRole {

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /postmortems — 403 for SERVICE")
        void listPostmortems_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("PATCH /postmortems/incident/{id} — 403 for SERVICE")
        void updateContent_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ROLE_RESPONDER — 200 ─────────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_RESPONDER — allowed on all endpoints")
    class ResponderRole {

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("GET /postmortems — 200 for RESPONDER")
        void listPostmortems_returns200() throws Exception {
            given(postmortemService.getPostmortems(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("GET /postmortems/incident/{id} — 200 for RESPONDER")
        void getByIncidentId_returns200() throws Exception {
            given(postmortemService.getByIncidentId(eq(INCIDENT_ID), any()))
                    .willReturn(buildPostmortemDto());

            mockMvc.perform(get("/api/v1/postmortems/incident/{id}", INCIDENT_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("PATCH /postmortems/incident/{id} — 200 for RESPONDER")
        void updateContent_returns200() throws Exception {
            given(postmortemService.updateContent(eq(INCIDENT_ID), any(), any()))
                    .willReturn(buildPostmortemDto());

            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated postmortem content\"}"))
                    .andExpect(status().isOk());
        }
    }

    // ── ROLE_ADMIN — 200 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ROLE_ADMIN — allowed on all endpoints")
    class AdminRole {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("GET /postmortems — 200 for ADMIN")
        void listPostmortems_returns200() throws Exception {
            given(postmortemService.getPostmortems(any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("PATCH /postmortems/incident/{id} — 200 for ADMIN")
        void updateContent_returns200() throws Exception {
            given(postmortemService.updateContent(eq(INCIDENT_ID), any(), any()))
                    .willReturn(buildPostmortemDto());

            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated postmortem content\"}"))
                    .andExpect(status().isOk());
        }
    }

    private PostmortemDto buildPostmortemDto() {
        return new PostmortemDto(
                UUID.randomUUID(),
                INCIDENT_ID,
                TENANT_ID,
                "High CPU Usage",
                "CRITICAL",
                Instant.now().minusSeconds(1800),
                Instant.now(),
                30,
                "DRAFT",
                "## Summary\nTest content",
                null,
                Instant.now(),
                Instant.now()
        );
    }
}