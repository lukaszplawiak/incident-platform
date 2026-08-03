package com.incidentplatform.notification.slack;

import com.incidentplatform.notification.domain.SlackMessageTs;
import com.incidentplatform.notification.repository.SlackMessageTsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores Slack message timestamps ("ts") needed to update a previously
 * sent Slack message via {@code chat.update} after an incident is
 * acknowledged.
 *
 * <h2>Fixed: previously an in-memory ConcurrentHashMap</h2>
 * The old implementation stored entries in a plain
 * {@code Map<String, String>}, local to each JVM instance. With
 * notification-service scaled to multiple replicas (Kubernetes HPA), a
 * Slack "Acknowledge" button click routed to a different pod than the one
 * that originally sent the notification would find nothing in that pod's
 * map — the update-after-ack step would silently fail for that channel.
 * Separately (and this was the more serious half of the bug — see
 * {@link com.incidentplatform.notification.channel.SlackNotificationChannel#send}),
 * {@code save} was never actually called anywhere in the codebase before
 * this fix, so the map was always empty regardless of replica count.
 *
 * <p>Now backed by {@link SlackMessageTsRepository} — Postgres, which
 * notification-service already depends on (unlike Redis, which this
 * service had no existing infrastructure for). Rows are deleted
 * explicitly after a successful ACK update ({@link #removeAllForIncident}),
 * or by {@code NotificationScheduler}'s cleanup job for incidents that
 * were never acknowledged via Slack.
 */
@Component
public class SlackMessageStore {

    private static final Logger log =
            LoggerFactory.getLogger(SlackMessageStore.class);

    private final SlackMessageTsRepository repository;

    public SlackMessageStore(SlackMessageTsRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void save(UUID incidentId, String channel, String tenantId, String ts) {
        if (ts == null || ts.isBlank()) {
            log.warn("Attempted to save null/blank ts: incidentId={}, " +
                    "channel={}", incidentId, channel);
            return;
        }
        repository.save(new SlackMessageTs(incidentId, channel, tenantId, ts));
        log.debug("Slack ts saved: incidentId={}, channel={}, ts={}",
                incidentId, channel, ts);
    }

    @Transactional(readOnly = true)
    public Optional<String> find(UUID incidentId, String channel) {
        return repository.findByIdIncidentIdAndIdChannel(incidentId, channel)
                .map(SlackMessageTs::getTs);
    }

    @Transactional(readOnly = true)
    public List<String> findAllChannelsForIncident(UUID incidentId) {
        return repository.findByIdIncidentId(incidentId).stream()
                .map(SlackMessageTs::getChannel)
                .toList();
    }

    @Transactional
    public void remove(UUID incidentId, String channel) {
        repository.findByIdIncidentIdAndIdChannel(incidentId, channel)
                .ifPresent(repository::delete);
    }

    @Transactional
    public void removeAllForIncident(UUID incidentId) {
        repository.deleteByIdIncidentId(incidentId);
        log.debug("All Slack ts entries removed: incidentId={}", incidentId);
    }

    /**
     * Deletes entries older than {@code threshold} — the cleanup path for
     * incidents that were never acknowledged via Slack (e.g. acknowledged
     * through the web UI instead), so {@link #removeAllForIncident} was
     * never called for them. Called by
     * {@code NotificationScheduler.cleanupOldSlackMessageTs}.
     *
     * @return number of rows deleted, for logging
     */
    @Transactional
    public int deleteOlderThan(Instant threshold) {
        return repository.deleteByCreatedAtBefore(threshold);
    }
}