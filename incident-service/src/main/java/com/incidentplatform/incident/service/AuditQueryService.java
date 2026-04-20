package com.incidentplatform.incident.service;

import com.incidentplatform.incident.dto.AuditEventDto;
import com.incidentplatform.incident.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {

    private static final Logger log =
            LoggerFactory.getLogger(AuditQueryService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditQueryService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditEventDto> getAuditLog(UUID incidentId, String tenantId) {
        log.debug("Fetching audit log: incidentId={}, tenant={}",
                incidentId, tenantId);

        return auditEventRepository
                .findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                        tenantId, incidentId)
                .stream()
                .map(AuditEventDto::from)
                .toList();
    }
}