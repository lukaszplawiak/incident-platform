package com.incidentplatform.incident.api;

import com.incidentplatform.incident.dto.AuditEventDto;
import com.incidentplatform.incident.service.AuditQueryService;
import com.incidentplatform.shared.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incident Audit",
        description = "Audit log — full chronological history of incident events")
@SecurityRequirement(name = "Bearer Authentication")
public class IncidentAuditController {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentAuditController.class);

    private final AuditQueryService auditQueryService;

    public IncidentAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/{incidentId}/audit")
    @PreAuthorize("hasRole('ROLE_RESPONDER') or hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Get incident audit log",
            description = "Returns full chronological history of all events " +
                    "for the incident — creation, notifications, escalations, " +
                    "acknowledgement, resolution and postmortem generation."
    )
    @ApiResponse(responseCode = "200",
            description = "Audit log retrieved successfully")
    @ApiResponse(responseCode = "401",
            description = "Missing or invalid JWT token")
    @ApiResponse(responseCode = "403",
            description = "Insufficient permissions")
    public ResponseEntity<List<AuditEventDto>> getAuditLog(
            @PathVariable UUID incidentId) {

        final String tenantId = TenantContext.getRequired();

        log.debug("GET /api/v1/incidents/{}/audit, tenant={}",
                incidentId, tenantId);

        final List<AuditEventDto> auditLog =
                auditQueryService.getAuditLog(incidentId, tenantId);

        return ResponseEntity.ok(auditLog);
    }
}