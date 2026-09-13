package com.incidentplatform.incident.config;

import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates STOMP {@code CONNECT} frames and verifies {@code SUBSCRIBE}
 * destinations against the connecting user's own tenant.
 *
 * <h2>The gap this closes</h2>
 * {@code /ws/**} is (correctly) {@code permitAll()} at the HTTP level in
 * {@link SecurityConfig} — a browser's native WebSocket handshake cannot
 * carry a custom {@code Authorization} header, so the handshake itself
 * can never be authenticated the way an ordinary HTTP request is.
 * {@link WebSocketConfig}'s own comment on that {@code permitAll()} rule
 * said authentication was "handled inside STOMP protocol" — but before
 * this class existed, nothing did that: {@code configureClientInboundChannel}
 * was never overridden, so no {@link ChannelInterceptor} of any kind ran
 * for STOMP frames. In practice this meant {@code /ws/**} was fully
 * unauthenticated end to end: any client — no login, no token — could
 * connect and subscribe to {@code /topic/incidents/{tenantId}} for
 * <em>any</em> tenant, receiving a live stream of that tenant's incident
 * data (titles, descriptions, severity, assignment) with zero
 * authentication or authorization. Found and confirmed during a
 * frontend/backend consistency review: the frontend's own
 * {@code websocket.service.ts} was already sending
 * {@code connectHeaders: { Authorization: 'Bearer ' + token }} on every
 * connection attempt — correctly anticipating exactly this check — but
 * the token was silently ignored by the backend the entire time.
 *
 * <h2>What this checks, and why at these two specific commands</h2>
 * <ul>
 *   <li>{@code CONNECT} — the one point in a STOMP session's lifecycle
 *       equivalent to an HTTP request's {@code Authorization} header
 *       check. Missing/invalid/expired/revoked token → the message is
 *       rejected (a {@link MessagingException} thrown from
 *       {@link #preSend} causes Spring to send a STOMP {@code ERROR}
 *       frame and close the connection) — there is no authenticated
 *       fallback, unlike some HTTP endpoints that permit anonymous
 *       access. A valid token results in a {@link UserPrincipal} being
 *       attached to the STOMP session via {@code accessor.setUser(...)}.
 *       Spring associates this with the <em>session</em>, not the
 *       thread processing this one frame — correctly available in
 *       every subsequent frame of the same session regardless of which
 *       thread pool thread handles it, unlike the thread-local
 *       {@code TenantContext} that {@code IncidentWebSocketController}
 *       used to (incorrectly) rely on for this same purpose.
 *   <li>{@code SUBSCRIBE} — authenticating the connection alone is not
 *       enough on its own: a legitimately authenticated user for tenant
 *       A could still request a subscription to
 *       {@code /topic/incidents/B} for some other tenant B's topic
 *       unless the destination itself is checked against their own
 *       tenant. Rejected the same way as an authentication failure —
 *       there is no scenario where subscribing to another tenant's
 *       topic is legitimate.
 * </ul>
 *
 * <h2>Reuses {@link JwtUtils}, not a parallel validation path</h2>
 * The same token-parsing/claim-extraction methods {@link JwtAuthFilter}
 * already uses for ordinary HTTP requests are reused here rather than
 * reimplemented — same validation logic, same revocation check via
 * {@link TokenRevocationChecker}, for both transports this service
 * exposes.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String TENANT_TOPIC_PREFIX = "/topic/incidents/";

    private final JwtUtils jwtUtils;
    private final TokenRevocationChecker revocationChecker;

    public StompAuthChannelInterceptor(JwtUtils jwtUtils,
                                       TokenRevocationChecker revocationChecker) {
        this.jwtUtils = jwtUtils;
        this.revocationChecker = revocationChecker;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        final StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscribe(accessor);
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        final String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("WebSocket CONNECT rejected — missing or malformed " +
                    "Authorization header");
            throw new MessagingException(
                    "Missing or malformed Authorization header");
        }

        final String rawToken = authHeader.substring("Bearer ".length());

        final Claims claims = jwtUtils.validateAndGetClaims(rawToken)
                .orElseThrow(() -> {
                    log.warn("WebSocket CONNECT rejected — invalid or expired token");
                    return new MessagingException("Invalid or expired token");
                });

        final Optional<String> jti = jwtUtils.extractJti(claims);
        if (jti.isPresent() && revocationChecker.isRevoked(jti.get())) {
            log.warn("WebSocket CONNECT rejected — revoked token, jti={}", jti.get());
            throw new MessagingException("Token has been revoked");
        }

        final UUID userId = jwtUtils.extractUserId(claims)
                .orElseThrow(() -> new MessagingException(
                        "Token missing required userId claim"));
        final String tenantId = jwtUtils.extractTenantId(claims)
                .orElseThrow(() -> new MessagingException(
                        "Token missing required tenantId claim"));
        final String email = jwtUtils.extractEmail(claims)
                .orElseThrow(() -> new MessagingException(
                        "Token missing required email claim"));
        final List<String> roles = jwtUtils.extractRoles(claims);
        final List<UUID> teamIds = jwtUtils.extractTeamIds(claims);
        final List<UUID> managedTeamIds = jwtUtils.extractManagedTeamIds(claims);

        final UserPrincipal principal = new UserPrincipal(
                userId, tenantId, email, roles, teamIds, managedTeamIds);

        accessor.setUser(principal);

        log.info("WebSocket CONNECT authenticated: userId={}, tenant={}",
                userId, tenantId);
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        final Principal principal = accessor.getUser();

        if (!(principal instanceof UserPrincipal userPrincipal)) {
            // Should be unreachable in practice — a session that failed
            // CONNECT authentication never gets this far, since preSend
            // rejecting CONNECT closes the connection before any
            // SUBSCRIBE frame could arrive on it. Defended anyway: never
            // treat a missing principal as implicitly authorized.
            log.warn("WebSocket SUBSCRIBE rejected — no authenticated principal");
            throw new MessagingException("Not authenticated");
        }

        final String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TENANT_TOPIC_PREFIX)) {
            return;
        }

        final String requestedTenantId =
                destination.substring(TENANT_TOPIC_PREFIX.length());

        if (!requestedTenantId.equals(userPrincipal.tenantId())) {
            log.warn("WebSocket SUBSCRIBE rejected — userId={} (tenant={}) " +
                            "attempted to subscribe to another tenant's topic: {}",
                    userPrincipal.userId(), userPrincipal.tenantId(), destination);
            throw new MessagingException(
                    "Not authorized to subscribe to this destination");
        }
    }
}