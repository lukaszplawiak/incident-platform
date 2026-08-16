package com.incidentplatform.incident.service;

import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Short, independent transactions for updating {@code IncidentEventOutbox}
 * status — used by {@code IncidentEventOutboxScheduler}.
 *
 * <p>Same pattern already established by {@code AuthEmailPersistenceService}
 * (auth-service) and {@code PostmortemPersistenceService} (postmortem-service):
 * the scheduler's polling loop calls
 * {@code IncidentEventKafkaSender.sendRawSync(...)} — a real, blocking
 * network call to the Kafka broker — for each entry. That call must NOT
 * happen inside an open database transaction (holding a DB connection idle
 * for the duration of a Kafka round-trip, times however many entries are
 * in the batch, would be wasteful and could exhaust the connection pool
 * under load). Each status update is therefore its own short,
 * independent transaction, called only after the corresponding Kafka call
 * has already completed (successfully or not).
 */
@Service
public class IncidentEventOutboxPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventOutboxPersistenceService.class);

    private final IncidentEventOutboxRepository outboxRepository;

    public IncidentEventOutboxPersistenceService(
            IncidentEventOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void markPublished(UUID entryId) {
        outboxRepository.findById(entryId).ifPresentOrElse(
                entry -> entry.markPublished(),
                () -> log.warn("Outbox entry not found when marking published " +
                        "— may have been deleted concurrently: entryId={}", entryId)
        );
    }

    @Transactional
    public void markFailed(UUID entryId, String errorMessage) {
        outboxRepository.findById(entryId).ifPresentOrElse(
                entry -> entry.markFailed(errorMessage),
                () -> log.warn("Outbox entry not found when marking failed " +
                        "— may have been deleted concurrently: entryId={}", entryId)
        );
    }
}