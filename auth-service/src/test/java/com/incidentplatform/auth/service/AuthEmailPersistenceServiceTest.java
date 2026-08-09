package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Tests for {@link AuthEmailPersistenceService} — new class, extracted
 * from {@code AuthEmailScheduler} to close the batch-transaction-spans-
 * every-SMTP-call issue documented in {@code AuthEmailScheduler}'s Javadoc.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailPersistenceService")
class AuthEmailPersistenceServiceTest {

    @Mock
    private AuthEmailOutboxRepository outboxRepository;

    private AuthEmailPersistenceService persistenceService;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        persistenceService = new AuthEmailPersistenceService(outboxRepository);
    }

    private AuthEmailOutbox buildEntry() {
        final User user = User.forTesting(UUID.randomUUID(), TENANT_ID,
                "user@firma.pl", "hash", true, List.of("ROLE_RESPONDER"));
        final AuthToken token = AuthToken.create(user, TENANT_ID, "hash",
                AuthToken.Type.INVITE, Instant.now().plusSeconds(3600));
        return AuthEmailOutbox.invitePending(user, token, "raw-token");
    }

    @Nested
    @DisplayName("markSent")
    class MarkSent {

        @Test
        @DisplayName("marks the entry sent and saves it when found")
        void marksSentAndSaves() {
            final AuthEmailOutbox entry = buildEntry();
            given(outboxRepository.findById(entry.getId()))
                    .willReturn(Optional.of(entry));

            persistenceService.markSent(entry.getId());

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());
            assertThat(captor.getValue().getRawToken()).isNull(); // markSent() nulls it
        }

        @Test
        @DisplayName("does nothing (no exception) when the entry no longer exists")
        void doesNothingWhenEntryMissing() {
            final UUID missingId = UUID.randomUUID();
            given(outboxRepository.findById(missingId)).willReturn(Optional.empty());

            persistenceService.markSent(missingId);

            then(outboxRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("markFailed")
    class MarkFailed {

        @Test
        @DisplayName("marks the entry failed with the given message and saves it")
        void marksFailedAndSaves() {
            final AuthEmailOutbox entry = buildEntry();
            given(outboxRepository.findById(entry.getId()))
                    .willReturn(Optional.of(entry));

            persistenceService.markFailed(entry.getId(), "SMTP timeout");

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());
            assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("markPermanentlyFailed")
    class MarkPermanentlyFailed {

        @Test
        @DisplayName("marks the entry permanently failed and saves it")
        void marksPermanentlyFailedAndSaves() {
            final AuthEmailOutbox entry = buildEntry();
            given(outboxRepository.findById(entry.getId()))
                    .willReturn(Optional.of(entry));

            persistenceService.markPermanentlyFailed(
                    entry.getId(), "rawToken is null — cannot send email");

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());
            assertThat(captor.getValue().getRawToken()).isNull();
        }
    }
}