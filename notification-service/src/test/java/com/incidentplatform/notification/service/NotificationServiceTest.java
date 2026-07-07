package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.domain.NotificationLogStatus;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    @Mock private NotificationRouter router;
    @Mock private NotificationLogRepository logRepository;
    @Mock private NotificationQueueRepository queueRepository;
    @Mock private NotificationChannel emailChannel;
    @Mock private NotificationChannel slackChannel;
    @Mock private AuditEventPublisher auditEventPublisher;

    private NotificationService notificationService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "IncidentOpenedEvent";

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                router, logRepository, queueRepository, auditEventPublisher);
    }

    // ── enqueue ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enqueue")
    class Enqueue {

        @Test
        @DisplayName("should write PENDING queue entry")
        void shouldWritePendingEntry() {
            given(queueRepository.existsByIncidentIdAndEventType(
                    INCIDENT_ID, EVENT_TYPE)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU");

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
            given(queueRepository.existsByIncidentIdAndEventType(
                    INCIDENT_ID, EVENT_TYPE)).willReturn(true);

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU");

            then(queueRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should not call router or channels — fast path only")
        void shouldNotCallRouterOrChannels() {
            given(queueRepository.existsByIncidentIdAndEventType(
                    INCIDENT_ID, EVENT_TYPE)).willReturn(false);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.enqueue(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU");

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
                    Severity.CRITICAL, "High CPU"))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(slackChannel, slackRequest)
                    ));
            given(logRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            then(emailChannel).should(times(1)).send(emailRequest);
            then(slackChannel).should(times(1)).send(slackRequest);
        }

        @Test
        @DisplayName("should save SENT log after successful send")
        void shouldSaveSentLog() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));
            given(logRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            final ArgumentCaptor<NotificationLog> logCaptor =
                    ArgumentCaptor.forClass(NotificationLog.class);
            then(logRepository).should().save(logCaptor.capture());
            assertThat(logCaptor.getValue().getStatus())
                    .isEqualTo(NotificationLogStatus.SENT);
        }

        @Test
        @DisplayName("should mark queue entry SENT after processing")
        void shouldMarkQueueEntrySent() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of());
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            assertThat(entry.getStatus()).isEqualTo(NotificationQueueStatus.SENT);
            assertThat(entry.getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("should save FAILED log and continue when channel throws")
        void shouldSaveFailedLogOnChannelException() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest emailRequest = buildRequest("EMAIL");
            final NotificationRequest slackRequest = buildRequest("SLACK");

            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(slackChannel, slackRequest)
                    ));

            willThrow(new NotificationException("EMAIL", "test@test.com",
                    "SMTP connection failed"))
                    .given(emailChannel).send(emailRequest);

            given(logRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            // Slack still called despite email failure
            then(slackChannel).should(times(1)).send(slackRequest);

            final ArgumentCaptor<NotificationLog> logCaptor =
                    ArgumentCaptor.forClass(NotificationLog.class);
            then(logRepository).should(times(2)).save(logCaptor.capture());

            assertThat(logCaptor.getAllValues())
                    .anySatisfy(l ->
                            assertThat(l.getStatus()).isEqualTo(NotificationLogStatus.FAILED))
                    .anySatisfy(l ->
                            assertThat(l.getStatus()).isEqualTo(NotificationLogStatus.SENT));

            // Queue entry still marked SENT — individual failures recorded in log
            assertThat(entry.getStatus()).isEqualTo(NotificationQueueStatus.SENT);
        }

        @Test
        @DisplayName("should skip channel if already logged — idempotency")
        void shouldSkipIfAlreadySent() {
            final NotificationQueueEntry entry = buildPendingEntry();
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(emailChannel, request)));
            given(logRepository.existsByIncidentIdAndEventTypeAndChannel(
                    INCIDENT_ID, EVENT_TYPE, "EMAIL")).willReturn(true);
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            then(emailChannel).should(never()).send(any());
            then(logRepository).should(never()).save(any(NotificationLog.class));
        }

        @Test
        @DisplayName("should mark SENT and not call channels when router returns empty")
        void shouldMarkSentWhenNoChannels() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of());
            given(queueRepository.save(any())).willAnswer(i -> i.getArgument(0));

            notificationService.processEntry(entry);

            then(emailChannel).shouldHaveNoInteractions();
            assertThat(entry.getStatus()).isEqualTo(NotificationQueueStatus.SENT);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private NotificationQueueEntry buildPendingEntry() {
        return NotificationQueueEntry.pending(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE,
                Severity.CRITICAL, "High CPU");
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