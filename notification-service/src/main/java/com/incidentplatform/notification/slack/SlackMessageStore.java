package com.incidentplatform.notification.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Imp: ConcurrentHashMap (in-memory)
// production should be in Redis:
//   key: "slack:ts:{incidentId}:{channel}"
//   TTL: 7 days
// TODO: prod -> Redis , with ShedLock/Redis sprint
@Component
public class SlackMessageStore {

    private static final Logger log =
            LoggerFactory.getLogger(SlackMessageStore.class);

    private final Map<String, String> store = new ConcurrentHashMap<>();

    public void save(UUID incidentId, String channel, String ts) {
        if (ts == null || ts.isBlank()) {
            log.warn("Attempted to save null/blank ts: incidentId={}, " +
                    "channel={}", incidentId, channel);
            return;
        }
        store.put(buildKey(incidentId, channel), ts);
        log.debug("Slack ts saved: incidentId={}, channel={}, ts={}",
                incidentId, channel, ts);
    }

    public Optional<String> find(UUID incidentId, String channel) {
        return Optional.ofNullable(store.get(buildKey(incidentId, channel)));
    }

    public List<String> findAllChannelsForIncident(UUID incidentId) {
        final String prefix = incidentId + ":";
        return store.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .toList();
    }

    public void remove(UUID incidentId, String channel) {
        store.remove(buildKey(incidentId, channel));
    }

    public void removeAllForIncident(UUID incidentId) {
        final String prefix = incidentId + ":";
        store.keySet().removeIf(key -> key.startsWith(prefix));
        log.debug("All Slack ts entries removed: incidentId={}", incidentId);
    }

    private String buildKey(UUID incidentId, String channel) {
        return incidentId + ":" + channel;
    }
}