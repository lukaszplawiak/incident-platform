package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventPublisher")
class AuditEventPublisherTest {

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

    @Mock private AuditEventKafkaSender sender;
    @Mock private AuditEventStore outbox;

    private AuditEventPublisher publisher(AuditEventStore outbox) {
        return new AuditEventPublisher(sender, outbox);
    }

    @Nested
    @DisplayName("with the service's outbox (backlog #0-84)")
    class WithOutbox {

        @Test
        @DisplayName("writes the event to the outbox, with its id, tenant, type and JSON; sends nothing")
        void writesToOutbox() throws Exception {
            given(sender.serialize(any())).willReturn("{json}");
            final ArgumentCaptor<AuditEventMessage> message = ArgumentCaptor.forClass(AuditEventMessage.class);

            publisher(outbox).publishIncident(INCIDENT_ID, TENANT_ID, AuditEventTypes.INCIDENT_CREATED,
                    "incident-service", "Incident created", Map.of());

            then(sender).should().serialize(message.capture());
            then(outbox).should().enqueue(message.getValue().eventId(), TENANT_ID,
                    AuditEventTypes.INCIDENT_CREATED, "{json}");
            then(sender).should(never()).sendForRelay(any(), any());
        }

        @Test
        @DisplayName("a failure to write is not swallowed: the caller's action fails with it")
        void writeFailurePropagates() throws Exception {
            given(sender.serialize(any())).willReturn("{json}");
            willThrow(new IllegalStateException("db down")).given(outbox)
                    .enqueue(any(), anyString(), anyString(), anyString());

            assertThatThrownBy(() -> publisher(outbox).publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .hasMessage("db down");
        }

        @Test
        @DisplayName("an event that cannot be serialized fails the action rather than vanish")
        void serializationFailurePropagates() throws Exception {
            given(sender.serialize(any())).willThrow(new JsonProcessingException("bad") { });

            assertThatThrownBy(() -> publisher(outbox).publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalStateException.class);
            then(outbox).shouldHaveNoInteractions();
        }

    }

    @Nested
    @DisplayName("events the audit trail cannot store are refused (backlog #0-84)")
    class Refused {

        @Test
        @DisplayName("no tenant, no resource, no source, or a field over its column fails the action")
        void unstorable() {
            final AuditEventPublisher publisher = publisher(outbox);
            assertThatThrownBy(() -> publisher.publishAuth(UUID.randomUUID(), null,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
            assertThatThrownBy(() -> publisher.publishAuth(null, TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resource id");
            assertThatThrownBy(() -> publisher.publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, " ", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sourceService");
            assertThatThrownBy(() -> publisher.publishAuth(UUID.randomUUID(), "t".repeat(256),
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");
            assertThatThrownBy(() -> publisher.publishAuth(UUID.randomUUID(), TENANT_ID,
                    "E".repeat(101), "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventType");
            assertThatThrownBy(() -> publisher.publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "a".repeat(256), "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
            then(outbox).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("metadata under a key that names a secret is refused; identifiers pass")
        void secretMetadataKey() {
            for (final String key : java.util.List.of("password", "newPassword", "client_secret", "mfaSecret",
                    "totpCode", "refreshToken", "accessToken", "rawKey", "apiKey", "privateKey", "credentials")) {
                assertThatThrownBy(() -> publisher(outbox).publishAuth(UUID.randomUUID(), TENANT_ID,
                        AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of(key, "x")))
                        .as(key).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
            }
            for (final String key : java.util.List.of("keyId", "keyIds", "apiKeyId", "tokenType", "integrationId",
                    "remainingCodes", "personalApiKeysRevoked")) {
                assertThat(AuditEventPublisher.looksSecret(key)).as(key).isFalse();
            }
            then(outbox).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a payload over 256 KiB is refused, so the relay never holds one Kafka refuses")
        void oversizedPayload() throws Exception {
            given(sender.serialize(any())).willReturn("x".repeat(AuditEventPublisher.MAX_PAYLOAD_BYTES + 1));

            assertThatThrownBy(() -> publisher(outbox).publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bytes");
            then(outbox).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a payload of exactly the limit is stored")
        void payloadAtLimit() throws Exception {
            given(sender.serialize(any())).willReturn("x".repeat(AuditEventPublisher.MAX_PAYLOAD_BYTES));

            publisher(outbox).publishAuth(UUID.randomUUID(), TENANT_ID,
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of());

            then(outbox).should().enqueue(any(), eq(TENANT_ID), eq(AuditEventTypes.USER_LOGIN), anyString());
        }
    }
}
