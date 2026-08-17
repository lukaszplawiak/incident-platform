package com.incidentplatform.notification.service;

import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.domain.NotificationLogStatus;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.NotificationQueueStatus;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

/**
 * Backlog #42. This class did not exist before — extracted from
 * {@code NotificationService.processEntry(...)}'s previously-inline
 * repository writes, specifically so each write could happen in its own
 * short transaction with no external I/O in progress while it's open.
 * See this class's own Javadoc for the full account.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPersistenceService")
class NotificationPersistenceServiceTest {

    @Mock private NotificationQueueRepository queueRepository;
    @Mock private NotificationLogRepository logRepository;

    private NotificationPersistenceService persistenceService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "IncidentOpenedEvent";

    @BeforeEach
    void setUp() {
        persistenceService = new NotificationPersistenceService(
                queueRepository, logRepository);
    }

    private NotificationQueueEntry buildEntry() {
        return NotificationQueueEntry.pending(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE, Severity.CRITICAL, "High CPU");
    }

    @Test
    @DisplayName("markSent marks the entry SENT and persists it")
    void marksSentAndPersists() {
        final NotificationQueueEntry entry = buildEntry();

        persistenceService.markSent(entry);

        assertThat(entry.getStatus()).isEqualTo(NotificationQueueStatus.SENT);
        then(queueRepository).should().save(entry);
    }

    @Test
    @DisplayName("markFailed marks the entry FAILED and persists it")
    void marksFailedAndPersists() {
        final NotificationQueueEntry entry = buildEntry();

        persistenceService.markFailed(entry, "oncall-service unreachable");

        assertThat(entry.getStatus()).isEqualTo(NotificationQueueStatus.FAILED);
        then(queueRepository).should().save(entry);
    }

    @Test
    @DisplayName("recordChannelSent persists a SENT NotificationLog with the given fields")
    void recordsChannelSent() {
        persistenceService.recordChannelSent(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE, "EMAIL",
                "oncall@example.com", "[CRITICAL] High CPU", "Body text");

        final ArgumentCaptor<NotificationLog> captor =
                ArgumentCaptor.forClass(NotificationLog.class);
        then(logRepository).should().save(captor.capture());

        final NotificationLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NotificationLogStatus.SENT);
        assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
        assertThat(saved.getChannel()).isEqualTo("EMAIL");
        assertThat(saved.getRecipient()).isEqualTo("oncall@example.com");
    }

    @Test
    @DisplayName("recordChannelFailed persists a FAILED NotificationLog with the error message")
    void recordsChannelFailed() {
        persistenceService.recordChannelFailed(
                INCIDENT_ID, TENANT_ID, EVENT_TYPE, "EMAIL",
                "oncall@example.com", "SMTP connection refused");

        final ArgumentCaptor<NotificationLog> captor =
                ArgumentCaptor.forClass(NotificationLog.class);
        then(logRepository).should().save(captor.capture());

        final NotificationLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NotificationLogStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo("SMTP connection refused");
    }
}