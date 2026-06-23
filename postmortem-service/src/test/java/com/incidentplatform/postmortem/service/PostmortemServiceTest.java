package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostmortemService")
class PostmortemServiceTest {

    @Mock
    private PostmortemRepository postmortemRepository;

    @Mock
    private GeminiClient geminiClient;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private PostmortemPersistenceService persistenceService;

    private PostmortemService postmortemService;

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
        final PostmortemPromptBuilder promptBuilder = new PostmortemPromptBuilder();

        postmortemService = new PostmortemService(
                postmortemRepository, geminiClient, auditEventPublisher,
                promptBuilder, persistenceService);
    }

    @Nested
    @DisplayName("generatePostmortem")
    class GeneratePostmortem {

        @Test
        @DisplayName("should create GENERATING record, then mark DRAFT when Gemini responds")
        void shouldCreateDraftWhenGeminiResponds() {
            // given
            given(postmortemRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
            given(persistenceService.createGeneratingRecord(
                    eq(INCIDENT_ID), eq(TENANT_ID), eq(TITLE), eq(Severity.CRITICAL),
                    eq(OPENED_AT), eq(RESOLVED_AT), eq(DURATION)))
                    .willReturn(POSTMORTEM_ID);
            given(geminiClient.generate(anyString()))
                    .willReturn("## Summary\nHigh CPU incident postmortem...");

            // when
            postmortemService.generatePostmortem(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then — GENERATING record created first
            then(persistenceService).should().createGeneratingRecord(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // and — DRAFT recorded with Gemini's content after the call succeeds
            final ArgumentCaptor<String> contentCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().markDraftAndPublish(
                    eq(POSTMORTEM_ID), eq(INCIDENT_ID), eq(TENANT_ID),
                    contentCaptor.capture(), anyString(), eq(DURATION));

            assertThat(contentCaptor.getValue())
                    .contains("High CPU incident postmortem");

            // and — FAILED path never invoked
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should mark FAILED when Gemini throws exception")
        void shouldMarkFailedWhenGeminiThrows() {
            // given
            given(postmortemRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
            given(persistenceService.createGeneratingRecord(
                    any(), any(), any(), any(), any(), any(), anyInt()))
                    .willReturn(POSTMORTEM_ID);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("API quota exceeded"));

            // when
            postmortemService.generatePostmortem(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then
            then(persistenceService).should().markFailedAndPublish(
                    POSTMORTEM_ID, INCIDENT_ID, TENANT_ID, "API quota exceeded");

            then(persistenceService).should(never())
                    .markDraftAndPublish(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should skip if postmortem already exists (idempotency)")
        void shouldSkipIfAlreadyExists() {
            // given
            given(postmortemRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(true);

            // when
            postmortemService.generatePostmortem(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then — no DB writes, no Gemini call
            then(persistenceService).should(never())
                    .createGeneratingRecord(any(), any(), any(), any(), any(), any(), anyInt());
            then(geminiClient).should(never()).generate(anyString());
        }

        @Test
        @DisplayName("should send prompt containing incident details to Gemini")
        void shouldSendPromptWithIncidentDetails() {
            // given
            given(postmortemRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
            given(persistenceService.createGeneratingRecord(
                    any(), any(), any(), any(), any(), any(), anyInt()))
                    .willReturn(POSTMORTEM_ID);
            given(geminiClient.generate(anyString())).willReturn("draft");

            // when
            postmortemService.generatePostmortem(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then
            final ArgumentCaptor<String> promptCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(geminiClient).should().generate(promptCaptor.capture());

            final String prompt = promptCaptor.getValue();
            assertThat(prompt).contains(TITLE);
            assertThat(prompt).contains(Severity.CRITICAL.toString());
            assertThat(prompt).contains(String.valueOf(DURATION));
        }

        @Test
        @DisplayName("should call createGeneratingRecord before calling Gemini — " +
                "GENERATING state must be persisted before the external call starts")
        void shouldPersistGeneratingRecordBeforeCallingGemini() {
            // given
            given(postmortemRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
            given(persistenceService.createGeneratingRecord(
                    any(), any(), any(), any(), any(), any(), anyInt()))
                    .willReturn(POSTMORTEM_ID);
            given(geminiClient.generate(anyString())).willReturn("draft");

            // when
            postmortemService.generatePostmortem(
                    INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                    OPENED_AT, RESOLVED_AT, DURATION);

            // then
            final InOrder order = inOrder(persistenceService, geminiClient);
            order.verify(persistenceService).createGeneratingRecord(
                    any(), any(), any(), any(), any(), any(), anyInt());
            order.verify(geminiClient).generate(anyString());
        }
    }

    @Nested
    @DisplayName("getPostmortems")
    class GetPostmortems {

        @Test
        @DisplayName("should return paginated postmortems for tenant")
        void shouldReturnPostmortemsForTenant() {
            // given
            final Postmortem postmortem = buildDraftPostmortem();
            final Pageable pageable = PageRequest.of(0, 20);
            given(postmortemRepository
                    .findByTenantIdOrderByCreatedAtDesc(TENANT_ID, pageable))
                    .willReturn(new PageImpl<>(List.of(postmortem), pageable, 1));

            // when
            final Page<PostmortemDto> result =
                    postmortemService.getPostmortems(TENANT_ID, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.getContent().get(0).status()).isEqualTo("DRAFT");
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("getByIncidentId")
    class GetByIncidentId {

        @Test
        @DisplayName("should return postmortem for incident")
        void shouldReturnPostmortemForIncident() {
            // given
            final Postmortem postmortem = buildDraftPostmortem();
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.of(postmortem));

            // when
            final PostmortemDto result =
                    postmortemService.getByIncidentId(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            // given
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    postmortemService.getByIncidentId(INCIDENT_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(INCIDENT_ID.toString());
        }
    }

    @Nested
    @DisplayName("updateContent")
    class UpdateContent {

        @Test
        @DisplayName("should update postmortem content")
        void shouldUpdateContent() {
            // given
            final Postmortem postmortem = buildDraftPostmortem();
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.of(postmortem));
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            final UpdatePostmortemRequest request =
                    new UpdatePostmortemRequest("Updated content by engineer");

            // when
            final PostmortemDto result =
                    postmortemService.updateContent(
                            INCIDENT_ID, TENANT_ID, request);

            // then
            assertThat(result.content()).isEqualTo("Updated content by engineer");
            then(postmortemRepository).should().save(postmortem);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when postmortem not found")
        void shouldThrowWhenNotFound() {
            // given
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    postmortemService.updateContent(
                            INCIDENT_ID, TENANT_ID,
                            new UpdatePostmortemRequest("content")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(INCIDENT_ID.toString());
        }
    }

    private Postmortem buildDraftPostmortem() {
        final Postmortem postmortem = Postmortem.createGenerating(
                INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                OPENED_AT, RESOLVED_AT, DURATION);
        postmortem.markDraft("## Summary\nTest postmortem content", "test prompt");
        return postmortem;
    }
}