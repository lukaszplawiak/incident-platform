package com.incidentplatform.oncall.api;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("OncallScheduleController")
class OncallScheduleControllerTest {

    @Mock
    private OncallScheduleService service;

    private OncallScheduleController controller;

    private static final String TENANT_ID = "test-tenant";
    private static final CurrentOncallResponse ONCALL_RESPONSE =
            new CurrentOncallResponse(
                    UUID.randomUUID().toString(),
                    "John Doe",
                    "john@example.com",
                    null,  // teamId
                    null,  // phone
                    null,  // slackUserId
                    OncallRole.PRIMARY.name(),
                    Instant.now().plusSeconds(3600)
            );

    @BeforeEach
    void setUp() {
        controller = new OncallScheduleController(service);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("role parameter variants")
    class RoleParameterVariants {

        @Test
        @DisplayName("null role fetches all current on-call")
        void nullRoleFetchesAllCurrentOncall() {
            // given
            given(service.getAllCurrentOncall(TENANT_ID))
                    .willReturn(List.of(ONCALL_RESPONSE));

            // when
            final ResponseEntity<?> response = controller.getCurrentOncall(null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            then(service).should().getAllCurrentOncall(TENANT_ID);
            then(service).should(never()).getCurrentOncall(TENANT_ID, null);
        }

        @Test
        @DisplayName("empty string role fetches all current on-call — same as absent")
        void emptyStringRoleFetchesAllCurrentOncall() {
            // given
            given(service.getAllCurrentOncall(TENANT_ID))
                    .willReturn(List.of(ONCALL_RESPONSE));

            // when
            final ResponseEntity<?> response = controller.getCurrentOncall("");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            then(service).should().getAllCurrentOncall(TENANT_ID);
            then(service).should(never()).getCurrentOncall(TENANT_ID, "");
        }

        @Test
        @DisplayName("blank string role fetches all current on-call — same as absent")
        void blankStringRoleFetchesAllCurrentOncall() {
            // ?role=   (whitespace only) treated as absent param
            given(service.getAllCurrentOncall(TENANT_ID))
                    .willReturn(List.of(ONCALL_RESPONSE));

            // when
            final ResponseEntity<?> response = controller.getCurrentOncall("   ");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            then(service).should().getAllCurrentOncall(TENANT_ID);
        }

        @Test
        @DisplayName("valid role delegates to getCurrentOncall with that role")
        void validRoleDelegatesToGetCurrentOncall() {
            // given
            given(service.getCurrentOncall(TENANT_ID, OncallRole.PRIMARY.name()))
                    .willReturn(Optional.of(ONCALL_RESPONSE));

            // when
            final ResponseEntity<?> response =
                    controller.getCurrentOncall(OncallRole.PRIMARY.name());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            then(service).should().getCurrentOncall(TENANT_ID, OncallRole.PRIMARY.name());
            then(service).should(never()).getAllCurrentOncall(TENANT_ID);
        }

        @Test
        @DisplayName("valid role with no oncall returns 204 No Content")
        void validRoleWithNoOncallReturns204() {
            // given
            given(service.getCurrentOncall(TENANT_ID, OncallRole.SECONDARY.name()))
                    .willReturn(Optional.empty());

            // when
            final ResponseEntity<?> response =
                    controller.getCurrentOncall(OncallRole.SECONDARY.name());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("null role with empty oncall list returns 204 No Content")
        void nullRoleWithEmptyListReturns204() {
            // given
            given(service.getAllCurrentOncall(TENANT_ID)).willReturn(List.of());

            // when
            final ResponseEntity<?> response = controller.getCurrentOncall(null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Nested
    @DisplayName("findBySlackUserId")
    class FindBySlackUserId {

        @Test
        @DisplayName("should return 200 with user info when slackUserId found for tenant")
        void shouldReturn200WhenFound() {
            // given
            final SlackUserLookupResponse response = new SlackUserLookupResponse(
                    "user-1", "Jan Kowalski", TENANT_ID, "U0123456789");
            given(service.findBySlackUserId(TENANT_ID, "U0123456789"))
                    .willReturn(Optional.of(response));

            // when
            final ResponseEntity<SlackUserLookupResponse> result =
                    controller.findBySlackUserId("U0123456789");

            // then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().userId()).isEqualTo("user-1");
            assertThat(result.getBody().tenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should return 204 No Content when no schedule found for tenant")
        void shouldReturn204WhenNotFound() {
            // given
            given(service.findBySlackUserId(TENANT_ID, "U_UNKNOWN"))
                    .willReturn(Optional.empty());

            // when
            final ResponseEntity<SlackUserLookupResponse> result =
                    controller.findBySlackUserId("U_UNKNOWN");

            // then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("should pass tenantId from TenantContext to service")
        void shouldPassTenantIdFromContext() {
            // given
            given(service.findBySlackUserId(TENANT_ID, "U0123456789"))
                    .willReturn(Optional.empty());

            // when
            controller.findBySlackUserId("U0123456789");

            // then
            then(service).should().findBySlackUserId(TENANT_ID, "U0123456789");
        }
    }
}