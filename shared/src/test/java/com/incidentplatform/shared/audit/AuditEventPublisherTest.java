package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventPublisher")
class AuditEventPublisherTest {

    @Mock
    private AuditEventKafkaSender sender;

    private AuditEventPublisher publisher;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        publisher = new AuditEventPublisher(sender);
    }

    @Nested
    @DisplayName("publishSystem")
    class PublishSystem {

        @Test
        @DisplayName("delegates to sender with system message")
        void delegatesToSenderWithSystemMessage() throws JsonProcessingException {
            // given
            willDoNothing().given(sender).send(any(AuditEventMessage.class));

            // when
            publisher.publishIncident(
                    INCIDENT_ID, TENANT_ID,
                    AuditEventTypes.INCIDENT_CREATED, "incident-service",
                    "Incident created", Map.of());

            // then
            then(sender).should(times(1)).send(any(AuditEventMessage.class));
        }

        @Test
        @DisplayName("does not throw when sender fails with Kafka exception")
        void doesNotThrowWhenSenderFails() throws JsonProcessingException {
            // given
            willThrow(new RuntimeException("Kafka broker unavailable"))
                    .given(sender).send(any(AuditEventMessage.class));

            // when / then — publisher swallows the exception after all retries
            // (retry exhaustion is handled by @Retryable in AuditEventKafkaSender)
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    publisher.publishIncident(
                            INCIDENT_ID, TENANT_ID,
                            AuditEventTypes.INCIDENT_CREATED, "incident-service",
                            "Incident created", Map.of())
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw when sender fails with serialization exception")
        void doesNotThrowOnSerializationFailure() throws JsonProcessingException {
            // given
            willThrow(new JsonProcessingException("Cannot serialize") {})
                    .given(sender).send(any(AuditEventMessage.class));

            // when / then — JsonProcessingException is caught and logged, not propagated
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    publisher.publishIncident(
                            INCIDENT_ID, TENANT_ID,
                            AuditEventTypes.INCIDENT_CREATED, "incident-service",
                            "Incident created", Map.of())
            ).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("publishUser")
    class PublishUser {

        @Test
        @DisplayName("delegates to sender with user message")
        void delegatesToSenderWithUserMessage() throws JsonProcessingException {
            // given
            willDoNothing().given(sender).send(any(AuditEventMessage.class));

            // when
            publisher.publishAuth(
                    INCIDENT_ID, TENANT_ID,
                    AuditEventTypes.INCIDENT_ACKNOWLEDGED, "incident-service",
                    UUID.randomUUID().toString(),
                    "Incident acknowledged", Map.of());

            // then
            then(sender).should(times(1)).send(any(AuditEventMessage.class));
        }
    }
}