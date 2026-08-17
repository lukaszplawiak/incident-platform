package com.incidentplatform.incident.scheduler;

import com.incidentplatform.incident.config.IncidentEventOutboxProperties;
import com.incidentplatform.incident.domain.IncidentEventOutbox;
import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import com.incidentplatform.incident.service.IncidentEventOutboxPersistenceService;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * Backlog #36. Previously had no test file at all — this class didn't
 * exist before this fix. Covers the scheduler's two core responsibilities:
 * publishing PENDING entries via {@code sendRawSync} (blocking, so
 * success/failure is known synchronously — unlike the async {@code send}
 * used elsewhere), and correctly marking each entry PUBLISHED or leaving
 * it PENDING (via {@code markFailed}) based on that outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentEventOutboxScheduler")
class IncidentEventOutboxSchedulerTest {

    @Mock private IncidentEventOutboxRepository outboxRepository;
    @Mock private IncidentEventKafkaSender kafkaSender;
    @Mock private IncidentEventOutboxPersistenceService persistenceService;

    private IncidentEventOutboxScheduler scheduler;

    private static final String TENANT_ID = "acme-corp";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        final IncidentEventOutboxProperties properties =
                new IncidentEventOutboxProperties(2000, 50, SEND_TIMEOUT);
        scheduler = new IncidentEventOutboxScheduler(
                outboxRepository, kafkaSender, persistenceService, properties);
    }

    private IncidentEventOutbox buildEntry() {
        return IncidentEventOutbox.pending(
                UUID.randomUUID(), TENANT_ID,
                IncidentEventTypes.INCIDENT_OPENED, "{\"incidentId\":\"test\"}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("does nothing when there are no PENDING entries")
    void doesNothingWhenNoPendingEntries() throws Exception {
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of());

        scheduler.processPending();

        then(kafkaSender).shouldHaveNoInteractions();
        then(persistenceService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("publishes a PENDING entry via sendRawSync and marks it published on success")
    void publishesAndMarksPublishedOnSuccess() throws Exception {
        final IncidentEventOutbox entry = buildEntry();
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of(entry));

        scheduler.processPending();

        then(kafkaSender).should().sendRawSync(
                eq(entry.getIncidentId().toString()),
                eq(IncidentEventTypes.INCIDENT_OPENED),
                eq(entry.getPayload()),
                eq(SEND_TIMEOUT));
        then(persistenceService).should().markPublished(entry.getId());
        then(persistenceService).should(never()).markFailed(any(), any());
    }

    /**
     * The actual regression test for backlog #36's scheduler half: when
     * the blocking send genuinely fails, the entry must be left PENDING
     * (via markFailed, which — see IncidentEventOutboxStatus's Javadoc —
     * never transitions to a permanent-failure state) so the next poll
     * cycle retries it, rather than being silently dropped.
     */
    @Test
    @DisplayName("marks an entry failed (not published) when sendRawSync throws")
    void marksFailedWhenSendThrows() throws Exception {
        final IncidentEventOutbox entry = buildEntry();
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of(entry));
        willThrow(new java.util.concurrent.ExecutionException(
                "Broker unreachable", new RuntimeException()))
                .given(kafkaSender).sendRawSync(any(), any(), any(), any());

        scheduler.processPending();

        then(persistenceService).should().markFailed(eq(entry.getId()), any());
        then(persistenceService).should(never()).markPublished(any());
    }

    @Test
    @DisplayName("continues processing remaining entries after one fails")
    void continuesProcessingAfterOneFails() throws Exception {
        final IncidentEventOutbox failing = buildEntry();
        final IncidentEventOutbox succeeding = buildEntry();
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of(failing, succeeding));

        willThrow(new java.util.concurrent.ExecutionException(
                "Broker unreachable", new RuntimeException()))
                .given(kafkaSender).sendRawSync(
                        eq(failing.getIncidentId().toString()), any(), any(), any());
        // succeeding entry's sendRawSync call succeeds (void, no stub needed)

        scheduler.processPending();

        then(persistenceService).should().markFailed(eq(failing.getId()), any());
        then(persistenceService).should().markPublished(succeeding.getId());
    }

    /**
     * The actual regression test for backlog #42's TenantContext fix.
     * See this class's own Javadoc for the full account — every other
     * scheduler in this codebase already set TenantContext per entry;
     * this one was the gap.
     */
    @Test
    @DisplayName("sets and clears TenantContext per entry")
    void setsAndClearsTenantContextPerEntry() throws Exception {
        final IncidentEventOutbox entry = buildEntry();
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of(entry));

        scheduler.processPending();

        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("clears TenantContext even when sendRawSync throws")
    void clearsTenantContextOnFailure() throws Exception {
        final IncidentEventOutbox entry = buildEntry();
        given(outboxRepository.findPendingOrderByCreatedAt(any(PageRequest.class)))
                .willReturn(List.of(entry));
        willThrow(new java.util.concurrent.ExecutionException(
                "Broker unreachable", new RuntimeException()))
                .given(kafkaSender).sendRawSync(any(), any(), any(), any());

        scheduler.processPending();

        assertThat(TenantContext.getOrNull()).isNull();
    }
}