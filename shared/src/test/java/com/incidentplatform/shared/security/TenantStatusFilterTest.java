package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantStatusFilter} (backlog #0-82): a suspended tenant's users and
 * API keys are refused, in full or for writes; service tokens and anonymous
 * requests are left alone.
 */
@DisplayName("TenantStatusFilter")
class TenantStatusFilterTest {

    private static final String TENANT = "acme";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static UserPrincipal user(boolean apiKey) {
        return new UserPrincipal(UUID.randomUUID(), TENANT, "u@acme.test", List.of("ROLE_ADMIN"),
                List.of(), List.of(), apiKey, List.of(), null);
    }

    private static MockFilterChain run(TenantAccess access, String method, String path, List<String> allowed,
                                       MockHttpServletResponse response) throws Exception {
        final TenantStatusFilter filter = new TenantStatusFilter(
                tenantId -> tenantId.equals(TENANT) ? access : TenantAccess.FULL,
                new ObjectMapper().findAndRegisterModules(), allowed);
        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        final MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    @Test
    @DisplayName("an active tenant passes, whatever the method")
    void activePasses() throws Exception {
        authenticate(user(false));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(TenantAccess.FULL, "DELETE", "/api/v1/x", List.of(), response).getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    @DisplayName("a tenant suspended in full is refused on every method, 403 TENANT_SUSPENDED")
    void suspendedRefused(String method) throws Exception {
        authenticate(user(false));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain chain = run(TenantAccess.NONE, method, "/api/v1/x", List.of(), response);

        assertThat(chain.getRequest()).as("not passed on").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"TENANT_SUSPENDED\"");
    }

    @Test
    @DisplayName("an API key of a suspended tenant is refused like a person")
    void apiKeyRefused() throws Exception {
        authenticate(user(true));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(TenantAccess.NONE, "GET", "/api/v1/teams", List.of(), response).getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    @DisplayName("read-only: reads go on")
    void readOnlyReads(String method) throws Exception {
        authenticate(user(false));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(TenantAccess.READ_ONLY, method, "/api/v1/x", List.of(), response).getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("read-only: writes are refused, 403 TENANT_READ_ONLY")
    void readOnlyWritesRefused(String method) throws Exception {
        authenticate(user(false));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(TenantAccess.READ_ONLY, method, "/api/v1/x", List.of(), response).getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"TENANT_READ_ONLY\"");
    }

    @Test
    @DisplayName("read-only: an account-security write the service lists goes on; a similar path does not")
    void readOnlyAllowedWrites() throws Exception {
        authenticate(user(false));
        final List<String> allowed = List.of("POST /api/v1/auth/logout", "POST /api/v1/auth/mfa/**");
        assertThat(run(TenantAccess.READ_ONLY, "POST", "/api/v1/auth/mfa/enable", allowed,
                new MockHttpServletResponse()).getRequest()).isNotNull();
        assertThat(run(TenantAccess.READ_ONLY, "POST", "/api/v1/auth/logout", allowed,
                new MockHttpServletResponse()).getRequest()).isNotNull();
        assertThat(run(TenantAccess.READ_ONLY, "POST", "/api/v1/auth/logout-all", allowed,
                new MockHttpServletResponse()).getRequest()).isNull();
        assertThat(run(TenantAccess.NONE, "POST", "/api/v1/auth/logout", allowed,
                new MockHttpServletResponse()).getRequest())
                .as("suspended in full: the list does not apply").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/logout;jsessionid=abc",
            "/api/v1//auth/logout",
            "/api/v1/auth/%6cogout",
            "/api/v1/auth/mfa/enable;x=y"})
    @DisplayName("read-only: an allowed write is matched on the path MVC routes (decoded, no ;params, no //)")
    void allowedWriteMatchedOnRoutedPath(String uri) throws Exception {
        authenticate(user(false));
        final List<String> allowed = List.of("POST /api/v1/auth/logout", "POST /api/v1/auth/mfa/**");
        assertThat(run(TenantAccess.READ_ONLY, "POST", uri, allowed, new MockHttpServletResponse()).getRequest())
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/mfa/../../tenants/settings",
            "/api/v1/auth/mfa/%2e%2e/%2E%2E/tenants/settings",
            "/api/v1/auth/mfa/..;/..;/tenants/settings",
            "/api/v1/auth/mfa/./enable",
            "/api/v1/auth/logout/.."})
    @DisplayName("read-only: a path with a dot segment is never an allowed write, though it matches a pattern (fails closed)")
    void dotSegmentsRefused(String uri) throws Exception {
        authenticate(user(false));
        final List<String> allowed = List.of("POST /api/v1/auth/logout/**", "POST /api/v1/auth/mfa/**");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(TenantAccess.READ_ONLY, "POST", uri, allowed, response).getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("read-only: the context path is not part of the matched path")
    void contextPathStripped() throws Exception {
        authenticate(user(false));
        final TenantStatusFilter filter = new TenantStatusFilter(tenantId -> TenantAccess.READ_ONLY,
                new ObjectMapper().findAndRegisterModules(), List.of("POST /api/v1/auth/logout"));
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/api/v1/auth/logout");
        request.setContextPath("/auth");
        final MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a service token and an anonymous request are not checked here")
    void serviceAndAnonymousPass() throws Exception {
        authenticate(new ServicePrincipal("notification-service", TENANT));
        assertThat(run(TenantAccess.NONE, "GET", "/api/v1/x", List.of(),
                new MockHttpServletResponse()).getRequest()).isNotNull();
        SecurityContextHolder.clearContext();
        assertThat(run(TenantAccess.NONE, "POST", "/api/v1/auth/login", List.of(),
                new MockHttpServletResponse()).getRequest()).isNotNull();
    }

    @Test
    @DisplayName("another tenant's user is unaffected")
    void otherTenantUnaffected() throws Exception {
        authenticate(new UserPrincipal(UUID.randomUUID(), "globex", "u@globex.test", List.of("ROLE_ADMIN"),
                List.of(), List.of(), false, List.of(), null));
        assertThat(run(TenantAccess.NONE, "POST", "/api/v1/x", List.of(),
                new MockHttpServletResponse()).getRequest()).isNotNull();
    }

    @Test
    @DisplayName("read-only: an allowed write matches its method only; a PUT or DELETE on the same path is refused")
    void allowedWriteIsPerMethod() throws Exception {
        authenticate(user(false));
        final List<String> allowed = List.of("DELETE /api/v1/api-keys/*");
        assertThat(run(TenantAccess.READ_ONLY, "DELETE", "/api/v1/api-keys/k1", allowed,
                new MockHttpServletResponse()).getRequest()).isNotNull();
        for (final String method : List.of("POST", "PUT", "PATCH")) {
            final MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(run(TenantAccess.READ_ONLY, method, "/api/v1/api-keys/k1", allowed, response).getRequest())
                    .as(method).isNull();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/logout", "GET /api/v1/auth/logout", "TRACE /x", "POST", "POST x",
            "POST /a /b", ""})
    @DisplayName("an allowed-write entry without a write method and a /pattern fails at construction")
    void malformedEntryRefused(String entry) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TenantStatusFilter(
                        tenantId -> TenantAccess.FULL, new ObjectMapper(), List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
