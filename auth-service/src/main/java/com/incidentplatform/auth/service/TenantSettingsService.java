package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.TenantSettings;
import com.incidentplatform.auth.dto.TenantSettingsDto;
import com.incidentplatform.auth.repository.TenantSettingsRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class TenantSettingsService {

    private static final Logger log =
            LoggerFactory.getLogger(TenantSettingsService.class);

    private final TenantSettingsRepository settingsRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final UserRepository userRepository;

    public TenantSettingsService(TenantSettingsRepository settingsRepository,
                                 AuditEventPublisher auditEventPublisher,
                                 UserRepository userRepository) {
        this.settingsRepository = settingsRepository;
        this.auditEventPublisher = auditEventPublisher;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public TenantSettingsDto getSettings() {
        final String tenantId = TenantContext.get();
        final TenantSettings settings = getOrCreateDefaults(tenantId);
        return TenantSettingsDto.of(tenantId, settings.isMfaRequired(), activeAdmins(tenantId));
    }

    @Transactional
    public TenantSettingsDto updateSettings(boolean mfaRequired,
                                            UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        final TenantSettings settings = getOrCreateDefaults(tenantId);

        final boolean previous = settings.isMfaRequired();
        settings.setMfaRequired(mfaRequired);
        settingsRepository.save(settings);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.TENANT_MFA_POLICY_UPDATED,
                "auth-service",
                principal.userId().toString(),
                "Tenant MFA policy updated",
                Map.of("mfaRequired", String.valueOf(mfaRequired),
                        "previousValue", String.valueOf(previous)));

        log.info("Tenant MFA policy updated: tenant={}, mfaRequired={}, by={}",
                tenantId, mfaRequired, principal.userId());

        return TenantSettingsDto.of(tenantId, mfaRequired, activeAdmins(tenantId));
    }

    /**
     * Backlog #0-90: the settings warn a tenant's admins while it has one
     * active admin, the prevention side of the operator's MFA recovery (a
     * second admin can reset the first one's MFA at once, #0-88).
     */
    private long activeAdmins(String tenantId) {
        return userRepository.countActiveAcceptedUsersWithRole(tenantId, Role.ROLE_ADMIN);
    }

    /**
     * Returns MFA requirement for the given tenant.
     * Returns false (default) if no settings row exists yet.
     * Used by AuthService.login() — read-only, no transaction needed.
     */
    public boolean isMfaRequired(String tenantId) {
        return settingsRepository.findById(tenantId)
                .map(TenantSettings::isMfaRequired)
                .orElse(false);
    }

    private TenantSettings getOrCreateDefaults(String tenantId) {
        return settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaults(tenantId));
    }
}