package com.incidentplatform.escalation.client;

import com.incidentplatform.escalation.dto.OncallUserDto;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Added for backlog #0-11: the client used to send no {@code Authorization}
 * header at all (its Javadoc said it did), so every lookup was a 401 and
 * {@code escalateTo} was always null. These tests pin the header, and the
 * tenant it is minted for, at the HTTP boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OncallServiceClient")
class OncallServiceClientTest {

    private static final String BASE_URL = "http://oncall.test";
    private static final String TENANT_ID = "acme-corp";
    private static final UUID TEAM_ID = UUID.randomUUID();

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private OncallServiceClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OncallServiceClient(builder, serviceTokenProvider,
                new ClientFallbackMetrics(new SimpleMeterRegistry()), BASE_URL);
    }

    @Test
    @DisplayName("sends a Bearer token minted for the call's tenant and oncall-service, plus X-Tenant-Id")
    void sendsServiceTokenForTheTenant() {
        given(serviceTokenProvider.getToken(TENANT_ID, ServiceNames.ONCALL_SERVICE)).willReturn("tenant-token");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        BASE_URL + "/api/v1/oncall/current")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("teamId", TEAM_ID.toString()))
                .andExpect(queryParam("role", "SECONDARY"))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(header("X-Tenant-Id", TENANT_ID))
                .andRespond(withSuccess("""
                        {"userId":"11111111-1111-1111-1111-111111111111",
                         "userName":"Sam","email":"sam@acme.com",
                         "role":"SECONDARY"}
                        """, MediaType.APPLICATION_JSON));

        final Optional<OncallUserDto> result =
                client.getCurrentOncall(TENANT_ID, TEAM_ID, "SECONDARY");

        assertThat(result).isPresent();
        assertThat(result.get().userId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        server.verify();
    }

    @Test
    @DisplayName("204 means no active schedule, not an error")
    void noContentIsEmpty() {
        given(serviceTokenProvider.getToken(TENANT_ID, ServiceNames.ONCALL_SERVICE)).willReturn("tenant-token");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        BASE_URL + "/api/v1/oncall/current")))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.getCurrentOncall(TENANT_ID, TEAM_ID, "MANAGER")).isEmpty();
    }

    @Test
    @DisplayName("401 propagates, so the circuit breaker records it and the fallback logs it as an auth error")
    void unauthorizedPropagates() {
        given(serviceTokenProvider.getToken(TENANT_ID, ServiceNames.ONCALL_SERVICE)).willReturn("tenant-token");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        BASE_URL + "/api/v1/oncall/current")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getCurrentOncall(TENANT_ID, TEAM_ID, "SECONDARY"))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }
}
