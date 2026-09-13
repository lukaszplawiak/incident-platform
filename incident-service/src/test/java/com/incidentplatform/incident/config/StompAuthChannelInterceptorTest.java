package com.incidentplatform.incident.config;

import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TokenRevocationChecker;
import com.incidentplatform.shared.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * The actual regression coverage for the WebSocket authentication gap —
 * see {@link StompAuthChannelInterceptor}'s own Javadoc for the full
 * account of what this class exists to close. Uses real
 * {@link StompHeaderAccessor}/{@link MessageBuilder} instances rather
 * than mocking the message/accessor objects — this is the standard
 * pattern for testing Spring STOMP {@code ChannelInterceptor}s, and
 * avoids asserting against a brittle mock of Spring's own internals.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StompAuthChannelInterceptor")
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private TokenRevocationChecker revocationChecker;

    @Mock
    private Claims claims;

    private StompAuthChannelInterceptor interceptor;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";
    private static final String EMAIL = "user@acme-corp.com";

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtUtils, revocationChecker);
    }

    private static Message<byte[]> connectMessage(String authHeaderValue) {
        final StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeaderValue != null) {
            accessor.addNativeHeader("Authorization", authHeaderValue);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> subscribeMessage(String destination, Principal principal) {
        final StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ── CONNECT ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CONNECT")
    class Connect {

        @Test
        @DisplayName("rejects a CONNECT with no Authorization header at all")
        void rejectsMissingAuthHeader() {
            final Message<byte[]> message = connectMessage(null);

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("rejects a CONNECT with a malformed Authorization header " +
                "(missing 'Bearer ' prefix)")
        void rejectsMalformedAuthHeader() {
            final Message<byte[]> message = connectMessage("NotBearer sometoken");

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("rejects a CONNECT with an invalid or expired token")
        void rejectsInvalidToken() {
            given(jwtUtils.validateAndGetClaims("bad-token")).willReturn(Optional.empty());
            final Message<byte[]> message = connectMessage("Bearer bad-token");

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("rejects a CONNECT with a revoked token")
        void rejectsRevokedToken() {
            given(jwtUtils.validateAndGetClaims("revoked-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractJti(claims)).willReturn(Optional.of("jti-123"));
            given(revocationChecker.isRevoked("jti-123")).willReturn(true);

            final Message<byte[]> message = connectMessage("Bearer revoked-token");

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("rejects a CONNECT when the token is missing a required claim")
        void rejectsTokenMissingRequiredClaim() {
            given(jwtUtils.validateAndGetClaims("incomplete-token"))
                    .willReturn(Optional.of(claims));
            given(jwtUtils.extractJti(claims)).willReturn(Optional.empty());
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.empty());

            final Message<byte[]> message = connectMessage("Bearer incomplete-token");

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        /**
         * The actual regression test for the core vulnerability: a valid
         * token must result in a real {@link UserPrincipal}, correctly
         * populated, attached to the STOMP session — this is what makes
         * every subsequent SUBSCRIBE/SEND frame on this same session
         * correctly identifiable, replacing the broken thread-local
         * TenantContext IncidentWebSocketController used to rely on.
         */
        @Test
        @DisplayName("attaches a correctly-populated UserPrincipal on a valid token")
        void attachesPrincipalOnValidToken() {
            given(jwtUtils.validateAndGetClaims("good-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractJti(claims)).willReturn(Optional.of("jti-456"));
            given(revocationChecker.isRevoked("jti-456")).willReturn(false);
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.of(USER_ID));
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));
            given(jwtUtils.extractRoles(claims)).willReturn(List.of("ROLE_RESPONDER"));
            given(jwtUtils.extractTeamIds(claims)).willReturn(List.of());
            given(jwtUtils.extractManagedTeamIds(claims)).willReturn(List.of());

            final StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good-token");
            accessor.setLeaveMutable(true);
            final Message<byte[]> message =
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            interceptor.preSend(message, null);

            final StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(message);
            final Principal principal = resultAccessor.getUser();

            assertThat(principal).isInstanceOf(UserPrincipal.class);
            final UserPrincipal userPrincipal = (UserPrincipal) principal;
            assertThat(userPrincipal.userId()).isEqualTo(USER_ID);
            assertThat(userPrincipal.tenantId()).isEqualTo(TENANT_ID);
            assertThat(userPrincipal.email()).isEqualTo(EMAIL);
        }
    }

    // ── SUBSCRIBE ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SUBSCRIBE")
    class Subscribe {

        private final UserPrincipal principal = new UserPrincipal(
                USER_ID, TENANT_ID, EMAIL, List.of("ROLE_RESPONDER"), List.of());

        @Test
        @DisplayName("allows subscribing to the caller's own tenant topic")
        void allowsOwnTenantTopic() {
            final Message<byte[]> message =
                    subscribeMessage("/topic/incidents/" + TENANT_ID, principal);

            assertThatCode(() -> interceptor.preSend(message, null))
                    .doesNotThrowAnyException();
        }

        /**
         * The actual regression test for the second half of the
         * vulnerability: authentication alone is not enough — a
         * legitimately authenticated user for one tenant must not be
         * able to subscribe to another tenant's topic.
         */
        @Test
        @DisplayName("rejects subscribing to another tenant's topic")
        void rejectsOtherTenantTopic() {
            final Message<byte[]> message =
                    subscribeMessage("/topic/incidents/some-other-tenant", principal);

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }

        @Test
        @DisplayName("does not interfere with subscriptions to destinations " +
                "outside the tenant-scoped topic prefix")
        void ignoresUnrelatedDestinations() {
            final Message<byte[]> message =
                    subscribeMessage("/user/queue/incidents/refresh-response", principal);

            assertThatCode(() -> interceptor.preSend(message, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a SUBSCRIBE with no authenticated principal on the session")
        void rejectsUnauthenticatedSubscribe() {
            final Message<byte[]> message =
                    subscribeMessage("/topic/incidents/" + TENANT_ID, null);

            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessagingException.class);
        }
    }
}