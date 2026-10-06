package com.incidentplatform.incident.config;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * {@link TenantWebSocketSessions} (backlog #0-82, step 2): a tenant suspended in
 * full loses the WebSocket sessions it opened before the suspension; others keep
 * theirs, and closed sessions leave nothing behind.
 */
@DisplayName("TenantWebSocketSessions")
class TenantWebSocketSessionsTest {

    private TenantStatusProvider statusProvider;
    private SimpleMeterRegistry meters;
    private TenantWebSocketSessions sessions;
    private WebSocketHandler handler;

    @BeforeEach
    void setUp() {
        statusProvider = mock(TenantStatusProvider.class);
        given(statusProvider.knownAccessOf(any())).willReturn(TenantAccess.FULL);
        meters = new SimpleMeterRegistry();
        sessions = new TenantWebSocketSessions(statusProvider, meters);
        handler = sessions.decorate(mock(WebSocketHandler.class));
    }

    private WebSocketSession open(String id, String tenant) throws Exception {
        final WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        handler.afterConnectionEstablished(session);
        if (tenant != null) {
            sessions.bind(id, tenant);
        }
        return session;
    }

    @Test
    @DisplayName("closes only the sessions of a tenant suspended in full, asking each tenant once per sweep")
    void closesSuspendedOnly() throws Exception {
        final WebSocketSession acme1 = open("a1", "acme");
        final WebSocketSession acme2 = open("a2", "acme");
        final WebSocketSession globex = open("g1", "globex");
        final WebSocketSession readOnly = open("r1", "initech");
        given(statusProvider.knownAccessOf("acme")).willReturn(TenantAccess.NONE);
        given(statusProvider.knownAccessOf("initech")).willReturn(TenantAccess.READ_ONLY);

        sessions.closeSuspended();

        then(acme1).should().close(any(CloseStatus.class));
        then(acme2).should().close(any(CloseStatus.class));
        then(globex).should(never()).close(any(CloseStatus.class));
        then(readOnly).should(never()).close(any(CloseStatus.class));
        then(statusProvider).should(times(1)).knownAccessOf("acme");
        then(statusProvider).should(never()).accessOf(any());
        assertThat(meters.get(TenantWebSocketSessions.CLOSED_COUNTER).counter().count()).isEqualTo(2);
        assertThat(sessions.boundSessions()).as("closed sessions are not swept again").isEqualTo(2);
    }

    @Test
    @DisplayName("the close reason is a policy violation (1008), so a client does not reconnect blindly")
    void policyViolation() throws Exception {
        final WebSocketSession acme = open("a1", "acme");
        given(statusProvider.knownAccessOf("acme")).willReturn(TenantAccess.NONE);

        sessions.closeSuspended();

        final org.mockito.ArgumentCaptor<CloseStatus> status = org.mockito.ArgumentCaptor.forClass(CloseStatus.class);
        then(acme).should().close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    @DisplayName("a session closed by its client is forgotten; a bind after the close records nothing")
    void closedSessionsForgotten() throws Exception {
        final WebSocketSession session = open("a1", "acme");
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        sessions.bind("a1", "acme");
        sessions.bind("never-opened", "acme");

        assertThat(sessions.trackedSessions()).isZero();
        assertThat(sessions.boundSessions()).isZero();
        assertThat(meters.get(TenantWebSocketSessions.UNBOUND_COUNTER).counter().count())
                .as("a CONNECT whose socket is not tracked is counted").isEqualTo(2);
    }

    @Test
    @DisplayName("a session not yet bound (no CONNECT) is not closed, and a close that fails does not stop the sweep")
    void unboundAndFailingClose() throws Exception {
        open("unbound", null);
        final WebSocketSession failing = open("a1", "acme");
        final WebSocketSession other = open("a2", "acme");
        willThrow(new IOException("broken pipe")).given(failing).close(any(CloseStatus.class));
        given(statusProvider.knownAccessOf("acme")).willReturn(TenantAccess.NONE);

        sessions.closeSuspended();

        then(other).should().close(any(CloseStatus.class));
        assertThat(sessions.trackedSessions()).isEqualTo(3);
    }

    @Test
    @DisplayName("a session whose close failed stays bound and is closed by the next sweep (found in review)")
    void failedCloseRetried() throws Exception {
        final WebSocketSession failing = open("a1", "acme");
        willThrow(new IOException("broken pipe")).willDoNothing().given(failing).close(any(CloseStatus.class));
        given(statusProvider.knownAccessOf("acme")).willReturn(TenantAccess.NONE);

        sessions.closeSuspended();
        assertThat(sessions.boundSessions()).as("still bound after the failed close").isEqualTo(1);
        sessions.closeSuspended();

        then(failing).should(times(2)).close(any(CloseStatus.class));
        assertThat(sessions.boundSessions()).isZero();
        assertThat(meters.get(TenantWebSocketSessions.CLOSED_COUNTER).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a status lookup that throws skips that tenant only, and the sweep goes on")
    void throwingLookupSkipsTenantOnly() throws Exception {
        final WebSocketSession broken = open("b1", "broken");
        final WebSocketSession acme = open("a1", "acme");
        given(statusProvider.knownAccessOf("broken")).willThrow(new IllegalStateException("defect"));
        given(statusProvider.knownAccessOf("acme")).willReturn(TenantAccess.NONE);

        sessions.closeSuspended();

        then(acme).should().close(any(CloseStatus.class));
        then(broken).should(never()).close(any(CloseStatus.class));
        assertThat(sessions.boundSessions()).isEqualTo(1);
    }
}
