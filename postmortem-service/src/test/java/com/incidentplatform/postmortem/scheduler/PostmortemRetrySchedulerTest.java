package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.config.PostmortemProperties;
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
    private static final int STUCK_THRESHOLD_MINUTES = 2;

    @BeforeEach
    void setUp() {
        final PostmortemPromptBuilder promptBuilder = new PostmortemPromptBuilder();
        final PostmortemProperties properties = new PostmortemProperties(
                MAX_RETRY_ATTEMPTS,
                java.time.Duration.ofMinutes(STUCK_THRESHOLD_MINUTES));
        scheduler = new PostmortemRetryScheduler(
                postmortemRepository,
                geminiClient,
                promptBuilder,
                persistenceService,
                properties);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── processGenerating ─────────────────────────────────────────────────

    @Nested
    @DisplayName("processGenerating")
    class ProcessGenerating {

        @Test
        @DisplayName("should do nothing when no GENERATING postmortems exist")
        void shouldDoNothingWhenNoGeneratingPostmortems() {
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of());

            scheduler.processGenerating();

            then(geminiClient).should(never()).generate(anyString());
            then(persistenceService).should(never()).markDraftAndPublish(
                    any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should call Gemini and mark DRAFT when generation succeeds")
        void shouldMarkDraftWhenGeminiSucceeds() {
            final Postmortem postmortem = buildGeneratingPostmortem();
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString()))
                    .willReturn("## Summary\nGenerated content");

            scheduler.processGenerating();

            final ArgumentCaptor<String> contentCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().markDraftAndPublish(
                    eq(postmortem.getId()), eq(postmortem.getIncidentId()),
                    eq(postmortem.getTenantId()), contentCaptor.capture(),
                    anyString(), eq(postmortem.getDurationMinutes()));
            assertThat(contentCaptor.getValue()).contains("Generated content");
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should mark FAILED (not PERMANENTLY_FAILED) when Gemini fails on first attempt")
        void shouldMarkFailedWhenGeminiFailsOnFirstAttempt() {
            // First attempt failure — retryCount is still 0,
            // retry scheduler will pick it up later.
            final Postmortem postmortem = buildGeneratingPostmortem();
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"));

            scheduler.processGenerating();

            then(persistenceService).should().markFailedAndPublish(
                    postmortem.getId(), postmortem.getIncidentId(),
                    postmortem.getTenantId(), "Timeout");
            then(persistenceService).should(never())
                    .markPermanentlyFailedAndPublish(any(), any(), any(), any(), anyInt());
            // retryCount not incremented on first attempt —
            // that is the retry scheduler's job
            then(persistenceService).should(never()).incrementRetryCount(any());
        }

        @Test
        @DisplayName("should NOT increment retryCount on first attempt")
        void shouldNotIncrementRetryCountOnFirstAttempt() {
            // retryCount is incremented only by retryOne(), not processOne().
            // This ensures GENERATING→FAILED counts as attempt 0,
            // so the retry scheduler gets the full maxRetryAttempts budget.
            final Postmortem postmortem = buildGeneratingPostmortem();
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString())).willReturn("draft");

            scheduler.processGenerating();

            then(persistenceService).should(never()).incrementRetryCount(any());
        }

        @Test
        @DisplayName("should continue processing other entries if one fails")
        void shouldContinueAfterOneFailure() {
            final Postmortem p1 = buildGeneratingPostmortem();
            final Postmortem p2 = buildGeneratingPostmortem();
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of(p1, p2));
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"))
                    .willReturn("## Success");

            scheduler.processGenerating();

            then(geminiClient).should(times(2)).generate(anyString());
            then(persistenceService).should()
                    .markFailedAndPublish(eq(p1.getId()), any(), any(), any());
            then(persistenceService).should()
                    .markDraftAndPublish(eq(p2.getId()), any(), any(),
                            anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("should clear TenantContext after processing")
        void shouldClearTenantContextAfterProcessing() {
            final Postmortem postmortem = buildGeneratingPostmortem();
            given(postmortemRepository.findStuckGenerating(any()))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString())).willReturn("draft");

            scheduler.processGenerating();

            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    // ── retryFailedPostmortems ────────────────────────────────────────────

    @Nested
    @DisplayName("retryFailedPostmortems")
    class RetryFailedPostmortems {

        @Test
        @DisplayName("should do nothing when no FAILED postmortems exist")
        void shouldDoNothingWhenNoFailedPostmortems() {
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of());

            scheduler.retryFailedPostmortems();

            then(geminiClient).should(never()).generate(anyString());
            then(persistenceService).should(never()).incrementRetryCount(any());
        }

        @Test
        @DisplayName("should increment retry count before calling Gemini")
        void shouldIncrementRetryCountBeforeCallingGemini() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString())).willReturn("draft");

            scheduler.retryFailedPostmortems();

            final InOrder order = inOrder(persistenceService, geminiClient);
            order.verify(persistenceService).incrementRetryCount(postmortem.getId());
            order.verify(geminiClient).generate(anyString());
        }

        @Test
        @DisplayName("should mark DRAFT via persistenceService when Gemini succeeds")
        void shouldMarkDraftWhenGeminiSucceeds() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString()))
                    .willReturn("## Summary\nRetried postmortem content");

            scheduler.retryFailedPostmortems();

            final ArgumentCaptor<String> contentCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().markDraftAndPublish(
                    eq(postmortem.getId()), eq(postmortem.getIncidentId()),
                    eq(postmortem.getTenantId()), contentCaptor.capture(),
                    anyString(), eq(postmortem.getDurationMinutes()));
            assertThat(contentCaptor.getValue()).contains("Retried postmortem content");
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
            then(persistenceService).should(never())
                    .markPermanentlyFailedAndPublish(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should mark FAILED (not PERMANENTLY_FAILED) when retries remain")
        void shouldMarkFailedWhenRetriesRemain() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1); // 1 < MAX_RETRY_ATTEMPTS (3)
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"));

            scheduler.retryFailedPostmortems();

            then(persistenceService).should().markFailedAndPublish(
                    postmortem.getId(), postmortem.getIncidentId(),
                    postmortem.getTenantId(), "Timeout");
            then(persistenceService).should(never())
                    .markPermanentlyFailedAndPublish(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should mark PERMANENTLY_FAILED when retry count reaches maxRetryAttempts")
        void shouldMarkPermanentlyFailedAfterMaxRetries() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(MAX_RETRY_ATTEMPTS);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("API down"));

            scheduler.retryFailedPostmortems();

            then(persistenceService).should().markPermanentlyFailedAndPublish(
                    postmortem.getId(), postmortem.getIncidentId(),
                    postmortem.getTenantId(), "API down", MAX_RETRY_ATTEMPTS);
            then(persistenceService).should(never())
                    .markFailedAndPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should continue retrying other postmortems if one fails")
        void shouldContinueAfterOneFailure() {
            final Postmortem p1 = buildFailedPostmortem();
            final Postmortem p2 = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(p1, p2));
            given(persistenceService.incrementRetryCount(any())).willReturn(1);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"))
                    .willReturn("## Summary\nSuccessful retry");

            scheduler.retryFailedPostmortems();

            then(geminiClient).should(times(2)).generate(anyString());
            then(persistenceService).should()
                    .markFailedAndPublish(eq(p1.getId()), any(), any(), any());
            then(persistenceService).should()
                    .markDraftAndPublish(eq(p2.getId()), any(), any(),
                            anyString(), anyString(), anyInt());
        }
    }

    // ── TenantContext ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("TenantContext handling")
    class TenantContextHandling {

        @Test
        @DisplayName("should clear TenantContext after retry run")
        void shouldClearTenantContextAfterProcessing() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString())).willReturn("draft");

            scheduler.retryFailedPostmortems();

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when Gemini call throws")
        void shouldClearTenantContextOnFailure() {
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(postmortem));
            given(persistenceService.incrementRetryCount(postmortem.getId()))
                    .willReturn(1);
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("down"));

            scheduler.retryFailedPostmortems();

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not leak tenantId between candidates from different tenants")
        void shouldNotLeakTenantIdBetweenCandidates() {
            final Postmortem pA = buildFailedPostmortemForTenant("tenant-a");
            final Postmortem pB = buildFailedPostmortemForTenant("tenant-b");
            given(postmortemRepository.findFailedWithRemainingRetries(anyInt()))
                    .willReturn(List.of(pA, pB));
            given(persistenceService.incrementRetryCount(any())).willReturn(1);

            final List<String> observedTenants = new ArrayList<>();
            given(geminiClient.generate(anyString())).willAnswer(invocation -> {
                observedTenants.add(TenantContext.getOrNull());
                return "draft";
            });

            scheduler.retryFailedPostmortems();

            assertThat(observedTenants).containsExactly("tenant-a", "tenant-b");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Postmortem buildGeneratingPostmortem() {
        final Instant openedAt = Instant.now().minusSeconds(30 * 60L);
        final Instant resolvedAt = Instant.now();
        return Postmortem.createGenerating(
                UUID.randomUUID(), "test-tenant",
                "High CPU Usage on prod-server-1",
                Severity.CRITICAL, openedAt, resolvedAt, 30);
    }

    private Postmortem buildFailedPostmortem() {
        return buildFailedPostmortemForTenant("test-tenant");
    }

    private Postmortem buildFailedPostmortemForTenant(String tenantId) {
        final Instant openedAt = Instant.now().minusSeconds(30 * 60L);
        final Instant resolvedAt = Instant.now();
        final Postmortem postmortem = Postmortem.createGenerating(
                UUID.randomUUID(), tenantId,
                "High CPU Usage on prod-server-1",
                Severity.CRITICAL, openedAt, resolvedAt, 30);
        postmortem.markFailed("Gemini API quota exceeded");
        return postmortem;
    }
}