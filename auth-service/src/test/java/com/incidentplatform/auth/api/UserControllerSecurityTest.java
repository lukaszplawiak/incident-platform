package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H",
        "spring.application.name=auth-service"
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
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── POST /users — security ────────────────────────────────────────────

    @Nested
    @DisplayName("POST /users — security")
    class CreateUserSecurity {

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
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("201 for ROLE_ADMIN with Location header")
        void admin_returns201() throws Exception {
            given(userService.createUser(any())).willReturn(buildCreateResponse());

            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("409 on duplicate email")
        void duplicateEmail_returns409() throws Exception {
            given(userService.createUser(any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.EMAIL_ALREADY_EXISTS,
                            "Email already exists",
                            HttpStatus.CONFLICT));

            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"u@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isConflict());
        }
    }

    // ── GET /users — security ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users — security")
    class ListUsersSecurity {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("200 for ROLE_ADMIN with paged response")
        void admin_returns200() throws Exception {
            given(userQueryService.listUsers(any()))
                    .willReturn(PagedResponse.of(
                            List.of(buildUserSummary()), 0, 20, 1L, 1, true, true));

            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].email")
                            .value("user@example.com"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("200 with empty page when no users")
        void admin_emptyPage() throws Exception {
            given(userQueryService.listUsers(any()))
                    .willReturn(PagedResponse.of(
                            List.of(), 0, 20, 0L, 0, true, true));

            mockMvc.perform(get("/api/v1/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ── GET /users/me — security ──────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/me — security")
    class GetMeSecurity {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("200 for ROLE_RESPONDER — own profile accessible to any role")
        void responder_returns200() throws Exception {
            given(userQueryService.getMe(any())).willReturn(buildUserSummary());

            mockMvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("user@example.com"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("200 for ROLE_ADMIN")
        void admin_returns200() throws Exception {
            given(userQueryService.getMe(any())).willReturn(buildUserSummary());

            mockMvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isOk());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private CreateUserResponse buildCreateResponse() {
        return new CreateUserResponse(
                UUID.randomUUID(), TENANT_ID, "u@example.com",
                List.of("ROLE_RESPONDER"), true, Instant.now(),
                "invite-token", Instant.now().plusSeconds(72 * 3600));
    }

    private UserSummaryDto buildUserSummary() {
        return new UserSummaryDto(
                UUID.randomUUID(), TENANT_ID, "user@example.com",
                List.of("ROLE_RESPONDER"), true,
                Instant.now(), Instant.now());
    }
}