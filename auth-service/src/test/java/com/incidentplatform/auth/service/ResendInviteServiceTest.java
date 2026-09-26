package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendInviteService")
class ResendInviteServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthEmailRequestService emailRequests;
    @Mock private AuditEventPublisher auditEventPublisher;

    private ResendInviteService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "jan@firma.pl";

    @BeforeEach
    void setUp() {
        service = new ResendInviteService(
                userRepository, outboxRepository, emailRequests, auditEventPublisher);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private User givenUserWithoutPassword() {
        final User user = User.forTesting(USER_ID, TENANT_ID, EMAIL, null, true, List.of("ROLE_RESPONDER"));
        given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(user));
        return user;
    }

    private void latestInvite(AuthEmailStatus status) {
        final AuthEmailOutbox entry = mock(AuthEmailOutbox.class);
        given(entry.getStatus()).willReturn(status);
        given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(USER_ID, AuthEmailType.INVITE))
                .willReturn(Optional.of(entry));
    }

    @Nested
    @DisplayName("resendInvite — success")
    class ResendInviteSuccess {

        @Test
        @DisplayName("queues an invite request and audits it; no token is created here (backlog #0-52)")
        void queuesRequest() {
            final User user = givenUserWithoutPassword();
            given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(USER_ID, AuthEmailType.INVITE))
                    .willReturn(Optional.empty());

            service.resendInvite(USER_ID);

            then(emailRequests).should().requestInvite(user);
            then(auditEventPublisher).should().publishAuth(eq(USER_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.USER_INVITE_RESENT), anyString(), anyString(), anyString(), anyMap());
        }

        /**
         * Backlog #0-52: an earlier request — even one still being retried —
         * is left to the scheduler, which supersedes it; this service never
         * changes an existing outbox row.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AuthEmailStatus.class, names = {"FAILED", "SENT", "PERMANENTLY_FAILED", "SUPERSEDED"})
        @DisplayName("queues a new request whatever the earlier one's state (except PENDING)")
        void queuesNextToEarlier(AuthEmailStatus status) {
            final User user = givenUserWithoutPassword();
            latestInvite(status);

            service.resendInvite(USER_ID);

            then(emailRequests).should().requestInvite(user);
            then(outboxRepository).should(never()).save(any());
            then(outboxRepository).should(never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("resendInvite — guards")
    class ResendInviteGuards {

        @Test
        @DisplayName("throws 404 when user not found in tenant")
        void throws404WhenUserNotFound() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(emailRequests).shouldHaveNoInteractions();
            then(outboxRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when user already accepted invite")
        void throws409WhenUserAlreadyAccepted() {
            final User user = User.forTesting(USER_ID, TENANT_ID, EMAIL,
                    "bcrypt-hash", true, List.of("ROLE_RESPONDER"));
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already accepted")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(emailRequests).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when an invite is already PENDING (dispatch in progress)")
        void throws409WhenEmailAlreadyPending() {
            givenUserWithoutPassword();
            latestInvite(AuthEmailStatus.PENDING);

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already queued")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(emailRequests).shouldHaveNoInteractions();
        }
    }
}
