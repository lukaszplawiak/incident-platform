package com.incidentplatform.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores the Slack message timestamp ("ts") needed to update a previously
 * sent Slack message via {@code chat.update} — e.g. after the incident is
 * acknowledged, to change the message to show "Acknowledged by X".
 *
 * <p>Replaces {@code SlackMessageStore}'s previous in-memory
 * {@code ConcurrentHashMap} — see the V4 migration's comment for why this
 * needed to move to Postgres. Rows are deleted explicitly (all channels for
 * an incident) right after a successful Slack ACK update, or by
 * {@code NotificationScheduler}'s cleanup job for incidents that were never
 * acknowledged via Slack (e.g. acknowledged through the web UI instead).
 */
@Entity
@Table(name = "slack_message_ts")
public class SlackMessageTs {

    @EmbeddedId
    private Key id;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @NotBlank
    @Column(name = "ts", nullable = false)
    private String ts;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SlackMessageTs() {}

    public SlackMessageTs(UUID incidentId, String channel, String tenantId, String ts) {
        this.id = new Key(incidentId, channel);
        this.tenantId = tenantId;
        this.ts = ts;
        this.createdAt = Instant.now();
    }

    public UUID getIncidentId() { return id.incidentId(); }
    public String getChannel()  { return id.channel(); }
    public String getTenantId() { return tenantId; }
    public String getTs()       { return ts; }
    public Instant getCreatedAt() { return createdAt; }

    @Embeddable
    public static final class Key implements Serializable {

        @Column(name = "incident_id", nullable = false, updatable = false)
        private UUID incidentId;

        @Column(name = "channel", nullable = false, updatable = false)
        private String channel;

        protected Key() {}

        public Key(UUID incidentId, String channel) {
            this.incidentId = incidentId;
            this.channel = channel;
        }

        public UUID incidentId() { return incidentId; }
        public String channel()  { return channel; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(incidentId, key.incidentId)
                    && Objects.equals(channel, key.channel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(incidentId, channel);
        }
    }
}