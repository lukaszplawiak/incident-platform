package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.router.NotificationRouter;
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

    @Mock
    private NotificationRouter router;

    @Mock
    private NotificationLogRepository logRepository;

    @Mock
    private NotificationChannel emailChannel;

    @Mock
    private NotificationChannel slackChannel;

    private NotificationService notificationService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "IncidentOpenedEvent";

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(router, logRepository);
    }

    @Nested
    @DisplayName("successful notification sending")
    class SuccessfulSending {

        @Test
        @DisplayName("should send notification through each channel from router")
        void shouldSendThroughEachChannel() {
            // given
            final NotificationRequest emailRequest = buildRequest("EMAIL");
            final NotificationRequest slackRequest = buildRequest("SLACK");

            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(EVENT_TYPE, INCIDENT_ID, TENANT_ID,
                    "CRITICAL", "High CPU"))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(
                                    emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(
                                    slackChannel, slackRequest)
                    ));
            given(logRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            notificationService.processEvent(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID, "CRITICAL", "High CPU");

            // then — call both
            then(emailChannel).should(times(1)).send(emailRequest);
            then(slackChannel).should(times(1)).send(slackRequest);
        }

        @Test
        @DisplayName("should save SENT log entry after successful send")
        void shouldSaveSentLogEntry() {
            // given
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(
                                    emailChannel, request)));
            given(logRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            notificationService.processEvent(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID, "HIGH", "Test");

            // then — log with SENT status
            final ArgumentCaptor<NotificationLog> logCaptor =
                    ArgumentCaptor.forClass(NotificationLog.class);
            then(logRepository).should().save(logCaptor.capture());

            final NotificationLog savedLog = logCaptor.getValue();
            assertThat(savedLog.getStatus()).isEqualTo("SENT");
            assertThat(savedLog.getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(savedLog.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(savedLog.getChannel()).isEqualTo("EMAIL");
        }

        @Test
        @DisplayName("should save two log entries for two channels")
        void shouldSaveTwoLogEntries() {
            // given
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(
                                    emailChannel, buildRequest("EMAIL")),
                            new NotificationRouter.ChannelRequest(
                                    slackChannel, buildRequest("SLACK"))
                    ));
            given(logRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            notificationService.processEvent(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID, "CRITICAL", "Test");

            // then — two records in log
            then(logRepository).should(times(2)).save(any(NotificationLog.class));
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should save FAILED log when channel throws NotificationException")
        void shouldSaveFailedLogOnException() {
            // given
            final NotificationRequest request = buildRequest("EMAIL");
            given(emailChannel.channelName()).willReturn("EMAIL");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(
                                    emailChannel, request)));

            willThrow(new NotificationException("EMAIL", "test@test.com",
                    "SMTP connection failed"))
                    .given(emailChannel).send(request);

            given(logRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            notificationService.processEvent(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID, "HIGH", "Test");

            // then
            final ArgumentCaptor<NotificationLog> logCaptor =
                    ArgumentCaptor.forClass(NotificationLog.class);
            then(logRepository).should().save(logCaptor.capture());

            assertThat(logCaptor.getValue().getStatus()).isEqualTo("FAILED");
            assertThat(logCaptor.getValue().getErrorMessage())
                    .contains("SMTP connection failed");
        }

        @Test
        @DisplayName("should continue with next channel after one fails")
        void shouldContinueAfterChannelFailure() {
            // given — email throw, slack should run
            final NotificationRequest emailRequest = buildRequest("EMAIL");
            final NotificationRequest slackRequest = buildRequest("SLACK");

            given(emailChannel.channelName()).willReturn("EMAIL");
            given(slackChannel.channelName()).willReturn("SLACK");
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of(
                            new NotificationRouter.ChannelRequest(
                                    emailChannel, emailRequest),
                            new NotificationRouter.ChannelRequest(
                                    slackChannel, slackRequest)
                    ));

            willThrow(new NotificationException("EMAIL", "test@test.com",
                    "SMTP failed"))
                    .given(emailChannel).send(emailRequest);

            given(logRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            notificationService.processEvent(
                    EVENT_TYPE, INCIDENT_ID, TENANT_ID, "CRITICAL", "Test");

            // then
            then(slackChannel).should(times(1)).send(slackRequest);
            then(logRepository).should(times(2)).save(any(NotificationLog.class));
        }
    }

    @Nested
    @DisplayName("no notifications")
    class NoNotifications {

        @Test
        @DisplayName("should do nothing when router returns empty list")
        void shouldDoNothingWhenNoChannels() {
            // given
            given(router.route(any(), any(), any(), any(), any()))
                    .willReturn(List.of());

            // when
            notificationService.processEvent(
                    "UnknownEvent", INCIDENT_ID, TENANT_ID, "LOW", "Test");

            // then
            then(emailChannel).should(never()).send(any());
            then(logRepository).should(never()).save(any());
        }
    }

    private NotificationRequest buildRequest(String channel) {
        return new NotificationRequest(
                INCIDENT_ID,
                TENANT_ID,
                EVENT_TYPE,
                "oncall@test-tenant.example.com",
                "[CRITICAL] High CPU Usage",
                "New CRITICAL incident detected",
                "CRITICAL",
                "High CPU Usage"
        );
    }
}