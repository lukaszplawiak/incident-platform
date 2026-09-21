package com.incidentplatform.oncall.security;

import com.incidentplatform.oncall.api.OncallScheduleController;
import com.incidentplatform.oncall.config.SecurityConfig;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.exception.GlobalExceptionHandler;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.SharedSecurityAutoConfiguration;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real tokens, real {@code JwtAuthFilter}, real {@link SecurityConfig}, on
 * the endpoint that notification-service and escalation-service call.
 *
 * <p>Added for backlog #0-11. {@code OncallScheduleControllerSecurityTest}
 * builds its {@code Authentication} by hand and never runs the filter, so
 * it could not notice that a genuine service token was rejected before it
 * ever reached the URL rule. This test starts from the token, exactly as
 * the calling service sends it.
 */
@WebMvcTest(OncallScheduleController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=oncall-service"
})
@DisplayName("GET /current — real service tokens through the real filter")
class OncallServiceTokenAuthenticationTest {

    /**
     * Explicit configuration instead of a {@code @SpringBootApplication}
     * with component scanning: {@code OncallScheduleControllerSecurityTest}
     * (package {@code oncall.api}) declares its own scanning
     * {@code TestApplication}, and two of them reachable from one scan fail
     * every test with "Found multiple @SpringBootConfiguration". Living in
     * its own package and importing exactly what it needs avoids that.
     */
    @SpringBootConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    @Import({OncallScheduleController.class, SecurityConfig.class,
            UnauthorizedEntryPoint.class, JwtUtils.class,
            SharedSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
    static class TestConfig {
        // JwtAuthFilter itself comes from SharedSecurityAutoConfiguration —
        // the real bean, so the test runs the filter exactly as the service does.
    }

    private static final String TENANT_ID = "acme-corp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @MockitoBean
    private OncallScheduleService service;

    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    @MockitoBean
    private IncidentEventKafkaSender incidentEventKafkaSender;

    @Test
    @DisplayName("200 for a service token, and the service sees the tenant from the token")
    void serviceTokenReachesTheController() throws Exception {
        given(service.getCurrentOncall(TENANT_ID, "SECONDARY")).willReturn(Optional.of(
                new CurrentOncallResponse("user-2", "Sam", "sam@acme.com", null,
                        "+48100200300", "U123", "SECONDARY", Instant.now().plusSeconds(3600))));

        mockMvc.perform(get("/api/v1/oncall/current")
                        .param("role", "SECONDARY")
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.ONCALL_SERVICE))
                        // a caller-supplied header must not be able to change the tenant
                        .header("X-Tenant-Id", "some-other-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sam@acme.com"));

        then(service).should().getCurrentOncall(TENANT_ID, "SECONDARY");
        then(service).should(never()).getCurrentOncall("some-other-tenant", "SECONDARY");
    }

    @Test
    @DisplayName("401 for a service token issued for a different service")
    void tokenForAnotherServiceIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oncall/current")
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.INCIDENT_SERVICE)))
                .andExpect(status().isUnauthorized());

        then(service).shouldHaveNoInteractions();
    }

    // ── GET /current/by-user/{userId} (backlog #0-1) ──────────────────────

    @Test
    @DisplayName("by-user: 200 for a service token, and the lookup uses the tenant from the token")
    void byUserServiceTokenReachesTheController() throws Exception {
        given(service.findCurrentByUserId(TENANT_ID, "user-2")).willReturn(Optional.of(
                new CurrentOncallResponse("user-2", "Sam", "sam@acme.com", null,
                        "+48100200300", "U123", "SECONDARY", Instant.now().plusSeconds(3600))));

        mockMvc.perform(get("/api/v1/oncall/current/by-user/user-2")
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.ONCALL_SERVICE))
                        .header("X-Tenant-Id", "some-other-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sam@acme.com"));

        then(service).should().findCurrentByUserId(TENANT_ID, "user-2");
        then(service).should(never()).findCurrentByUserId("some-other-tenant", "user-2");
    }

    @Test
    @DisplayName("by-user: 403 for a real user token with ROLE_RESPONDER")
    void byUserResponderTokenIsForbidden() throws Exception {
        final String userToken = jwtUtils.generateToken(UUID.randomUUID(), TENANT_ID,
                "user@acme.com", List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        mockMvc.perform(get("/api/v1/oncall/current/by-user/user-2")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("by-user: 401 for a service token issued for a different service")
    void byUserTokenForAnotherServiceIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oncall/current/by-user/user-2")
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.INCIDENT_SERVICE)))
                .andExpect(status().isUnauthorized());

        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("by-user: 400 for a user id longer than the user_id column (255)")
    void byUserRejectsTooLongUserId() throws Exception {
        mockMvc.perform(get("/api/v1/oncall/current/by-user/" + "u".repeat(256))
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.ONCALL_SERVICE)))
                .andExpect(status().isBadRequest());

        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("by-user: 400 for a blank user id")
    void byUserRejectsBlankUserId() throws Exception {
        mockMvc.perform(get("/api/v1/oncall/current/by-user/%20")
                        .header("Authorization", "Bearer "
                                + jwtUtils.generateServiceToken("notification-service", TENANT_ID,
                                        ServiceNames.ONCALL_SERVICE)))
                .andExpect(status().isBadRequest());

        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("403 for a real user token with ROLE_RESPONDER")
    void responderTokenIsForbidden() throws Exception {
        final String userToken = jwtUtils.generateToken(UUID.randomUUID(), TENANT_ID,
                "user@acme.com", List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

        mockMvc.perform(get("/api/v1/oncall/current")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("401 without a token")
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/oncall/current"))
                .andExpect(status().isUnauthorized());
    }
}
