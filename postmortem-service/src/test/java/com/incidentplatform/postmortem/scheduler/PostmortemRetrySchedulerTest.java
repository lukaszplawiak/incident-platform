package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostmortemRetryScheduler")
class PostmortemRetrySchedulerTest {

    @Mock
    private PostmortemRepository postmortemRepository;

    @Mock
    private GeminiClient geminiClient;

    private PostmortemRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PostmortemRetryScheduler(
                postmortemRepository, geminiClient);
    }

    @Nested
    @DisplayName("retryFailedPostmortems")
    class RetryFailedPostmortems {

        @Test
        @DisplayName("should do nothing when no FAILED postmortems exist")
        void shouldDoNothingWhenNoFailedPostmortems() {
            // given
            given(postmortemRepository.findByStatus("FAILED"))
                    .willReturn(List.of());

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(geminiClient).should(never()).generate(anyString());
            then(postmortemRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should mark postmortem as DRAFT when Gemini succeeds")
        void shouldMarkDraftWhenGeminiSucceeds() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findByStatus("FAILED"))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString()))
                    .willReturn("## Summary\nRetried postmortem content");
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            scheduler.retryFailedPostmortems();

            // then
            assertThat(postmortem.getStatus()).isEqualTo("DRAFT");
            assertThat(postmortem.getContent())
                    .contains("Retried postmortem content");
            then(postmortemRepository).should().save(postmortem);
        }

        @Test
        @DisplayName("should keep FAILED status when Gemini still fails")
        void shouldKeepFailedStatusWhenGeminiStillFails() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findByStatus("FAILED"))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Still unavailable"));
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            scheduler.retryFailedPostmortems();

            // then
            assertThat(postmortem.getStatus()).isEqualTo("FAILED");
            assertThat(postmortem.getErrorMessage())
                    .contains("Still unavailable");
        }

        @Test
        @DisplayName("should continue retrying other postmortems if one fails")
        void shouldContinueAfterOneFailure() {
            // given
            final Postmortem postmortem1 = buildFailedPostmortem();
            final Postmortem postmortem2 = buildFailedPostmortem();

            given(postmortemRepository.findByStatus("FAILED"))
                    .willReturn(List.of(postmortem1, postmortem2));
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            given(geminiClient.generate(anyString()))
                    .willThrow(new GeminiException("Timeout"))
                    .willReturn("## Summary\nSuccessful retry");

            // when
            scheduler.retryFailedPostmortems();

            // then
            then(geminiClient).should(times(2)).generate(anyString());

            // then
            assertThat(postmortem2.getStatus()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("should send prompt containing incident details")
        void shouldSendPromptWithIncidentDetails() {
            // given
            final Postmortem postmortem = buildFailedPostmortem();
            given(postmortemRepository.findByStatus("FAILED"))
                    .willReturn(List.of(postmortem));
            given(geminiClient.generate(anyString())).willReturn("draft");
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

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

    private Postmortem buildFailedPostmortem() {
        final Instant openedAt = Instant.now().minusSeconds(30 * 60L);
        final Instant resolvedAt = Instant.now();
        final Postmortem postmortem = Postmortem.createGenerating(
                UUID.randomUUID(),
                "test-tenant",
                "High CPU Usage on prod-server-1",
                "CRITICAL",
                openedAt,
                resolvedAt,
                30
        );
        postmortem.markFailed("Gemini API quota exceeded");
        return postmortem;
    }
}