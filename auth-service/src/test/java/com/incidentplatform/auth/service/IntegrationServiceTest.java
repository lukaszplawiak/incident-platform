package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateIntegrationRequest;
import com.incidentplatform.auth.ratelimit.ApiKeyCreationLimit;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import com.incidentplatform.auth.repository.TeamRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationService")
class IntegrationServiceTest {

    private static final String TENANT_ID = "acme";
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock private IntegrationRepository integrationRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private ApiKeyHasher apiKeyHasher;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private AuthEmailRequestService authEmailRequestService;
    @Mock private ApiKeyCreationLimit creationLimit;

    private IntegrationService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationService(integrationRepository, apiKeyRepository, teamRepository,
                apiKeyHasher, auditEventPublisher, userRepository, authEmailRequestService, creationLimit,
                new AfterCommit(Runnable::run, new SimpleMeterRegistry()));
        TenantContext.set(TENANT_ID);
        org.mockito.Mockito.lenient().when(creationLimit.check(any(), any()))
                .thenReturn(RateLimitDecision.ALLOWED);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("tells the admin who created it, as for any tenant key (backlog #0-89)")
    void emailsCreatingAdmin() {
        final User admin = User.forTesting(ADMIN_ID, TENANT_ID, "admin@acme.example", "hash", true,
                List.of("ROLE_ADMIN"));
        given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT_ID)).willReturn(Optional.of(admin));
        given(apiKeyHasher.generateRawKey()).willReturn("ipl_abcdefgh12345678901234567890123456");
        given(apiKeyHasher.hash(anyString())).willReturn("sha256hashvalue");
        given(apiKeyHasher.extractPrefix(anyString())).willReturn("abcdefgh");
        final AtomicReference<ApiKey> savedKey = new AtomicReference<>();
        given(apiKeyRepository.save(any())).willAnswer(i -> {
            savedKey.set(withId(i.getArgument(0, ApiKey.class)));
            return savedKey.get();
        });
        given(integrationRepository.save(any())).willAnswer(i -> withId(i.getArgument(0, Integration.class)));

        final var response = service.createIntegration(
                new CreateIntegrationRequest("Prometheus prod", "prometheus", null, null), admin());

        assertThat(response.apiKey()).startsWith("ipl_");
        then(authEmailRequestService).should().requestApiKeyCreatedNotification(admin, savedKey.get().getId());
        // Backlog #0-89: who made it, for a later clean-up.
        assertThat(savedKey.get().getCreatedByUserId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("a duplicate name creates nothing and emails nobody")
    void duplicateNameEmailsNobody() {
        given(integrationRepository.existsByNameAndTenantId("Prometheus prod", TENANT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.createIntegration(
                new CreateIntegrationRequest("Prometheus prod", "prometheus", null, null), admin()))
                .isInstanceOf(BusinessException.class);

        then(apiKeyRepository).shouldHaveNoInteractions();
        then(authEmailRequestService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("an integration's key counts for the hourly creation limit: 429, nothing created (backlog #0-89)")
    void refusedByCreationLimit() {
        given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT_ID)).willReturn(Optional.of(
                User.forTesting(ADMIN_ID, TENANT_ID, "admin@acme.example", "hash", true, List.of("ROLE_ADMIN"))));
        given(creationLimit.check(TENANT_ID, ADMIN_ID)).willReturn(new com.incidentplatform.auth.ratelimit
                .RateLimitDecision(RateLimitDecision.Outcome.LIMITED, 60));

        assertThatThrownBy(() -> service.createIntegration(
                new CreateIntegrationRequest("Prometheus prod", "prometheus", null, null), admin()))
                .isInstanceOf(RateLimitRefusedException.class);
        then(apiKeyRepository).shouldHaveNoInteractions();
        then(authEmailRequestService).shouldHaveNoInteractions();
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(ADMIN_ID, TENANT_ID, "admin@acme.example", List.of("ROLE_ADMIN"), List.of());
    }

    /** Stands in for JPA's generated id. */
    private static <T> T withId(T entity) {
        try {
            final var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            if (field.get(entity) == null) {
                field.set(entity, UUID.randomUUID());
            }
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
