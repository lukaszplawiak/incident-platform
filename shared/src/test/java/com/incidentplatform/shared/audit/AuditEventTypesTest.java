package com.incidentplatform.shared.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit event types are a contract: they are published to Kafka and stored
 * for compliance, and {@code audit_events.event_type} is a plain string with no
 * whitelist on the consuming side. Nothing but this test would notice a typo or
 * a duplicated value.
 */
@DisplayName("AuditEventTypes")
class AuditEventTypesTest {

    private static List<Field> constants() {
        final List<Field> constants = new ArrayList<>();
        for (final Field field : AuditEventTypes.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                constants.add(field);
            }
        }
        return constants;
    }

    @Test
    @DisplayName("every value equals its constant name, as the convention requires")
    void valueEqualsName() throws IllegalAccessException {
        for (final Field field : constants()) {
            assertThat(field.get(null)).as(field.getName()).isEqualTo(field.getName());
        }
    }

    @Test
    @DisplayName("no two constants share a value")
    void valuesAreUnique() throws IllegalAccessException {
        final Set<Object> seen = new HashSet<>();
        for (final Field field : constants()) {
            assertThat(seen.add(field.get(null))).as(field.getName()).isTrue();
        }
    }

    @Test
    @DisplayName("every value fits the audit_events.event_type column (VARCHAR(100))")
    void valuesFitTheColumn() throws IllegalAccessException {
        for (final Field field : constants()) {
            assertThat(((String) field.get(null)).length()).as(field.getName()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("the undeliverable type exists and is distinct from a failed send")
    void undeliverableIsItsOwnType() {
        assertThat(AuditEventTypes.NOTIFICATION_UNDELIVERABLE)
                .isEqualTo("NOTIFICATION_UNDELIVERABLE")
                .isNotEqualTo(AuditEventTypes.NOTIFICATION_FAILED);
    }

    @Test
    @DisplayName("tenant provisioning has its own types, distinct from the user events it causes (backlog #0-80)")
    void tenantProvisioningTypes() {
        assertThat(AuditEventTypes.TENANT_PROVISIONED)
                .isEqualTo("TENANT_PROVISIONED")
                .isNotEqualTo(AuditEventTypes.USER_CREATED);
        assertThat(AuditEventTypes.TENANT_ADMIN_REINVITED)
                .isEqualTo("TENANT_ADMIN_REINVITED")
                .isNotEqualTo(AuditEventTypes.USER_INVITE_RESENT);
    }

    @Test
    @DisplayName("an admin's MFA reset and its break-glass form have their own types (backlog #0-88)")
    void mfaResetByAdminType() {
        assertThat(AuditEventTypes.MFA_RESET_BY_ADMIN)
                .isEqualTo("MFA_RESET_BY_ADMIN")
                .isNotEqualTo(AuditEventTypes.MFA_DISABLED);
        assertThat(AuditEventTypes.MFA_RESET_BREAK_GLASS)
                .isEqualTo("MFA_RESET_BREAK_GLASS")
                .isNotEqualTo(AuditEventTypes.MFA_RESET_BY_ADMIN);
    }

    @Test
    @DisplayName("a tenant's suspension and resumption have their own types (backlog #0-82)")
    void tenantSuspensionTypes() {
        assertThat(List.of(AuditEventTypes.TENANT_SUSPENDED, AuditEventTypes.TENANT_RESUMED))
                .containsExactly("TENANT_SUSPENDED", "TENANT_RESUMED")
                .doesNotContain(AuditEventTypes.TENANT_PROVISIONED, AuditEventTypes.USER_STATUS_UPDATED);
    }

    @Test
    @DisplayName("operator-assisted MFA recovery has its own types, one per outcome (backlog #0-90)")
    void mfaRecoveryTypes() {
        assertThat(List.of(
                AuditEventTypes.MFA_RECOVERY_REQUESTED,
                AuditEventTypes.MFA_RECOVERY_CANCELLED,
                AuditEventTypes.MFA_RECOVERY_EXECUTED,
                AuditEventTypes.MFA_RECOVERY_EXPIRED))
                .containsExactly("MFA_RECOVERY_REQUESTED", "MFA_RECOVERY_CANCELLED",
                        "MFA_RECOVERY_EXECUTED", "MFA_RECOVERY_EXPIRED")
                .doesNotContain(AuditEventTypes.MFA_RESET_BY_ADMIN, AuditEventTypes.MFA_RESET_BREAK_GLASS);
    }
}
