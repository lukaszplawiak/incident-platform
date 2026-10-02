package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MFA change notices are retried at least as long as the platform API's grace period (backlog #0-83). */
@DisplayName("AuthEmailRequestService — MFA notices")
class AuthEmailRequestServiceTest {

    private final AuthEmailOutboxRepository repository = mock(AuthEmailOutboxRepository.class);
    private final User user = User.forTesting(UUID.randomUUID(), "acme", "a@acme.example", "hash", true,
            List.of("ROLE_ADMIN"));

    private Duration deadlineFor(Duration grace, boolean enabled) {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        final ArgumentCaptor<AuthEmailOutbox> saved = ArgumentCaptor.forClass(AuthEmailOutbox.class);

        new AuthEmailRequestService(repository, grace).requestMfaChangeNotification(user, enabled);

        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmailType())
                .isEqualTo(enabled ? AuthEmailType.MFA_ENABLED : AuthEmailType.MFA_DISABLED);
        return Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getDeadline());
    }

    @Test
    @DisplayName("a grace period longer than 24 h stretches the notice's deadline to it")
    void followsLongerGrace() {
        assertThat(deadlineFor(Duration.ofHours(72), true)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    @DisplayName("never less than 24 h, also with a shorter grace period")
    void atLeastADay() {
        assertThat(deadlineFor(Duration.ofHours(1), false)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("an admin's MFA reset queues its own type, with the security notices' deadline (backlog #0-88)")
    void mfaResetNotice() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        final ArgumentCaptor<AuthEmailOutbox> saved = ArgumentCaptor.forClass(AuthEmailOutbox.class);

        new AuthEmailRequestService(repository, Duration.ofHours(72)).requestMfaResetNotification(user);

        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmailType()).isEqualTo(AuthEmailType.MFA_RESET);
        assertThat(Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getDeadline()))
                .isEqualTo(Duration.ofHours(72));
    }

    @Test
    @DisplayName("every new API key queues its own notice naming the key, never merged (backlog #0-89, review)")
    void apiKeyCreatedNotice() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        final ArgumentCaptor<AuthEmailOutbox> saved = ArgumentCaptor.forClass(AuthEmailOutbox.class);
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final AuthEmailRequestService service = new AuthEmailRequestService(repository, Duration.ofHours(72));

        service.requestApiKeyCreatedNotification(user, first);
        service.requestApiKeyCreatedNotification(user, second);

        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AuthEmailOutbox::getApiKeyId).containsExactly(first, second);
        assertThat(saved.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getEmailType()).isEqualTo(AuthEmailType.API_KEY_CREATED);
            assertThat(entry.getEmail()).isEqualTo(user.getEmail());
            assertThat(Duration.between(entry.getCreatedAt(), entry.getDeadline())).isEqualTo(Duration.ofHours(72));
        });
    }
}
