package com.incidentplatform.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.domain.MfaVerificationMethod;
import com.incidentplatform.auth.dto.RequestMfaRecoveryRequest;
import com.incidentplatform.auth.ratelimit.PlatformRateLimiter;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.JwtProperties;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may ask for, list and cancel an MFA recovery of a customer tenant's only
 * admin (backlog #0-90): exactly who may use the rest of the platform API, an
 * operator admin with a JWT of a session that passes the MFA rule. Real tokens
 * through the real {@link SecurityConfig}, as in
 * {@code PlatformTenantControllerSecurityTest}.
 */
@WebMvcTest(PlatformMfaRecoveryController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class, JwtUtils.class,
        PlatformMfaRecoveryControllerSecurityTest.JwtPropertiesConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("PlatformMfaRecoveryController — security")
class PlatformMfaRecoveryControllerSecurityTest {

    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }

    private static final String RECOVERY = "/api/v1/platform/tenants/acme/mfa-recovery";
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final String CANCEL = "/api/v1/platform/mfa-recovery/" + REQUEST_ID + "/cancel";
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID MFA_SESSION = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MfaRecoveryService recoveryService;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;
    @MockitoBean private MfaSessionStatusService mfaSessionStatus;
    @MockitoBean private PlatformRateLimiter rateLimiter;

    @BeforeEach
    void mfaSessionAndLimit() {
        given(mfaSessionStatus.check(any(), any(), any())).willReturn(MfaSessionStatusService.Status.NO_MFA);
        given(mfaSessionStatus.check(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR, MFA_SESSION))
                .willReturn(MfaSessionStatusService.Status.ACCEPTED);
        given(rateLimiter.tryConsume(any())).willReturn(new RateLimitDecision(RateLimitDecision.Outcome.ALLOWED, 0));
        given(recoveryService.waitingPeriod()).willReturn(Duration.ofHours(72));
    }

    private String token(String tenantId, UUID session, String... roles) {
        return jwtUtils.generateToken(OPERATOR_ID, tenantId, "ops@platform.test",
                List.of(roles), List.of(), List.of(), session);
    }

    private String operatorAdmin() {
        return token(ReservedTenants.PLATFORM_OPERATOR, MFA_SESSION, SecurityRoles.ROLE_ADMIN);
    }

    private MockHttpServletRequestBuilder requestAs(String bearer) throws Exception {
        final MockHttpServletRequestBuilder request = post(RECOVERY).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RequestMfaRecoveryRequest(
                        ADMIN_ID, MfaVerificationMethod.KNOWN_PHONE_CALLBACK, "Called +48 number from contract")));
        return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
    }

    private void assertForbiddenEverywhere(String bearer) throws Exception {
        mockMvc.perform(requestAs(bearer)).andExpect(status().isForbidden());
        mockMvc.perform(get(RECOVERY).header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post(CANCEL).header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(rateLimiter);
        then(recoveryService).should(org.mockito.Mockito.never()).request(any(), any(), any(), any(), any());
        then(recoveryService).should(org.mockito.Mockito.never()).cancelByOperator(any(), any());
        then(recoveryService).should(org.mockito.Mockito.never()).list(any(), any());
    }

    @Test
    @DisplayName("operator admin: request — 202 with the request, a limit token used")
    void operatorRequests() throws Exception {
        final MfaRecoveryRequest created = MfaRecoveryRequest.open("acme", ADMIN_ID, OPERATOR_ID,
                MfaVerificationMethod.KNOWN_PHONE_CALLBACK, "Called +48 number from contract", Instant.now());
        given(recoveryService.request(eq("acme"), eq(ADMIN_ID), eq(MfaVerificationMethod.KNOWN_PHONE_CALLBACK),
                eq("Called +48 number from contract"), any(UserPrincipal.class))).willReturn(created);

        mockMvc.perform(requestAs(operatorAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.executeNotBefore").doesNotExist());
        then(rateLimiter).should().tryConsume(OPERATOR_ID);
    }

    @Test
    @DisplayName("operator admin: list and cancel — 200 and 204, not limited")
    void operatorListsAndCancels() throws Exception {
        given(recoveryService.list(eq("acme"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(RECOVERY).header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isOk());
        mockMvc.perform(post(CANCEL).header("Authorization", "Bearer " + operatorAdmin()))
                .andExpect(status().isNoContent());
        then(recoveryService).should().cancelByOperator(eq(REQUEST_ID), any(UserPrincipal.class));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("no token — 401")
    void noToken() throws Exception {
        mockMvc.perform(requestAs(null)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CANCEL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin of a customer tenant, even of the tenant itself — 403 everywhere")
    void customerAdminForbidden() throws Exception {
        assertForbiddenEverywhere(token("acme", MFA_SESSION, SecurityRoles.ROLE_ADMIN));
    }

    @Test
    @DisplayName("non-admin of the operator tenant — 403")
    void operatorNonAdminForbidden() throws Exception {
        assertForbiddenEverywhere(token(ReservedTenants.PLATFORM_OPERATOR, MFA_SESSION, SecurityRoles.ROLE_RESPONDER));
    }

    @Test
    @DisplayName("operator admin without a recent MFA login, or with a factor too new — 403")
    void operatorWithoutMfaForbidden() throws Exception {
        assertForbiddenEverywhere(token(ReservedTenants.PLATFORM_OPERATOR, UUID.randomUUID(), SecurityRoles.ROLE_ADMIN));
        final UUID newFactorSession = UUID.randomUUID();
        given(mfaSessionStatus.check(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR, newFactorSession))
                .willReturn(MfaSessionStatusService.Status.MFA_ENROLLED_TOO_RECENTLY);
        assertForbiddenEverywhere(token(ReservedTenants.PLATFORM_OPERATOR, newFactorSession, SecurityRoles.ROLE_ADMIN));
    }

    @Test
    @DisplayName("service token in the operator tenant — 403")
    void serviceTokenForbidden() throws Exception {
        assertForbiddenEverywhere(jwtUtils.generateServiceToken(
                "notification-service", ReservedTenants.PLATFORM_OPERATOR, ServiceNames.AUTH_SERVICE));
    }

    @Test
    @DisplayName("API key of an operator admin — 403")
    void apiKeyForbidden() throws Exception {
        final UserPrincipal keyPrincipal = new UserPrincipal(OPERATOR_ID, ReservedTenants.PLATFORM_OPERATOR,
                "ops@platform.test", List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), true,
                List.of("teams:write"), null);
        given(apiKeyLookupService.lookup(anyString(), any()))
                .willReturn(new ApiKeyLookupResult.Authenticated(keyPrincipal));
        assertForbiddenEverywhere("ipl_operator_personal_key");
    }

    @Test
    @DisplayName("limit reached — 429, cannot be checked — 503, nothing requested")
    void rateLimited() throws Exception {
        given(rateLimiter.tryConsume(OPERATOR_ID)).willReturn(
                new RateLimitDecision(RateLimitDecision.Outcome.LIMITED, 180),
                new RateLimitDecision(RateLimitDecision.Outcome.UNAVAILABLE, 30));

        mockMvc.perform(requestAs(operatorAdmin()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "180"));
        mockMvc.perform(requestAs(operatorAdmin()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"));
        then(recoveryService).should(org.mockito.Mockito.never()).request(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a body without a method or note — 400 before the limit or the service")
    void invalidBody() throws Exception {
        mockMvc.perform(post(RECOVERY).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + operatorAdmin())
                        .content("{\"userId\":\"" + ADMIN_ID + "\",\"verificationNote\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("the service's refusal — its status and error code")
    void serviceRefusal() throws Exception {
        willThrow(new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION, "another active admin",
                HttpStatus.CONFLICT)).given(recoveryService).request(any(), any(), any(), any(), any());

        mockMvc.perform(requestAs(operatorAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.BUSINESS_RULE_VIOLATION));
    }
}
