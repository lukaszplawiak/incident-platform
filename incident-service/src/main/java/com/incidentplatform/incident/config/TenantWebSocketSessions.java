package com.incidentplatform.incident.config;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Closes the live WebSocket sessions of a tenant suspended in full (backlog
 * #0-82, step 2).
 *
 * <p>{@link StompAuthChannelInterceptor} refuses a suspended tenant's STOMP
 * {@code CONNECT}, but a session opened before the suspension would otherwise
 * keep receiving the tenant's incidents until the browser closes it: a
 * WebSocket outlives the access token it was opened with. So every session is
 * tracked here — the socket through a {@link WebSocketHandlerDecoratorFactory}
 * registered in {@link WebSocketConfig}, its tenant from the {@code CONNECT}
 * the interceptor authenticated — and {@link #closeSuspended()} closes the
 * sessions of every tenant whose access is {@link TenantAccess#NONE}, within
 * two sweeps of the suspension (the status is read without waiting, so a
 * changed one is seen by the sweep after it was refreshed). A read-only tenant
 * keeps its sessions: reading is what it is left with.
 *
 * <h2>No ShedLock, on purpose</h2>
 * Every other {@code @Scheduled} job carries a ShedLock so that replicas do not
 * double-fire. This one must run on every replica: the sessions are held in
 * this JVM, and a lock would leave every other replica's sessions open.
 */
@Component
public class TenantWebSocketSessions implements WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(TenantWebSocketSessions.class);

    static final String CLOSED_COUNTER = "websocket.sessions.closed.suspended";
    static final String UNBOUND_COUNTER = "websocket.sessions.unbound";

    private final TenantStatusProvider tenantStatusProvider;
    private final Counter closed;
    private final Counter unbound;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> tenantBySession = new ConcurrentHashMap<>();

    public TenantWebSocketSessions(TenantStatusProvider tenantStatusProvider, MeterRegistry meterRegistry) {
        this.tenantStatusProvider = tenantStatusProvider;
        this.closed = Counter.builder(CLOSED_COUNTER)
                .description("WebSocket sessions closed because their tenant was suspended (backlog #0-82)")
                .register(meterRegistry);
        this.unbound = Counter.builder(UNBOUND_COUNTER)
                .description("Authenticated STOMP sessions whose socket was not tracked, so a suspension cannot "
                        + "close them (backlog #0-82)")
                .register(meterRegistry);
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                tenantBySession.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * Records the tenant of an authenticated STOMP session. A session that
     * closed in the meantime is not recorded, so nothing is left behind.
     *
     * <p>A CONNECT whose socket is not tracked is WARNed and counted (found in
     * review): it relies on STOMP's session id being the {@link WebSocketSession}'s
     * id, which holds for this plain WebSocket endpoint. Should a transport ever
     * break that (SockJS, a proxy), the counter shows that suspensions no longer
     * close sessions, instead of nothing at all. A client that closed during
     * CONNECT is counted too, rarely.
     */
    void bind(String sessionId, String tenantId) {
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            unbound.increment();
            log.warn("Authenticated WebSocket session not tracked, a suspension cannot close it: tenant={}",
                    tenantId);
            return;
        }
        tenantBySession.put(sessionId, tenantId);
        if (!sessions.containsKey(sessionId)) {
            // Closed between the check and the put.
            tenantBySession.remove(sessionId);
        }
    }

    /**
     * Closes every session of a tenant suspended in full; asks each tenant's
     * status once per sweep. A tenant whose status cannot be read is left for
     * the next sweep, and so is a session whose close failed (found in review:
     * it was unbound first, so a failed close was never tried again and the
     * session kept receiving the tenant's incidents).
     */
    @Scheduled(fixedDelayString = "${websocket.suspended-sweep-interval-ms:10000}",
            initialDelayString = "${websocket.suspended-sweep-interval-ms:10000}")
    public void closeSuspended() {
        final Map<String, TenantAccess> accessByTenant = new HashMap<>();
        tenantBySession.forEach((sessionId, tenantId) -> {
            final TenantAccess access = accessByTenant.computeIfAbsent(tenantId, this::accessOrNull);
            if (access == TenantAccess.NONE) {
                close(sessionId, tenantId);
            }
        });
    }

    /** The provider answers rather than throws; a defect in it must not end the sweep for every tenant. */
    private TenantAccess accessOrNull(String tenantId) {
        try {
            // Never waits on auth-service (found in review: one timed-out call per
            // tenant made a sweep in an outage last minutes); a status refreshed in
            // the background is seen by the next sweep.
            return tenantStatusProvider.knownAccessOf(tenantId);
        } catch (RuntimeException e) {
            log.error("Tenant status could not be read for the WebSocket sweep, retried next sweep: tenant={}, "
                    + "error={}", tenantId, e.getClass().getSimpleName());
            return null;
        }
    }

    private void close(String sessionId, String tenantId) {
        final WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            tenantBySession.remove(sessionId);
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Organisation suspended"));
        } catch (IOException e) {
            // Still bound: the next sweep tries again. afterConnectionClosed
            // unbinds it if the transport closes it meanwhile.
            log.warn("WebSocket session of a suspended tenant could not be closed, retried next sweep: tenant={}, "
                    + "error={}", tenantId, e.getClass().getSimpleName());
            return;
        }
        tenantBySession.remove(sessionId);
        closed.increment();
        log.info("WebSocket session of a suspended tenant closed: tenant={}, session={}", tenantId, sessionId);
    }

    /** For tests only. */
    int trackedSessions() {
        return sessions.size();
    }

    /** For tests only. */
    int boundSessions() {
        return tenantBySession.size();
    }
}
