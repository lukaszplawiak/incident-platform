package com.incidentplatform.incident.api;

import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ReservedTenants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("DevTokenController")
class DevTokenControllerTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private Environment environment;

    @Test
    @DisplayName("refuses reserved tenant ids with 400, without minting a token (backlog #0-16)")
    void refusesReservedTenant() {
        final DevTokenController controller = new DevTokenController(jwtUtils, environment);

        for (final String tenant : List.of(ReservedTenants.PLATFORM_OPERATOR, "System")) {
            final ResponseEntity<Map<String, String>> response =
                    controller.generateToken(tenant, List.of("ROLE_ADMIN"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error").doesNotContainKey("token");
        }
        verifyNoInteractions(jwtUtils);
    }

    @Test
    @DisplayName("mints a token for an ordinary tenant")
    void mintsForOrdinaryTenant() {
        given(jwtUtils.generateToken(any(), eq("acme"), anyString(), anyList(), anyList(), anyList()))
                .willReturn("jwt");
        final DevTokenController controller = new DevTokenController(jwtUtils, environment);

        final ResponseEntity<Map<String, String>> response =
                controller.generateToken("acme", List.of("ROLE_RESPONDER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("token", "jwt").containsEntry("tenantId", "acme");
        then(jwtUtils).should().generateToken(any(), eq("acme"), anyString(),
                eq(List.of("ROLE_RESPONDER")), anyList(), anyList());
    }
}
