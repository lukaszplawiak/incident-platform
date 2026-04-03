package com.incidentplatform.incident.api;

import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

@Controller
public class IncidentWebSocketController {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentWebSocketController.class);

    private final IncidentQueryService queryService;

    public IncidentWebSocketController(IncidentQueryService queryService) {
        this.queryService = queryService;
    }

    @SubscribeMapping("/incidents/subscribe")
    public List<IncidentDto> handleSubscribe(Principal principal) {
        final String tenantId = TenantContext.getOrNull();

        if (tenantId == null) {
            log.warn("WebSocket subscribe without tenant context, principal={}",
                    principal != null ? principal.getName() : "anonymous");
            return List.of();
        }

        log.info("WebSocket subscribe: tenant={}, principal={}",
                tenantId, principal != null ? principal.getName() : "anonymous");

        final var filter = new com.incidentplatform.incident.dto.IncidentFilter(
                null, null, null, null);
        final var pageable = PageRequest.of(0, 50,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return queryService.findAll(tenantId, filter, pageable)
                .getContent();
    }

    @MessageMapping("/incidents/refresh")
    @SendTo("/topic/incidents/refresh-response")
    public List<IncidentDto> handleRefresh(Principal principal) {
        final String tenantId = TenantContext.getOrNull();

        if (tenantId == null) {
            log.warn("WebSocket refresh without tenant context");
            return List.of();
        }

        log.debug("WebSocket refresh requested: tenant={}", tenantId);

        final var filter = new com.incidentplatform.incident.dto.IncidentFilter(
                null, null, null, null);
        final var pageable = PageRequest.of(0, 50,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return queryService.findAll(tenantId, filter, pageable)
                .getContent();
    }
}