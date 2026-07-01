package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthTokenService authTokenService;

    private UserService service;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "newuser@example.com";

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, authTokenService);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── createUser — success ─────────────────────────────────────────────

    @Nested
    @DisplayName("createUser — success")
    class CreateUserSuccess {

        @Test
        @DisplayName("returns CreateUserResponse with correct fields")
        void returnsResponse() {
            // given
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.generateInviteToken(any(), anyString()))
                    .willReturn("raw-invite-token");

            // when
            final CreateUserResponse response = service.createUser(
                    new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            // then
            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.tenantId()).isEqualTo(TENANT_ID);
            assertThat(response.roles()).containsExactly("ROLE_ADMIN");
            assertThat(response.active()).isTrue();
            assertThat(response.inviteToken()).isEqualTo("raw-invite-token");
            assertThat(response.inviteExpiresAt()).isAfter(
                    java.time.Instant.now());
        }

        @Test
        @DisplayName("persists user with correct email, tenant, no password")
        void persistsUserWithNoPassword() {
            // given
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.generateInviteToken(any(), anyString()))
                    .willReturn("token");

            // when
            service.createUser(new CreateUserRequest(EMAIL, List.of("ROLE_RESPONDER")));

            // then
            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            final User saved = captor.getValue();

            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getPasswordHash()).isNull();
            assertThat(saved.isActive()).isTrue();
        }

        @Test
        @DisplayName("assigns all requested roles to user")
        void assignsRequestedRoles() {
            // given
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.generateInviteToken(any(), anyString()))
                    .willReturn("token");

            // when
            service.createUser(new CreateUserRequest(
                    EMAIL, List.of("ROLE_ADMIN", "ROLE_RESPONDER")));

            // then
            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            assertThat(captor.getValue().getRoleNames())
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_RESPONDER");
        }

        @Test
        @DisplayName("calls generateInviteToken with saved user and tenantId")
        void callsGenerateInviteTokenWithCorrectArgs() {
            // given
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.generateInviteToken(any(), anyString()))
                    .willReturn("token");

            // when
            service.createUser(new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            // then
            then(authTokenService).should()
                    .generateInviteToken(any(User.class), anyString());
        }
    }

    // ── createUser — duplicate email ─────────────────────────────────────

    @Nested
    @DisplayName("createUser — duplicate email")
    class DuplicateEmail {

        @Test
        @DisplayName("throws 409 when email already exists in tenant")
        void throws409OnDuplicateEmail() {
            // given
            final User existing = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    "hash", true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(existing));

            // when / then
            assertThatThrownBy(() ->
                    service.createUser(
                            new CreateUserRequest(EMAIL, List.of("ROLE_RESPONDER"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(EMAIL)
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("does not persist user or generate token on duplicate email")
        void doesNotPersistOnDuplicate() {
            // given
            final User existing = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    "hash", true, List.of());

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(existing));

            // when
            assertThatThrownBy(() ->
                    service.createUser(
                            new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN"))))
                    .isInstanceOf(BusinessException.class);

            // then
            then(userRepository).shouldHaveNoMoreInteractions();
            then(authTokenService).shouldHaveNoInteractions();
        }
    }

    // ── tenant isolation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("resolves tenant from TenantContext")
        void resolvesTenantFromContext() {
            // given
            TenantContext.set("company-abc");
            given(userRepository.findByEmailAndTenantId(EMAIL, "company-abc"))
                    .willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.generateInviteToken(any(), anyString()))
                    .willReturn("token");

            // when
            final CreateUserResponse response = service.createUser(
                    new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            // then
            assertThat(response.tenantId()).isEqualTo("company-abc");
        }
    }
}