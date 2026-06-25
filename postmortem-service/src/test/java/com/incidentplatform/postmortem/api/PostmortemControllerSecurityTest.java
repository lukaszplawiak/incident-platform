package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.config.SecurityConfig;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TenantContext;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link PostmortemController}.
 *
 * <h2>Why a nested @SpringBootApplication</h2>
 * {@code PostmortemServiceApplication} carries
 * {@code @ComponentScan(basePackages = {"com.incidentplatform.postmortem",
 * "com.incidentplatform.shared"})}, which causes {@code @WebMvcTest} to
 * discover every {@code @Component} in both packages — including Kafka senders
 * ({@code AuditEventKafkaSender}, {@code IncidentEventKafkaSender}) that
 * require {@code KafkaTemplate}, and {@code ShedLockConfig} that requires
 * {@code DataSource}. Neither is available in the web slice, so the context
 * fails to load regardless of how many {@code @MockitoBean}s or
 * {@code excludeFilters} are added.
 *
 * The fix is to provide a minimal {@code @SpringBootApplication} class
 * <em>inside the test</em>, scoped only to the packages actually needed for
 * the web slice. Spring Boot's {@code @WebMvcTest} bootstrapper prefers the
 * nearest {@code @SpringBootConfiguration} — the inner class wins over
 * {@code PostmortemServiceApplication}, so no problematic beans are
 * auto-scanned.
 */
@WebMvcTest(PostmortemController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H",
        "spring.application.name=postmortem-service"
})
@DisplayName("PostmortemController — security")
class PostmortemControllerSecurityTest {

    /**
     * Minimal Spring Boot application scoped only to the web + security
     * packages. Replaces {@code PostmortemServiceApplication} as the
     * bootstrap configuration for this test slice, preventing the broad
     * {@code @ComponentScan} from pulling in Kafka and ShedLock beans.
     */
    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.postmortem.api",
            "com.incidentplatform.postmortem.config",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostmortemService postmortemService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    // These shared @Components are discovered even in the narrow scan
    // because shared.security imports them transitively — mock them
    // to avoid requiring Kafka infrastructure.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    @MockitoBean
    private IncidentEventKafkaSender incidentEventKafkaSender;

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

    // ── Unauthenticated — 403 (no AuthenticationEntryPoint configured) ─────

    @Nested
    @DisplayName("unauthenticated requests — 403 (no JWT, no entry point)")
    class Unauthenticated {

        @Test
        @DisplayName("GET /postmortems — 403 without token (no AuthenticationEntryPoint)")
        void listPostmortems_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /postmortems/incident/{id} — 403 without token")
        void getByIncidentId_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/postmortems/incident/{id}", INCIDENT_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /postmortems/incident/{id} — 403 without token")
        void updateContent_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/postmortems/incident/{id}", INCIDENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"updated\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── ROLE_INGESTOR — 403 ───────────────────────────────────────────────

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