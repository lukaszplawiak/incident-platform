package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
            String tenantId, UUID incidentId);

    List<AuditEvent> findByTenantIdAndEventTypeOrderByOccurredAtDesc(
            String tenantId, String eventType);
}