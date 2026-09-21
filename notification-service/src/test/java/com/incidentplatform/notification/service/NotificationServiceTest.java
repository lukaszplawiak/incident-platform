package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.NotificationQueueStatus;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.router.NotificationRouter;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Backlog #42 note: processEntry() no longer writes to logRepository/
 * queueRepository directly — it delegates through
 * NotificationPersistenceService (a new mock here). ProcessEntry's tests
 * below verify persistenceService interactions instead of repository
 * saves directly. Enqueue's tests are unchanged — enqueue() still writes
 * to queueRepository directly (a single fast DB operation with no
 * external I/O, deliberately left as-is — see NotificationService's own
 * Javadoc for why).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    @Mock private NotificationRouter router;
    @Mock private NotificationLogRepository logRepository;
    @Mock private NotificationQueueRepository queueRepository;
    @Mock private NotificationPersistenceService persistenceService;
    @Mock private NotificationChannel emailChannel;
    @Mock private NotificationChannel slackChannel;
    @Mock private AuditEventPublisher auditEventPublisher;

    private NotificationService notificationService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "IncidentOpenedEvent";
    private static final String ESCALATED_EVENT_TYPE = "IncidentEscalatedEvent";

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                router, logRepository, queueRepository, persistenceService,
                auditEventPublisher);
    }

    // ── enqueue ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enqueue")
    class Enqueue {

        @Test
        @DisplayName("should write PENDING queue entry")
        void shouldWritePendingEntry() {
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, EVENT_TYPE, 0)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 0, null);

            final ArgumentCaptor<NotificationQueueEntry> captor =
                    ArgumentCaptor.forClass(NotificationQueueEntry.class);
            then(queueRepository).should().save(captor.capture());

            final NotificationQueueEntry saved = captor.getValue();
            assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(saved.getSeverity()).isEqualTo(Severity.CRITICAL);
            assertThat(saved.getStatus()).isEqualTo(NotificationQueueStatus.PENDING);
        }

        @Test
        @DisplayName("should be idempotent — skip if already queued")
        void shouldSkipIfAlreadyQueued() {
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, EVENT_TYPE, 0)).willReturn(true);

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 0, null);

            then(queueRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should store the escalation level and target on the entry")
        void shouldStoreEscalationContext() {
            final UUID escalateTo = UUID.randomUUID();
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 2)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    ESCALATED_EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 2, escalateTo);

            final ArgumentCaptor<NotificationQueueEntry> captor =
                    ArgumentCaptor.forClass(NotificationQueueEntry.class);
            then(queueRepository).should().save(captor.capture());
            assertThat(captor.getValue().getEscalationLevel()).isEqualTo(2);
            assertThat(captor.getValue().getEscalateTo()).isEqualTo(escalateTo);
        }

        /**
         * Regression test: idempotency used to be keyed on
         * (incidentId, eventType) only, so once the level-1 escalation was
         * queued the level-2 (MANAGER) escalation for the same incident was
         * discarded as a duplicate and never sent.
         */
        @Test
        @DisplayName("should queue a level-2 escalation even though level 1 already exists")
        void shouldQueueLevel2EvenWhenLevel1Exists() {
            // Level 1 is already queued for this incident, but the idempotency
            // check must only consider the entry's own level (2).
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 2)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    ESCALATED_EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 2, null);

            then(queueRepository).should().save(any());
            then(queueRepository).should(never())
                    .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                            INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 1);
        }

        @Test
        @DisplayName("should still skip a redelivered escalation of the same level")
        void shouldSkipRedeliveredEscalationOfSameLevel() {
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 1)).willReturn(true);

            notificationService.enqueue(
                    ESCALATED_EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 1, null);

            then(queueRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should not call router or channels — fast path only")
        void shouldNotCallRouterOrChannels() {
            given(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                    INCIDENT_ID, TENANT_ID, EVENT_TYPE, 0)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", 0, null);

            then(router).shouldHaveNoInteractions();
            then(emailChannel).shouldHaveNoInteractions();
            then(slackChannel).shouldHaveNoInteractions();
        }
    }

    // ── processEntry ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("processEntry")
    class ProcessEntry {

        @Test
        @DisplayName("should send through each routed channel")
        void shouldSendThroughEachChannel() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest emailRequest = buildRequest("EMAIL");
            final NotificationRequest slackRequest = buildRequest("SLACK");

            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(slackChannel, slackRequest)
                    ));

            notificationService.processEntry(entry);

            then(emailChannel).should(times(1)).send(emailRequest);
            then(slackChannel).should(times(1)).send(slackRequest);
        }

        @Test
        @DisplayName("should record a SENT log entry after successful send")
        void shouldRecordSentLog() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));

            notificationService.processEntry(entry);

            then(persistenceService).should().recordChannelSent(
                    eq(INCIDENT_ID), eq(TENANT_ID), eq(EVENT_TYPE), eq(0), eq("EMAIL"),
                    eq(request.recipient()), eq(request.subject()), eq(request.message()));
        }

        @Test
        @DisplayName("should mark queue entry SENT after processing, via persistenceService")
        void shouldMarkQueueEntrySent() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of());

            notificationService.processEntry(entry);

            then(persistenceService).should().markSent(entry);
        }

        @Test
        @DisplayName("should record a FAILED log and continue when channel throws")
        void shouldRecordFailedLogOnChannelException() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest emailRequest = buildRequest("EMAIL");
            final NotificationRequest slackRequest = buildRequest("SLACK");

            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(slackChannel, slackRequest)
                    ));

            willThrow(new NotificationException("EMAIL", "test@test.com",
                    "SMTP connection failed"))
                    .given(emailChannel).send(emailRequest);

            notificationService.processEntry(entry);

            // Slack still called despite email failure
            then(slackChannel).should(times(1)).send(slackRequest);

            then(persistenceService).should().recordChannelFailed(
                    eq(INCIDENT_ID), eq(TENANT_ID), eq(EVENT_TYPE), eq(0), eq("EMAIL"),
                    eq(emailRequest.recipient()), eq("SMTP connection failed"));
            then(persistenceService).should().recordChannelSent(
                    eq(INCIDENT_ID), eq(TENANT_ID), eq(EVENT_TYPE), eq(0), eq("SLACK"),
                    eq(slackRequest.recipient()), any(), any());

            // Queue entry still marked SENT — individual failures recorded in log
            then(persistenceService).should().markSent(entry);
        }

        @Test
        @DisplayName("should skip channel if already logged — idempotency")
        void shouldSkipIfAlreadySent() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));
            given(logRepository
                    .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                            INCIDENT_ID, TENANT_ID, EVENT_TYPE, 0, "EMAIL")).willReturn(true);

            notificationService.processEntry(entry);

            then(emailChannel).should(never()).send(any());
            then(persistenceService).should(never())
                    .recordChannelSent(any(), any(), any(), anyInt(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should pass the entry's escalateTo to the router (backlog #0-1)")
        void shouldPassEscalateToToRouter() {
            final UUID escalateTo = UUID.randomUUID();
            final NotificationQueueEntry entry = buildEscalationEntry(1, escalateTo);
            given(router.route(ESCALATED_EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", escalateTo))
                    .willReturn(List.of());

            notificationService.processEntry(entry);

            then(router).should().route(ESCALATED_EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", escalateTo);
        }

        @Test
        @DisplayName("should send a level-2 escalation on a channel already used by level 1")
        void shouldSendLevel2OnChannelUsedByLevel1() {
            final NotificationQueueEntry entry = buildEscalationEntry(2);
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));
            // Level 1 already sent EMAIL for this incident + event type, but
            // the idempotency check only looks at this entry's own level (2).
            given(logRepository
                    .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                            INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 2, "EMAIL"))
                    .willReturn(false);

            notificationService.processEntry(entry);

            then(logRepository).should(never())
                    .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                            INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE, 1, "EMAIL");
            then(emailChannel).should().send(request);
            then(persistenceService).should().recordChannelSent(
                    eq(INCIDENT_ID), eq(TENANT_ID), eq(ESCALATED_EVENT_TYPE),
                    eq(2), eq("EMAIL"), eq(request.recipient()),
                    eq(request.subject()), eq(request.message()));
        }

        @Test
        @DisplayName("should mark SENT and not call channels when router returns empty")
        void shouldMarkSentWhenNoChannels() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of());

            notificationService.processEntry(entry);

            then(emailChannel).shouldHaveNoInteractions();
            then(persistenceService).should().markSent(entry);
        }

        /**
         * Regression test for backlog #42's core fix: verifies
         * processEntry() itself no longer touches logRepository/
         * queueRepository for writes at all — every write goes through
         * persistenceService, which is what makes it possible for
         * NotificationPersistenceService's own short transactions to be
         * genuinely independent of the (no longer existing) outer one.
         */
        @Test
        @DisplayName("never calls logRepository.save or queueRepository.save directly")
        void neverCallsRepositoriesDirectlyForWrites() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));

            notificationService.processEntry(entry);

            then(logRepository).should(never()).save(any());
            then(queueRepository).should(never()).save(any());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private NotificationQueueEntry buildPendingEntry() {
        return NotificationQueueEntry.pending(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE,
                Severity.CRITICAL, "High CPU");
    }

    private NotificationQueueEntry buildEscalationEntry(int escalationLevel) {
        return buildEscalationEntry(escalationLevel, null);
    }

    private NotificationQueueEntry buildEscalationEntry(int escalationLevel, UUID escalateTo) {
        return NotificationQueueEntry.pending(
                INCIDENT_ID, TENANT_ID, ESCALATED_EVENT_TYPE,
                Severity.CRITICAL, "High CPU", escalationLevel, escalateTo);
    }

    private NotificationRequest buildRequest(String channel) {
        return new NotificationRequest(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE,
                "oncall@test-tenant.example.com",
                "[CRITICAL] High CPU Usage",
                "New CRITICAL incident detected",
                Severity.CRITICAL, "High CPU Usage");
    }
}