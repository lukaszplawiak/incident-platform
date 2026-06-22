package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostmortemRetryScheduler")
class PostmortemRetrySchedulerTest {

    @Mock
    private PostmortemRepository postmortemRepository;

    @Mock
    private GeminiClient geminiClient;

    @Mock
    private PostmortemPersistenceService persistenceService;

    private PostmortemRetryScheduler scheduler;

    private static final int MAX_RETRY_ATTEMPTS = 3;

    @BeforeEach
    void setUp() {
        final PostmortemPromptBuilder promptBuilder = new PostmortemPromptBuilder();

        scheduler = new PostmortemRetryScheduler(
                postmortemRepository,
                geminiClient,
                promptBuilder,
                persistenceService,
                MAX_RETRY_ATTEMPTS
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("retryFailedPostmortems")
    class RetryFailedPostmortems {

        @Test
        @DisplayName("should do nothing when no FAILED postmortems exist")
        void shouldDoNothingWhenNoFailedPostmortems() {
            // given
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of());

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(geminiClient).should(never()).generate(anyString());
            then(persistenceService).should(never())
                    .incrementRetryCount(any());
        }

        @Test
        @DisplayName("should increment retry count before calling Gemini")
        void shouldIncrementRetryCountBeforeCallingGemini() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString())).willReturn("draft");

            // when
            scheduler.retryFailedPostmortems();

            // then
            final InOrder order = inOrder(persistenceService, geminiClient);
            order.verify(persistenceService).incrementRetryCount(postmortem.getId());
            order.verify(geminiClient).generate(anyString());
        }

        @Test
        @DisplayName("should mark DRAFT via persistenceService when Gemini succeeds")
        void shouldMarkDraftWhenGeminiSucceeds() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString()))
                    .willReturn("## Summary\nRetried postmortem content");

            // when
            scheduler.retryFailedPostmortems();

            // then
            final ArgumentCaptor<String> contentCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().markDraftAndPublish(
                    eq(postmortem.getId()), eq(postmortem.getIncidentId()),
                    eq(postmortem.getTenantId()), contentCaptor.capture(),
                    anyString(), eq(postmortem.getDurationMinutes()));

            assertThat(contentCaptor.getValue())
                    .contains("Retried postmortem content");
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
            then(persistenceService).should(never())
                    .markPermanentlyFailedAndPublish(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should mark FAILED (not PERMANENTLY_FAILED) when retries remain")
        void shouldMarkFailedWhenRetriesRemain() {
            // given — retryCount returned by incrementRetryCount is below max
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1); // 1 < MAX_RETRY_ATTEMPTS (3)
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"));

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(persistenceService).should().markFailedAndPublish(
                    postmortem.getId(), postmortem.getIncidentId(),
                    postmortem.getTenantId(), "Timeout");
            then(persistenceService).should(never())
                    .markPermanentlyFailedAndPublish(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should mark PERMANENTLY_FAILED when retry count reaches maxRetryAttempts")
        void shouldMarkPermanentlyFailedAfterMaxRetries() {
            // given — retryCount returned by incrementRetryCount equals max
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(MAX_RETRY_ATTEMPTS);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("API down"));

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(persistenceService).should().markPermanentlyFailedAndPublish(
                    postmortem.getId(), postmortem.getIncidentId(),
                    postmortem.getTenantId(), "API down", MAX_RETRY_ATTEMPTS);
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should continue retrying other postmortems if one fails")
        void shouldContinueAfterOneFailure() {
            // given
            final Postmortem postmortem1 = buildFailedPostmortem();
            final Postmortem postmortem2 = buildFailedPostmortem();

            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem1, postmortem2));
            given(persistenceService.incrementRetryCount(any())).willReturn(1);

            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"))
                    .willReturn("## Summary\nSuccessful retry");

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(geminiClient).should(times(2)).generate(anyString());
            then(persistenceService).should().markFailedAndPublish(
                    eq(postmortem1.getId()), any(), any(), any());
            then(persistenceService).should().markDraftAndPublish(
                    eq(postmortem2.getId()), any(), any(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("should send prompt containing incident details")
        void shouldSendPromptWithIncidentDetails() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString())).willReturn("draft");

            // when
            scheduler.retryFailedPostmortems();

            // then
            final ArgumentCaptor<String> promptCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(geminiClient).should().generate(promptCaptor.capture());

            final String prompt = promptCaptor.getValue();
            assertThat(prompt).contains("High CPU Usage");
            assertThat(prompt).contains("CRITICAL");
        }
    }

    @Nested
    @DisplayName("TenantContext handling")
    class TenantContextHandling {

        @Test
        @DisplayName("should clear TenantContext after processing all candidates")
        void shouldClearTenantContextAfterProcessing() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString())).willReturn("draft");

            // when
            scheduler.retryFailedPostmortems();

            // then — no tenant context leaks out of the scheduler run
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when Gemini call throws")
        void shouldClearTenantContextOnFailure() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("down"));

            // when
            scheduler.retryFailedPostmortems();

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not leak tenantId between consecutive candidates " +
                "belonging to different tenants")
        void shouldNotLeakTenantIdBetweenCandidates() {
            // given — two postmortems from two different tenants in the same batch
            final Postmortem postmortemTenantA = buildFailedPostmortemForTenant("tenant-a");
            final Postmortem postmortemTenantB = buildFailedPostmortemForTenant("tenant-b");

            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortemTenantA, postmortemTenantB));
            given(persistenceService.incrementRetryCount(any())).willReturn(1);

            // Capture TenantContext at the moment each Gemini call happens —
            // this is the most direct way to verify the context was correctly
            // scoped to each record during its own processing window.
            final List<String> observedTenants = new ArrayList<>();
            given(geminiClient.generate(anyString())).willAnswer(invocation -> {
                observedTenants.add(TenantContext.getOrNull());
                return "draft";
            });

            // when
            scheduler.retryFailedPostmortems();

            // then
            assertThat(observedTenants).containsExactly("tenant-a", "tenant-b");
        }
    }

    private Postmortem buildFailedPostmortem() {
        return buildFailedPostmortemForTenant("test-tenant");
    }

    private Postmortem buildFailedPostmortemForTenant(String tenantId) {
        final Instant openedAt = Instant.now().minusSeconds(30 * 60L);
        final Instant resolvedAt = Instant.now();
        final Postmortem postmortem = Postmortem.createGenerating(
                UUID.randomUUID(),
                tenantId,
                "High CPU Usage on prod-server-1",
                Severity.CRITICAL,
                openedAt,
                resolvedAt,
                30
        );
        postmortem.markFailed("Gemini API quota exceeded");
        return postmortem;
    }
}