package com.incidentplatform.shared.testutils;

import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.AlertReceivedEvent;
import com.incidentplatform.shared.events.IncidentAcknowledgedEvent;
import com.incidentplatform.shared.events.IncidentClosedEvent;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentOpenedEvent;
import com.incidentplatform.shared.events.IncidentResolvedEvent;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.security.UserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TestDataFactory {

    public static final String TEST_TENANT_ID = "test-tenant-acme";
    public static final String TEST_TENANT_ID_2 = "test-tenant-beta";
    public static final UUID TEST_USER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    public static final UUID TEST_INCIDENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");
    public static final UUID TEST_ALERT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003");

    private TestDataFactory() {
        throw new UnsupportedOperationException("TestDataFactory is a utility class");
    }

    public static UnifiedAlertDto aPrometheusAlert() {
        return new UnifiedAlertDto(
                TEST_ALERT_ID,
                TEST_TENANT_ID,
                "prometheus",
                SourceType.OPS,
                Severity.CRITICAL,
                "High CPU usage on prod-server-1",
                "CPU usage exceeded 95% for 5 minutes on instance prod-server-1:9100",
                Instant.now().minusSeconds(60),
                "prometheus:highcpuusage:prod-server-1:9100",
                Map.of(
                        "job", "node-exporter",
                        "instance", "prod-server-1:9100",
                        "alertname", "HighCpuUsage"
                )
        );
    }

    public static UnifiedAlertDto aWazuhAlert() {
        return new UnifiedAlertDto(
                UUID.randomUUID(),
                TEST_TENANT_ID,
                "wazuh",
                SourceType.SECURITY,
                Severity.HIGH,
                "Multiple failed SSH login attempts detected",
                "Brute force attack detected: 50 failed attempts from 192.168.1.100",
                Instant.now().minusSeconds(30),
                "wazuh:5551:003",
                Map.of(
                        "rule_id", "5551",
                        "agent_name", "web-server-01",
                        "source_ip", "192.168.1.100"
                )
        );
    }

    public static UnifiedAlertDto anAlertWithSeverity(Severity severity) {
        return new UnifiedAlertDto(
                UUID.randomUUID(),
                TEST_TENANT_ID,
                "prometheus",
                SourceType.OPS,
                severity,
                "Test alert with severity: " + severity.name(),
                null,
                Instant.now(),
                "prometheus:test-alert-with-severity-"
                        + severity.name().toLowerCase() + ":unknown",
                Map.of()
        );
    }

    public static UnifiedAlertDto anAlertForTenant(String tenantId) {
        return new UnifiedAlertDto(
                UUID.randomUUID(),
                tenantId,
                "prometheus",
                SourceType.OPS,
                Severity.HIGH,
                "Alert for tenant: " + tenantId,
                null,
                Instant.now(),
                "prometheus:alert-for-tenant-" + tenantId + ":unknown",
                Map.of()
        );
    }

    public static AlertReceivedEvent anAlertReceivedEvent() {
        return AlertReceivedEvent.from(
                TEST_TENANT_ID,
                "prometheus",
                SourceType.OPS,
                Severity.CRITICAL,
                "High CPU usage on prod-server-1",
                "CPU usage exceeded 95% for 5 minutes",
                Instant.now().minusSeconds(60),
                Map.of("job", "node-exporter", "instance", "prod-server-1:9100")
        );
    }

    public static AlertReceivedEvent aSecurityAlertReceivedEvent() {
        return AlertReceivedEvent.from(
                TEST_TENANT_ID,
                "wazuh",
                SourceType.SECURITY,
                Severity.HIGH,
                "Brute force attack detected",
                "50 failed SSH attempts from 192.168.1.100",
                Instant.now().minusSeconds(30),
                Map.of("rule_id", "5551", "source_ip", "192.168.1.100")
        );
    }

    public static IncidentOpenedEvent anIncidentOpenedEvent() {
        return new IncidentOpenedEvent(
                TEST_INCIDENT_ID,
                TEST_TENANT_ID,
                TEST_ALERT_ID,
                "prometheus:highcpuusage:prod-server-1:9100",
                "High CPU usage on prod-server-1",
                Severity.CRITICAL,
                SourceType.OPS,
                Instant.now()
        );
    }

    public static IncidentAcknowledgedEvent anIncidentAcknowledgedEvent() {
        return new IncidentAcknowledgedEvent(
                TEST_INCIDENT_ID,
                TEST_TENANT_ID,
                TEST_USER_ID,
                Instant.now()
        );
    }

    public static IncidentResolvedEvent anIncidentResolvedEvent() {
        return new IncidentResolvedEvent(
                TEST_INCIDENT_ID,
                TEST_TENANT_ID,
                TEST_USER_ID,
                "prometheus:highcpuusage:prod-server-1:9100",
                45L,
                "Restarted the overloaded service and scaled up the instance",
                Instant.now()
        );
    }

    public static IncidentEscalatedEvent anIncidentEscalatedEvent() {
        return new IncidentEscalatedEvent(
                TEST_INCIDENT_ID,
                TEST_TENANT_ID,
                TEST_USER_ID,
                1,
                Severity.CRITICAL,
                "High CPU usage on prod-server-1",
                Instant.now()
        );
    }

    public static IncidentClosedEvent anIncidentClosedEvent() {
        return new IncidentClosedEvent(
                TEST_INCIDENT_ID,
                TEST_TENANT_ID,
                TEST_USER_ID,
                UUID.randomUUID(),
                Instant.now()
        );
    }

    public static UserPrincipal aResponderPrincipal() {
        return new UserPrincipal(
                TEST_USER_ID,
                TEST_TENANT_ID,
                "responder@acme.com",
                List.of("ROLE_RESPONDER")
        );
    }

    public static UserPrincipal anAdminPrincipal() {
        return new UserPrincipal(
                UUID.randomUUID(),
                TEST_TENANT_ID,
                "admin@acme.com",
                List.of("ROLE_ADMIN", "ROLE_RESPONDER")
        );
    }

    public static UserPrincipal aPrincipalForTenant(String tenantId) {
        return new UserPrincipal(
                UUID.randomUUID(),
                tenantId,
                "user@" + tenantId + ".com",
                List.of("ROLE_RESPONDER")
        );
    }
}