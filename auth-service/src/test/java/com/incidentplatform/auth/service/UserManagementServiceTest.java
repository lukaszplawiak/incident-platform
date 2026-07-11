package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserManagementService")
class UserManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserManagementService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserManagementService(userRepository);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── updateRoles ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateRoles")
    class UpdateRoles {

        @Test
        @DisplayName("replaces existing roles with new set")
        void replacesRoles() {
            // given
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            final UserSummaryDto result = service.updateRoles(
                    USER_ID, new UpdateUserRolesRequest(List.of("ROLE_ADMIN")));

            // then — ROLE_RESPONDER replaced by ROLE_ADMIN
            assertThat(result.roles()).containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("replaces with multiple roles")
        void replacesWithMultipleRoles() {
            // given
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            final UserSummaryDto result = service.updateRoles(
                    USER_ID, new UpdateUserRolesRequest(
                            List.of("ROLE_ADMIN", "ROLE_RESPONDER")));

            // then
            assertThat(result.roles())
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_RESPONDER");
        }

        @Test
        @DisplayName("saves user after updating roles")
        void savesUser() {
            // given
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            service.updateRoles(USER_ID,
                    new UpdateUserRolesRequest(List.of("ROLE_ADMIN")));

            // then
            then(userRepository).should().save(user);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not in tenant")
        void throwsNotFoundWhenUserMissing() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateRoles(
                    USER_ID, new UpdateUserRolesRequest(List.of("ROLE_ADMIN"))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── updateStatus ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("deactivates active user")
        void deactivatesUser() {
            // given
            final User user = buildUser("ROLE_RESPONDER"); // active=true
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            final UserSummaryDto result = service.updateStatus(
                    USER_ID, new UpdateUserStatusRequest(false));

            // then
            assertThat(result.active()).isFalse();
        }

        @Test
        @DisplayName("activates inactive user")
        void activatesUser() {
            // given — build inactive user
            final User user = User.forTesting(USER_ID, TENANT_ID,
                    "u@example.com", "hash", false, List.of("ROLE_RESPONDER"));
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            final UserSummaryDto result = service.updateStatus(
                    USER_ID, new UpdateUserStatusRequest(true));

            // then
            assertThat(result.active()).isTrue();
        }

        @Test
        @DisplayName("saves user after updating status")
        void savesUser() {
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.updateStatus(USER_ID, new UpdateUserStatusRequest(false));

            then(userRepository).should().save(user);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not in tenant")
        void throwsNotFoundWhenUserMissing() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStatus(
                    USER_ID, new UpdateUserStatusRequest(false)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUser(String role) {
        return User.forTesting(USER_ID, TENANT_ID, "u@example.com",
                "hash", true, List.of(role));
    }
}