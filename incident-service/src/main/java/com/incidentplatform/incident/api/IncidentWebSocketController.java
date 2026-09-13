package com.incidentplatform.incident.api;

import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import com.incidentplatform.incident.dto.IncidentFilter;

import java.security.Principal;
import java.util.List;

/**
 * <h2>Fixed: {@code TenantContext.getOrNull()} never actually worked here</h2>
 * Both handlers previously read the current tenant via
 * {@code TenantContext.getOrNull()} — a thread-local, set only by
 * {@code JwtAuthFilter} during the one-time HTTP handshake that upgrades
 * a connection to WebSocket. Every subsequent STOMP frame (including the
 * {@code SUBSCRIBE}/{@code SEND} frames that trigger these two methods)
 * is processed on a different thread from Spring's STOMP message-handling
 * thread pool, where that thread-local was never set — so
 * {@code TenantContext.getOrNull()} always returned {@code null} here,
 * and both methods always fell into their own "no tenant context" branch,
 * returning an empty list unconditionally. Confirmed the frontend's own
 * {@code websocket.service.ts} never actually calls either endpoint
 * ({@code /incidents/subscribe} or {@code /incidents/refresh}) — this
 * had no visible symptom in the shipped product, but both were genuinely
 * broken for any client that did call them (a raw STOMP client, or a
 * future frontend feature).
 *
 * <p>Fixed by reading the tenant from the {@link Principal} argument
 * instead — correctly populated now by
 * {@code StompAuthChannelInterceptor} (see its own Javadoc), which
 * attaches a {@link UserPrincipal} to the STOMP <em>session</em> (not a
 * thread) at {@code CONNECT} time, making it correctly available in
 * every subsequent frame of that same session regardless of which
 * thread handles it.
 */
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
        final String tenantId = extractTenantId(principal);

        if (tenantId == null) {
            log.warn("WebSocket subscribe without tenant context, principal={}",
                    principal != null ? principal.getName() : "anonymous");
            return List.of();
        }

        log.info("WebSocket subscribe: tenant={}, principal={}",
                tenantId, principal.getName());

        final var filter = new IncidentFilter(
                null, null, null, null, null);
        final var pageable = PageRequest.of(0, 50,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return queryService.findAll(tenantId, filter, pageable)
                .getContent();
    }

    /**
     * <h2>Fixed: {@code @SendTo} was a cross-tenant broadcast waiting to
     * happen</h2>
     * Previously {@code @SendTo("/topic/incidents/refresh-response")} — a
     * single, non-tenant-scoped destination shared by every connected
     * client regardless of tenant. As long as {@code TenantContext
     * .getOrNull()} always returned {@code null} (see this class's own
     * Javadoc), this method always returned an empty list, so the
     * broadcast carried no real data — but fixing that thread-local bug
     * on its own, without also fixing this, would have turned a dormant
     * bug into an active one: tenant A's incident data would have started
     * broadcasting to every client subscribed to that same shared topic,
     * regardless of their own tenant. {@code @SendToUser} is the correct
     * tool for "reply only to the caller" — Spring routes it to a
     * per-session destination derived from the STOMP session's own
     * {@link Principal}, never to other connected clients.
     */
    @MessageMapping("/incidents/refresh")
    @SendToUser("/queue/incidents/refresh-response")
    public List<IncidentDto> handleRefresh(Principal principal) {
        final String tenantId = extractTenantId(principal);

        if (tenantId == null) {
            log.warn("WebSocket refresh without tenant context");
            return List.of();
        }

        log.debug("WebSocket refresh requested: tenant={}", tenantId);

        final var filter = new IncidentFilter(
                null, null, null, null, null);
        final var pageable = PageRequest.of(0, 50,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return queryService.findAll(tenantId, filter, pageable)
                .getContent();
    }

    private String extractTenantId(Principal principal) {
        return principal instanceof UserPrincipal userPrincipal
                ? userPrincipal.tenantId()
                : null;
    }
}