package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.TenantSettings;
import com.incidentplatform.auth.dto.TenantSettingsDto;
import com.incidentplatform.auth.repository.TenantSettingsRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Tenant settings warn the admins while the tenant has one active admin
 * (backlog #0-90): with a second admin either can reset the other's MFA at
 * once (#0-88), with one a lost factor needs the operator's delayed recovery.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantSettingsService")
class TenantSettingsServiceTest {

    private static final String TENANT = "acme";

    @Mock private TenantSettingsRepository settingsRepository;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private UserRepository userRepository;

    private TenantSettingsService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        service = new TenantSettingsService(settingsRepository, auditEventPublisher, userRepository);
        given(settingsRepository.findById(TENANT)).willReturn(Optional.of(TenantSettings.defaults(TENANT)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("one active admin (or none) — singleAdmin, two — not")
    void singleAdminWarning() {
        given(userRepository.countActiveAcceptedUsersWithRole(TENANT, Role.ROLE_ADMIN)).willReturn(1L, 0L, 2L);

        assertThat(service.getSettings()).isEqualTo(new TenantSettingsDto(TENANT, false, 1, true));
        assertThat(service.getSettings().singleAdmin()).isTrue();
        assertThat(service.getSettings()).isEqualTo(new TenantSettingsDto(TENANT, false, 2, false));
    }

    @Test
    @DisplayName("the update's answer carries the same warning")
    void updateCarriesWarning() {
        given(userRepository.countActiveAcceptedUsersWithRole(TENANT, Role.ROLE_ADMIN)).willReturn(1L);
        final UserPrincipal admin = new UserPrincipal(UUID.randomUUID(), TENANT, "a@acme.test",
                List.of("ROLE_ADMIN"), List.of());

        assertThat(service.updateSettings(true, admin)).isEqualTo(new TenantSettingsDto(TENANT, true, 1, true));
    }
}
