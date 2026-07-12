package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
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

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuditEventPublisher auditEventPublisher;

    private UserService service;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "newuser@example.com";

    @BeforeEach
    void setUp() {
        service = new UserService(
                userRepository, authTokenService,
                outboxRepository, auditEventPublisher);
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
        @DisplayName("returns CreateUserResponse without inviteToken")
        void returnsResponseWithoutInviteToken() {
            givenUserCreationSucceeds();

            final CreateUserResponse response = service.createUser(
                    new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.tenantId()).isEqualTo(TENANT_ID);
            assertThat(response.roles()).containsExactly("ROLE_ADMIN");
            assertThat(response.active()).isTrue();
            // inviteToken no longer in response — goes directly to user's inbox
        }

        @Test
        @DisplayName("persists user with correct email, tenant, no password")
        void persistsUserWithNoPassword() {
            givenUserCreationSucceeds();

            service.createUser(new CreateUserRequest(EMAIL, List.of("ROLE_RESPONDER")));

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
            givenUserCreationSucceeds();

            service.createUser(new CreateUserRequest(
                    EMAIL, List.of("ROLE_ADMIN", "ROLE_RESPONDER")));

            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            assertThat(captor.getValue().getRoleNames())
                    .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_RESPONDER");
        }

        @Test
        @DisplayName("calls generateInviteTokenWithEntity with saved user and tenantId")
        void callsGenerateInviteTokenWithEntity() {
            givenUserCreationSucceeds();

            service.createUser(new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            then(authTokenService).should()
                    .generateInviteTokenWithEntity(any(User.class), anyString());
        }

        @Test
        @DisplayName("writes PENDING outbox entry with raw token and token entity")
        void writesPendingOutboxEntry() {
            givenUserCreationSucceeds();

            service.createUser(new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());

            final AuthEmailOutbox saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getStatus()).isEqualTo(AuthEmailStatus.PENDING);
            assertThat(saved.getRawToken()).isEqualTo("raw-invite-token");
            assertThat(saved.getRawToken()).isNotNull();
        }

        @Test
        @DisplayName("does NOT return inviteToken in response — it goes to inbox")
        void doesNotReturnTokenInResponse() {
            givenUserCreationSucceeds();

            final CreateUserResponse response = service.createUser(
                    new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            // Verify the DTO has no token field at all (compile-time safety)
            // This test documents the intentional API contract change
            assertThat(response).isNotNull();
            assertThat(response.email()).isEqualTo(EMAIL);
            // CreateUserResponse record has no inviteToken field
        }

        private void givenUserCreationSucceeds() {
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            final AuthToken mockToken = AuthToken.create(
                    User.forTesting(UUID.randomUUID(), TENANT_ID, EMAIL,
                            null, true, List.of()),
                    TENANT_ID, "hash", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));

            given(authTokenService.generateInviteTokenWithEntity(
                    any(User.class), anyString()))
                    .willReturn(new InviteTokenResult("raw-invite-token", mockToken));

            given(outboxRepository.save(any(AuthEmailOutbox.class)))
                    .willAnswer(inv -> inv.getArgument(0));
        }
    }

    // ── createUser — duplicate email ─────────────────────────────────────

    @Nested
    @DisplayName("createUser — duplicate email")
    class DuplicateEmail {

        @Test
        @DisplayName("throws 409 when email already exists in tenant")
        void throws409OnDuplicateEmail() {
            final User existing = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    "hash", true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.of(existing));

            assertThatThrownBy(() ->
                    service.createUser(
                            new CreateUserRequest(EMAIL, List.of("ROLE_RESPONDER"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(EMAIL)
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("does not persist user, generate token, or write outbox on duplicate")
        void doesNotPersistOnDuplicate() {
            final User existing = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    "hash", true, List.of());

            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.of(existing));

            assertThatThrownBy(() ->
                    service.createUser(
                            new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN"))))
                    .isInstanceOf(BusinessException.class);

            then(userRepository).shouldHaveNoMoreInteractions();
            then(authTokenService).shouldHaveNoInteractions();
            then(outboxRepository).shouldHaveNoInteractions();
        }
    }

    // ── tenant isolation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("resolves tenant from TenantContext")
        void resolvesTenantFromContext() {
            TenantContext.set("company-abc");
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, "company-abc")).willReturn(Optional.empty());
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            final AuthToken mockToken = AuthToken.create(
                    User.forTesting(UUID.randomUUID(), "company-abc", EMAIL,
                            null, true, List.of()),
                    "company-abc", "hash", AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));

            given(authTokenService.generateInviteTokenWithEntity(
                    any(User.class), anyString()))
                    .willReturn(new InviteTokenResult("token", mockToken));
            given(outboxRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            final CreateUserResponse response = service.createUser(
                    new CreateUserRequest(EMAIL, List.of("ROLE_ADMIN")));

            assertThat(response.tenantId()).isEqualTo("company-abc");
        }
    }
}