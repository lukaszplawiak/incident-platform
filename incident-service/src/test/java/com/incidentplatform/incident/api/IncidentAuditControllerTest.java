package com.incidentplatform.incident.api;

import com.incidentplatform.incident.config.SecurityConfig;
import com.incidentplatform.incident.dto.AuditEventDto;
import com.incidentplatform.incident.service.AuditQueryService;
import com.incidentplatform.shared.dto.PagedResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentAuditController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H",
        "spring.application.name=incident-service"
})
@DisplayName("IncidentAuditController")
class IncidentAuditControllerTest {

    @org.springframework.context.annotation.Import(com.incidentplatform.incident.config.SecurityConfig.class)
    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.incident.api",
            "com.incidentplatform.incident.config",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception",
            "com.incidentplatform.shared.observability"
    })
    static class TestApplication {

        /**
         * Provides a real JwtAuthFilter bean wired with the mocked JwtUtils.
         * Using @MockitoBean for JwtAuthFilter would create a no-op stub that
         * skips filterChain.doFilter() — breaking Spring Security entirely.
         * A real JwtAuthFilter with mocked JwtUtils validates tokens correctly
         * (all return empty Optional → unauthenticated) while letting
         * @WithMockUser set SecurityContext directly, bypassing the filter.
         */
        @org.springframework.context.annotation.Bean
        public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
            return new JwtAuthFilter(jwtUtils);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditQueryService auditQueryService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

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

    // ── security ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("security")
    class Security {

        @Test
        @DisplayName("GET /{incidentId}/audit — 401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "INGESTOR")
        @DisplayName("GET /{incidentId}/audit — 403 for ROLE_INGESTOR")
        void ingestor_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("GET /{incidentId}/audit — 403 for ROLE_SERVICE")
        void service_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("GET /{incidentId}/audit — 200 for ROLE_RESPONDER")
        void responder_returns200() throws Exception {
            given(auditQueryService.getAuditLog(
                    eq(INCIDENT_ID), any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("GET /{incidentId}/audit — 200 for ROLE_ADMIN")
        void admin_returns200() throws Exception {
            given(auditQueryService.getAuditLog(
                    eq(INCIDENT_ID), any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isOk());
        }
    }

    // ── pagination response ───────────────────────────────────────────────

    @Nested
    @DisplayName("pagination")
    class Pagination {

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("returns PagedResponse with correct metadata")
        void returnsPagedResponseMetadata() throws Exception {
            final AuditEventDto event = buildAuditEventDto();
            final Page<AuditEventDto> page = new PageImpl<>(
                    List.of(event), PageRequest.of(0, 50), 1L);

            given(auditQueryService.getAuditLog(
                    eq(INCIDENT_ID), any(), any(Pageable.class)))
                    .willReturn(page);

            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(true))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].eventType")
                            .value("INCIDENT_CREATED"));
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("returns empty PagedResponse when no events")
        void returnsEmptyPage() throws Exception {
            given(auditQueryService.getAuditLog(
                    eq(INCIDENT_ID), any(), any(Pageable.class)))
                    .willReturn(Page.empty());

            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("default page size is 50")
        void defaultPageSizeIs50() throws Exception {
            given(auditQueryService.getAuditLog(
                    eq(INCIDENT_ID), any(), any(Pageable.class)))
                    .willAnswer(inv -> {
                        final Pageable pageable = inv.getArgument(2);
                        assertDefaultPageSize(pageable);
                        return Page.empty();
                    });

            mockMvc.perform(get("/api/v1/incidents/{id}/audit", INCIDENT_ID))
                    .andExpect(status().isOk());
        }

        private void assertDefaultPageSize(Pageable pageable) {
            if (pageable.getPageSize() != 50) {
                throw new AssertionError(
                        "Expected default page size 50 but was " +
                                pageable.getPageSize());
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AuditEventDto buildAuditEventDto() {
        return new AuditEventDto(
                UUID.randomUUID(),
                INCIDENT_ID,
                "INCIDENT_CREATED",
                "system",
                "SYSTEM",
                "incident-service",
                "Incident created",
                null,
                Instant.now()
        );
    }
}