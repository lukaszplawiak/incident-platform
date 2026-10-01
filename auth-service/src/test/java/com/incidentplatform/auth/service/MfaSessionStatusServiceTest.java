package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.MfaSessionFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The platform API's MFA rule (backlog #0-83) on facts given by a mocked
 * repository: each branch of {@code check}, so a failure points here rather
 * than at the database. AuthRepositoryIntegrationTest proves the query that
 * produces those facts on a real Postgres.
 */
@DisplayName("MfaSessionStatusService")
class MfaSessionStatusServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private final AuthTokenRepository repository = mock(AuthTokenRepository.class);
    private final MfaSessionStatusService service =
            new MfaSessionStatusService(repository, Duration.ofHours(12), Duration.ofHours(24));

    @Test
    @DisplayName("refuses a zero or negative session age and a negative grace period at startup")
    void validatesConfiguration() {
        assertThatThrownBy(() -> new MfaSessionStatusService(repository, Duration.ZERO, Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MfaSessionStatusService(repository, Duration.ofHours(-1), Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MfaSessionStatusService(repository, Duration.ofHours(12), Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a factor is established exactly when the grace period since its notice has passed")
    void establishedBoundary() {
        final Instant now = Instant.parse("2026-10-02T12:00:00Z");

        assertThat(service.isEstablished(now.minus(Duration.ofHours(24)), now)).isTrue();
        assertThat(service.isEstablished(now.minus(Duration.ofHours(24)).plusSeconds(1), now)).isFalse();
        assertThat(service.isEstablished(null, now)).as("no notice sent").isFalse();
    }

    private MfaSessionStatusService.Status checkWith(MfaSessionFacts... facts) {
        given(repository.findLiveSessionMfaFacts(eq(USER), eq("acme"), eq(SESSION), any()))
                .willReturn(List.of(facts));
        return service.check(USER, "acme", SESSION);
    }

    private static MfaSessionFacts facts(Duration verifiedAgo, boolean enabled, Duration enabledAgo,
                                         Duration noticeSentAgo) {
        final Instant now = Instant.now();
        return new MfaSessionFacts(
                verifiedAgo == null ? null : now.minus(verifiedAgo),
                enabled,
                enabledAgo == null ? null : now.minus(enabledAgo),
                noticeSentAgo == null ? null : now.minus(noticeSentAgo));
    }

    @Test
    @DisplayName("accepts a session verified within 12 h with a factor whose notice went out over 24 h ago")
    void accepted() {
        assertThat(checkWith(facts(Duration.ofHours(1), true, Duration.ofHours(30), Duration.ofHours(25))))
                .isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
    }

    @Test
    @DisplayName("no live session, a password-only session, or MFA since disabled: no MFA")
    void noMfa() {
        assertThat(checkWith()).as("no live session").isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        assertThat(checkWith(facts(null, true, Duration.ofHours(30), Duration.ofHours(25))))
                .as("password-only session").isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        assertThat(checkWith(facts(Duration.ofHours(1), false, null, null)))
                .as("MFA disabled since").isEqualTo(MfaSessionStatusService.Status.NO_MFA);
    }

    @Test
    @DisplayName("a session that verified MFA more than 12 h ago is too old")
    void tooOld() {
        assertThat(checkWith(facts(Duration.ofHours(13), true, Duration.ofHours(30), Duration.ofHours(25))))
                .isEqualTo(MfaSessionStatusService.Status.MFA_TOO_OLD);
    }

    @Test
    @DisplayName("a factor whose notice was not sent, or went out less than 24 h ago, is refused")
    void freshFactor() {
        assertThat(checkWith(facts(Duration.ofHours(1), true, Duration.ofHours(30), null)))
                .isEqualTo(MfaSessionStatusService.Status.MFA_NOTICE_NOT_DELIVERED);
        assertThat(checkWith(facts(Duration.ofHours(1), true, null, Duration.ofHours(25))))
                .as("no enrolment time (no code path writes this)")
                .isEqualTo(MfaSessionStatusService.Status.MFA_NOTICE_NOT_DELIVERED);
        assertThat(checkWith(facts(Duration.ofHours(1), true, Duration.ofHours(30), Duration.ofHours(23))))
                .isEqualTo(MfaSessionStatusService.Status.MFA_ENROLLED_TOO_RECENTLY);
    }

    @Test
    @DisplayName("several live rows (a rotation race): the first, which the query orders newest first, decides")
    void firstRowDecides() {
        assertThat(checkWith(
                facts(Duration.ofHours(1), true, Duration.ofHours(30), Duration.ofHours(25)),
                facts(null, true, Duration.ofHours(30), Duration.ofHours(25))))
                .isEqualTo(MfaSessionStatusService.Status.ACCEPTED);
    }

    @Test
    @DisplayName("no user, tenant or session: no MFA, without asking the database")
    void nullGuards() {
        assertThat(service.check(null, "acme", UUID.randomUUID())).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        assertThat(service.check(UUID.randomUUID(), null, UUID.randomUUID()))
                .isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        assertThat(service.check(UUID.randomUUID(), "acme", null)).isEqualTo(MfaSessionStatusService.Status.NO_MFA);
        verifyNoInteractions(repository);
    }
}
