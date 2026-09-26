package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForgotPasswordService")
class ForgotPasswordServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthEmailRequestService emailRequests;
    @Mock private WorkSimulator workSimulator;

    private ForgotPasswordService service;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "user@firma.pl";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ForgotPasswordService(
                userRepository, outboxRepository, emailRequests, workSimulator);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private User givenUser() {
        final User user = User.forTesting(USER_ID, TENANT_ID, EMAIL,
                "bcrypt-hash", true, List.of("ROLE_RESPONDER"));
        given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID)).willReturn(Optional.of(user));
        return user;
    }

    private void latestRequest(AuthEmailStatus status) {
        final AuthEmailOutbox entry = mock(AuthEmailOutbox.class);
        given(entry.getStatus()).willReturn(status);
        given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                USER_ID, AuthEmailType.PASSWORD_RESET)).willReturn(Optional.of(entry));
    }

    // ── user enumeration protection ───────────────────────────────────────

    @Nested
    @DisplayName("user enumeration protection")
    class UserEnumerationProtection {

        @Test
        @DisplayName("does nothing when email not found — no exception, no request")
        void doesNothingWhenEmailNotFound() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID)).willReturn(Optional.empty());

            // Must NOT throw — caller always returns 202
            service.initiateReset(EMAIL, TENANT_ID);

            then(emailRequests).shouldHaveNoInteractions();
            then(outboxRepository).shouldHaveNoInteractions();
        }

        /**
         * Regression test for the fix documented in this class's Javadoc:
         * WorkSimulator was built but never called, so the "user not found"
         * path returned after only a fast DB lookup. Its calibration against
         * the "user exists" path is tracked as backlog #0-56.
         */
        @Test
        @DisplayName("calls workSimulator.simulate() when email is not found — timing attack mitigation")
        void callsWorkSimulatorWhenEmailNotFound() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID)).willReturn(Optional.empty());

            service.initiateReset(EMAIL, TENANT_ID);

            then(workSimulator).should().simulate();
        }
    }

    // ── queueing the reset ────────────────────────────────────────────────

    @Nested
    @DisplayName("initiateReset — user exists")
    class UserExists {

        @Test
        @DisplayName("queues a password-reset request and nothing else (backlog #0-52)")
        void queuesRequest() {
            final User user = givenUser();
            given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    USER_ID, AuthEmailType.PASSWORD_RESET)).willReturn(Optional.empty());

            service.initiateReset(EMAIL, TENANT_ID);

            then(emailRequests).should().requestPasswordReset(user);
            then(workSimulator).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("does nothing when a reset is already PENDING — idempotency guard")
        void noOpWhenPending() {
            givenUser();
            latestRequest(AuthEmailStatus.PENDING);

            service.initiateReset(EMAIL, TENANT_ID);

            then(emailRequests).shouldHaveNoInteractions();
        }

        /**
         * Backlog #0-52: a FAILED request is not closed here — the scheduler
         * supersedes it once it sees the newer one — so this path never writes
         * to an existing row and cannot answer anything but 202.
         */
        @Test
        @DisplayName("queues a new request next to one still being retried, without touching it")
        void queuesNextToFailed() {
            final User user = givenUser();
            latestRequest(AuthEmailStatus.FAILED);

            service.initiateReset(EMAIL, TENANT_ID);

            then(emailRequests).should().requestPasswordReset(user);
            then(outboxRepository).should(never()).save(any());
            then(outboxRepository).should(never()).saveAndFlush(any());
        }
    }
}
