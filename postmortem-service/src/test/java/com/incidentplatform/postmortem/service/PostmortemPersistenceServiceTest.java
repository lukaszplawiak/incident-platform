package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.domain.PostmortemStatus;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostmortemPersistenceService")
class PostmortemPersistenceServiceTest {

    @Mock
    private PostmortemRepository postmortemRepository;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private PostmortemPersistenceService persistenceService;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID POSTMORTEM_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";
    private static final String TITLE = "High CPU Usage on prod-server-1";
    private static final Instant OPENED_AT =
            Instant.now().minusSeconds(30 * 60L);
    private static final Instant RESOLVED_AT = Instant.now();
    private static final int DURATION = 30;

    @BeforeEach
    void setUp() {
        persistenceService = new PostmortemPersistenceService(
                postmortemRepository, auditEventPublisher);
    }

    @Nested
    @DisplayName("createGeneratingRecord")
    class CreateGeneratingRecord {

        @Test
        @DisplayName("should save a GENERATING postmortem and return its id")
        void shouldSaveGeneratingRecordAndReturnId() {
            // given
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            final UUID resultId = persistenceService.createGeneratingRecord(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then
            final ArgumentCaptor<Postmortem> captor =
                    ArgumentCaptor.forClass(Postmortem.class);
            then(postmortemRepository).should().save(captor.capture());

            final Postmortem saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(PostmortemStatus.GENERATING);
            assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(resultId).isEqualTo(saved.getId());
        }
    }

    @Nested
    @DisplayName("markDraftAndPublish")
    class MarkDraftAndPublish {

        @Test
        @DisplayName("should mark the postmortem DRAFT with Gemini's content")
        void shouldMarkDraftWithContent() {
            // given
            final Postmortem postmortem = Postmortem.createGenerating(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL.name(),
                    OPENED_AT, RESOLVED_AT, DURATION);
            given(postmortemRepository.getReferenceById(POSTMORTEM_ID))
                    .willReturn(postmortem);
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            persistenceService.markDraftAndPublish(
                    POSTMORTEM_ID, INCIDENT_ID, TENANT_ID,
                    "## Summary\nGenerated content", "the prompt", DURATION);

            // then
            assertThat(postmortem.getStatus()).isEqualTo(PostmortemStatus.DRAFT);
            assertThat(postmortem.getContent()).contains("Generated content");
            then(postmortemRepository).should().save(postmortem);
        }

        @Test
        @DisplayName("should publish POSTMORTEM_GENERATED audit event")
        void shouldPublishGeneratedAuditEvent() {
            // given
            final Postmortem postmortem = Postmortem.createGenerating(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL.name(),
                    OPENED_AT, RESOLVED_AT, DURATION);
            given(postmortemRepository.getReferenceById(POSTMORTEM_ID))
                    .willReturn(postmortem);
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            persistenceService.markDraftAndPublish(
                    POSTMORTEM_ID, INCIDENT_ID, TENANT_ID,
                    "content", "prompt", DURATION);

            // then
            then(auditEventPublisher).should().publishSystem(
                    eq(INCIDENT_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.POSTMORTEM_GENERATED), anyString(),
                    anyString(), any());
        }
    }

    @Nested
    @DisplayName("markFailedAndPublish")
    class MarkFailedAndPublish {

        @Test
        @DisplayName("should mark the postmortem FAILED with the error message")
        void shouldMarkFailedWithErrorMessage() {
            // given
            final Postmortem postmortem = Postmortem.createGenerating(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL.name(),
                    OPENED_AT, RESOLVED_AT, DURATION);
            given(postmortemRepository.getReferenceById(POSTMORTEM_ID))
                    .willReturn(postmortem);
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            persistenceService.markFailedAndPublish(
                    POSTMORTEM_ID, INCIDENT_ID, TENANT_ID, "API quota exceeded");

            // then
            assertThat(postmortem.getStatus()).isEqualTo(PostmortemStatus.FAILED);
            assertThat(postmortem.getErrorMessage()).isEqualTo("API quota exceeded");
            then(postmortemRepository).should().save(postmortem);
        }

        @Test
        @DisplayName("should publish POSTMORTEM_FAILED audit event")
        void shouldPublishFailedAuditEvent() {
            // given
            final Postmortem postmortem = Postmortem.createGenerating(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL.name(),
                    OPENED_AT, RESOLVED_AT, DURATION);
            given(postmortemRepository.getReferenceById(POSTMORTEM_ID))
                    .willReturn(postmortem);
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            persistenceService.markFailedAndPublish(
                    POSTMORTEM_ID, INCIDENT_ID, TENANT_ID, "timeout");

            // then
            then(auditEventPublisher).should().publishSystem(
                    eq(INCIDENT_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.POSTMORTEM_FAILED), anyString(),
                    anyString(), any());
        }
    }
}