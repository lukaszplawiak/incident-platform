package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.UserManagementService;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link UserController}.
 *
 * <h2>Fixed: previously used @WithMockUser throughout</h2>
 * All 26 test methods in the previous version of this file used
 * {@code @WithMockUser}, which authenticates as a generic Spring Security
 * {@code User}, not this app's {@link UserPrincipal} record —
 * {@code @AuthenticationPrincipal UserPrincipal principal} silently
 * resolves to {@code null} on that type mismatch (Spring's default
 * {@code errorOnInvalidType=false}). {@code GetMe}, {@code DeleteUser},
 * and {@code ChangePassword} all forward {@code principal} into an
 * already-mocked service call rather than dereferencing it directly, so
 * the null value never crashed these tests — it just meant they could
 * never actually verify the correct principal reached the service; any
 * assertion on that argument could only ever use {@code any()}. Fixed
 * using the same {@code principal(String... roles)} pattern established
 * in {@code IncidentControllerSecurityTest}/{@code AuthControllerSecurityTest}
 * — a real {@link UserPrincipal}-typed {@code Authentication} — applied
 * throughout this file for consistency, not just where {@code principal}
 * happens to be dereferenced.
 *
 * <p>Also added: {@code RestoreUser} and {@code AnonymizeUser} — both
 * real endpoints ({@code POST /{id}/restore}, {@code POST /{id}/anonymize},
 * part of the GDPR archive/restore/anonymize workflow) had no test
 * coverage of any kind in the previous version of this file.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=auth-service",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("UserController")
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserQueryService userQueryService;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private PasswordService passwordService;

    @MockitoBean
    private ResendInviteService resendInviteService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    @MockitoBean
    private ApiKeyAuthFilter.ApiKeyLookupService ApiKeyLookupService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PRINCIPAL_USER_ID = UUID.randomUUID();

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
        final UserPrincipal userPrincipal = new UserPrincipal(
                PRINCIPAL_USER_ID, TENANT_ID, "admin@example.com", List.of(roles), List.of());
        final List<GrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(
                userPrincipal, null, authorities));
    }

    private static UserPrincipal buildPrincipal(String... roles) {
        return new UserPrincipal(
                PRINCIPAL_USER_ID, TENANT_ID, "admin@example.com", List.of(roles), List.of());
    }

    // ── POST /users ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /users")
    class CreateUser {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("201 for ROLE_ADMIN with Location header")
        void admin_returns201() throws Exception {
            given(userService.createUser(any())).willReturn(buildCreateResponse());

            mockMvc.perform(post("/api/v1/users")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"));
        }

        @Test
        @DisplayName("409 on duplicate email")
        void duplicateEmail_returns409() throws Exception {
            given(userService.createUser(any()))
                    .willThrow(new BusinessException(ErrorCodes.EMAIL_ALREADY_EXISTS,
                            "Email already exists", HttpStatus.CONFLICT));

            mockMvc.perform(post("/api/v1/users")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isConflict());
        }
    }

    // ── GET /users ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users")
    class ListUsers {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/users")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN")
        void admin_returns200() throws Exception {
            given(userQueryService.listUsers(any()))
                    .willReturn(PagedResponse.of(
                            List.of(buildUserSummary()), 0, 20, 1L, 1, true, true));

            mockMvc.perform(get("/api/v1/users")
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    // ── GET /users/me ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/me")
    class GetMe {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Regression test for the fix documented in this class's Javadoc:
         * verifies the EXACT principal reaches UserQueryService, not just
         * that some call happened.
         */
        @Test
        @DisplayName("200 for ROLE_RESPONDER — any authenticated user can see own profile, " +
                "with the correct principal passed through")
        void responder_returns200() throws Exception {
            given(userQueryService.getMe(eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(buildUserSummary());

            mockMvc.perform(get("/api/v1/users/me")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk());

            then(userQueryService).should().getMe(eq(buildPrincipal("ROLE_RESPONDER")));
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN")
        void admin_returns200() throws Exception {
            given(userQueryService.getMe(any())).willReturn(buildUserSummary());

            mockMvc.perform(get("/api/v1/users/me")
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }
    }

    // ── PATCH /users/{id}/roles ───────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /users/{id}/roles")
    class UpdateRoles {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/users/{id}/roles", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["ROLE_ADMIN"]}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/users/{id}/roles", USER_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["ROLE_ADMIN"]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN")
        void admin_returns200() throws Exception {
            given(userManagementService.updateRoles(eq(USER_ID), any()))
                    .willReturn(buildUserSummary());

            mockMvc.perform(patch("/api/v1/users/{id}/roles", USER_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["ROLE_ADMIN"]}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("404 when user not found")
        void userNotFound_returns404() throws Exception {
            given(userManagementService.updateRoles(any(), any()))
                    .willThrow(new ResourceNotFoundException("User", USER_ID));

            mockMvc.perform(patch("/api/v1/users/{id}/roles", USER_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["ROLE_ADMIN"]}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    // ── PATCH /users/{id}/status ──────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /users/{id}/status")
    class UpdateStatus {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/users/{id}/status", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":false}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/users/{id}/status", USER_ID)
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":false}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("200 for ROLE_ADMIN — deactivate")
        void admin_deactivate_returns200() throws Exception {
            given(userManagementService.updateStatus(eq(USER_ID), any()))
                    .willReturn(buildUserSummary());

            mockMvc.perform(patch("/api/v1/users/{id}/status", USER_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":false}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("404 when user not found")
        void userNotFound_returns404() throws Exception {
            given(userManagementService.updateStatus(any(), any()))
                    .willThrow(new ResourceNotFoundException("User", USER_ID));

            mockMvc.perform(patch("/api/v1/users/{id}/status", USER_ID)
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":false}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    // ── PATCH /users/me/password ──────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /users/me/password")
    class ChangePassword {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPass123!","newPassword":"NewPass456!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Regression test for the fix documented in this class's Javadoc:
         * verifies the EXACT principal reaches PasswordService, not just
         * that some call happened.
         */
        @Test
        @DisplayName("204 for ROLE_RESPONDER — any authenticated user can change own password, " +
                "with the correct principal passed through")
        void responder_returns204() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/password")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPass123!1","newPassword":"NewPass456!1"}
                                    """))
                    .andExpect(status().isNoContent());

            then(passwordService).should().changePassword(
                    eq(buildPrincipal("ROLE_RESPONDER")), any());
        }

        @Test
        @DisplayName("204 for ROLE_ADMIN")
        void admin_returns204() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/password")
                            .with(principal("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPass123!1","newPassword":"NewPass456!1"}
                                    """))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("400 when newPassword too short")
        void shortPassword_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/password")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPass123!","newPassword":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 when current password is wrong")
        void wrongCurrentPassword_returns401() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(ErrorCodes.UNAUTHORIZED,
                                    "Invalid credentials", HttpStatus.UNAUTHORIZED))
                    .given(passwordService).changePassword(any(), any());

            mockMvc.perform(patch("/api/v1/users/me/password")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"WrongPass!1","newPassword":"NewPass456!1"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── DELETE /users/{id} ────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /users/{id}")
    class DeleteUser {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/users/{id}", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(delete("/api/v1/users/{id}", USER_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        /**
         * Regression test for the fix documented in this class's Javadoc:
         * verifies the EXACT principal reaches UserManagementService, not
         * just that some call happened.
         */
        @Test
        @DisplayName("204 for ROLE_ADMIN — with the correct principal passed through")
        void admin_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/users/{id}", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());

            then(userManagementService).should().archiveUser(
                    eq(USER_ID), eq(buildPrincipal("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("404 when user not found")
        void userNotFound_returns404() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new ResourceNotFoundException("User", USER_ID))
                    .given(userManagementService).archiveUser(eq(USER_ID), any());

            mockMvc.perform(delete("/api/v1/users/{id}", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNotFound());
        }

        /**
         * This is the test whose original @WithMockUser version could only
         * ever assert with any() for the principal argument — see this
         * class's Javadoc. Now verifies the stub is actually keyed to the
         * correct, real principal.
         */
        @Test
        @DisplayName("403 when admin tries to delete own account")
        void selfDelete_returns403() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(
                                    ErrorCodes.FORBIDDEN,
                                    "You cannot delete your own account",
                                    HttpStatus.FORBIDDEN))
                    .given(userManagementService).archiveUser(
                            eq(USER_ID), eq(buildPrincipal("ROLE_ADMIN")));

            mockMvc.perform(delete("/api/v1/users/{id}", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isForbidden());
        }
    }

    // ── POST /users/{id}/restore (previously completely untested) ───────────

    @Nested
    @DisplayName("POST /users/{id}/restore")
    class RestoreUser {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/restore", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/restore", USER_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("204 for ROLE_ADMIN — with the correct principal passed through")
        void admin_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/restore", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());

            then(userManagementService).should().restoreUser(
                    eq(USER_ID), eq(buildPrincipal("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("404 when user not found or not archived")
        void userNotFound_returns404() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new ResourceNotFoundException("User", USER_ID))
                    .given(userManagementService).restoreUser(eq(USER_ID), any());

            mockMvc.perform(post("/api/v1/users/{id}/restore", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("409 when user is anonymized — cannot restore")
        void anonymized_returns409() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(
                                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                                    "User is anonymized and cannot be restored",
                                    HttpStatus.CONFLICT))
                    .given(userManagementService).restoreUser(eq(USER_ID), any());

            mockMvc.perform(post("/api/v1/users/{id}/restore", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isConflict());
        }
    }

    // ── POST /users/{id}/anonymize (previously completely untested) ─────────

    @Nested
    @DisplayName("POST /users/{id}/anonymize")
    class AnonymizeUser {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/anonymize", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/anonymize", USER_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("204 for ROLE_ADMIN — with the correct principal passed through")
        void admin_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/anonymize", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNoContent());

            then(userManagementService).should().anonymizeUser(
                    eq(USER_ID), eq(buildPrincipal("ROLE_ADMIN")));
        }

        @Test
        @DisplayName("404 when user not found")
        void userNotFound_returns404() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new ResourceNotFoundException("User", USER_ID))
                    .given(userManagementService).anonymizeUser(eq(USER_ID), any());

            mockMvc.perform(post("/api/v1/users/{id}/anonymize", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("409 when user is active (must archive first) or already anonymized")
        void invalidState_returns409() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(
                                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                                    "User must be archived first",
                                    HttpStatus.CONFLICT))
                    .given(userManagementService).anonymizeUser(eq(USER_ID), any());

            mockMvc.perform(post("/api/v1/users/{id}/anonymize", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isConflict());
        }
    }

    // ── POST /users/{id}/resend-invite ────────────────────────────────────

    @Nested
    @DisplayName("POST /users/{id}/resend-invite")
    class ResendInvite {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID)
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("202 Accepted for ROLE_ADMIN")
        void admin_returns202() throws Exception {
            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("404 when user not found")
        void userNotFound_returns404() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new ResourceNotFoundException("User", USER_ID))
                    .given(resendInviteService).resendInvite(USER_ID);

            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("409 when invite already accepted")
        void inviteAlreadyAccepted_returns409() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(
                                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                                    "User has already accepted the invitation",
                                    HttpStatus.CONFLICT))
                    .given(resendInviteService).resendInvite(USER_ID);

            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("409 when email already PENDING dispatch")
        void emailAlreadyPending_returns409() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(
                                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                                    "An invite email is already queued",
                                    HttpStatus.CONFLICT))
                    .given(resendInviteService).resendInvite(USER_ID);

            mockMvc.perform(post("/api/v1/users/{id}/resend-invite", USER_ID)
                            .with(principal("ROLE_ADMIN")))
                    .andExpect(status().isConflict());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private CreateUserResponse buildCreateResponse() {
        return new CreateUserResponse(
                UUID.randomUUID(), TENANT_ID, "u@example.com",
                List.of("ROLE_RESPONDER"), true, Instant.now());
    }

    private UserSummaryDto buildUserSummary() {
        return new UserSummaryDto(
                UUID.randomUUID(), TENANT_ID, "user@example.com",
                List.of("ROLE_RESPONDER"), List.of(), true, false, Instant.now(), Instant.now());
    }
}