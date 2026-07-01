package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.service.UserService;
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

    // ── security ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("security")
    class Security {

        @Test
        @DisplayName("POST /users — 401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "RESPONDER")
        @DisplayName("POST /users — 403 for ROLE_RESPONDER")
        void responder_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "SERVICE")
        @DisplayName("POST /users — 403 for ROLE_SERVICE")
        void service_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("POST /users — 201 for ROLE_ADMIN")
        void admin_returns201() throws Exception {
            given(userService.createUser(any()))
                    .willReturn(buildResponse());

            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isCreated());
        }
    }

    // ── response contract ─────────────────────────────────────────────────

    @Nested
    @DisplayName("response contract")
    class ResponseContract {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("returns 201 with Location header and response body")
        void returns201WithLocationAndBody() throws Exception {
            final CreateUserResponse response = buildResponse();
            given(userService.createUser(any())).willReturn(response);

            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.userId").value(response.userId().toString()))
                    .andExpect(jsonPath("$.email").value("user@example.com"))
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_RESPONDER"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.inviteToken").value("raw-invite-token"))
                    .andExpect(jsonPath("$.inviteExpiresAt").exists());
        }
    }

    // ── validation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("missing email — 400")
        void missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("empty roles list — 400")
        void emptyRoles_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":[]}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("invalid role value — 400")
        void invalidRole_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_INVALID"]}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── service errors ────────────────────────────────────────────────────

    @Nested
    @DisplayName("service errors")
    class ServiceErrors {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("duplicate email — 409")
        void duplicateEmail_returns409() throws Exception {
            given(userService.createUser(any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.EMAIL_ALREADY_EXISTS,
                            "A user with email 'user@example.com' already exists in this tenant",
                            HttpStatus.CONFLICT));

            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user@example.com","roles":["ROLE_RESPONDER"]}
                                    """))
                    .andExpect(status().isConflict());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private CreateUserResponse buildResponse() {
        return new CreateUserResponse(
                UUID.randomUUID(),
                TENANT_ID,
                "user@example.com",
                List.of("ROLE_RESPONDER"),
                true,
                Instant.now(),
                "raw-invite-token",
                Instant.now().plusSeconds(72 * 3600)
        );
    }
}