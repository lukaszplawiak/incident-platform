package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One tenant's Slack workspace connection (backlog #0-21).
 *
 * <h2>Why this replaces the platform's one global Slack config</h2>
 * {@code notification.channels.slack} used to have one bot token/channel for
 * the whole platform. In a multi-tenant deployment each customer has its own
 * Slack workspace, so one bot token cannot DM their users — Slack only ever
 * worked for the one tenant whose engineers sat in the platform's own
 * workspace. This entity holds each tenant's own connection instead,
 * installed by a tenant admin pasting a bot token they created in their own
 * Slack App (no OAuth install flow — see backlog #0-21's recorded decision).
 * Because that App is the tenant's own, its interactive callbacks can't be
 * verified with the platform's signing secret, so Slack messages carry no
 * Acknowledge button until the OAuth install (backlog #0-35).
 *
 * <h2>Deliberately not shaped like {@link Integration}/{@link ApiKey}</h2>
 * {@link #teamId} is a plain {@code UUID}, not a JPA relation to {@link Team}
 * — unlike {@code Integration.team}. Investigating backlog #0-31 (auth-service
 * identity/config split, evaluated and deferred) found that {@code Integration}/
 * {@code ApiKey}'s eager {@code User}/{@code Team} object-graph traversal on
 * the API-key authentication hot path is exactly the coupling that would make
 * a future service split expensive. This entity has no relation to
 * {@link User} at all, and carries {@code @Version} from day one (backlog
 * #0-25 exists only because {@code NotificationQueueEntry} skipped that on a
 * mutable entity) — so it requires near-zero rework if that split ever
 * happens.
 *
 * <h2>Read cross-service</h2>
 * The bot token is decrypted and returned only by the internal,
 * {@code ROLE_SERVICE}-only endpoint notification-service calls (backlog
 * #0-30 — the first sanctioned case of another service calling into
 * auth-service). The admin-facing endpoints never return it after install.
 */
@Entity
@Table(name = "slack_workspaces")
public class SlackWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /**
     * Optional team scoping for a future team-scoped channel — not enforced
     * in routing yet (notification-service reads this per-tenant only, so
     * far). Plain column, see class Javadoc for why it is not a relation.
     */
    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "slack_team_id", nullable = false)
    private String slackTeamId;

    @Column(name = "bot_token_encrypted", nullable = false)
    private String botTokenEncrypted;

    @Column(name = "default_channel")
    private String defaultChannel;

    @Column(name = "broadcast_enabled", nullable = false)
    private boolean broadcastEnabled;

    @Column(name = "installed_at", nullable = false, updatable = false)
    private Instant installedAt;

    /** Null = active. Non-null = revoked (soft delete). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected SlackWorkspace() {}

    public static SlackWorkspace install(String tenantId, UUID teamId, String slackTeamId,
                                         String botTokenEncrypted, String defaultChannel,
                                         boolean broadcastEnabled) {
        final SlackWorkspace workspace = new SlackWorkspace();
        workspace.tenantId          = tenantId;
        workspace.teamId            = teamId;
        workspace.slackTeamId       = slackTeamId;
        workspace.botTokenEncrypted = botTokenEncrypted;
        workspace.defaultChannel    = defaultChannel;
        workspace.broadcastEnabled  = broadcastEnabled;
        workspace.installedAt       = Instant.now();
        workspace.createdAt         = Instant.now();
        return workspace;
    }

    /**
     * Test fixture factory — builds a fully-formed SlackWorkspace with an id
     * already assigned, simulating JPA id assignment on persist (mirrors
     * {@link Team#forTesting}). Production code never sets {@link #id}
     * directly — {@link GenerationType#UUID} assigns it.
     */
    public static SlackWorkspace forTesting(UUID id, String tenantId, String slackTeamId) {
        final SlackWorkspace workspace = install(
                tenantId, null, slackTeamId, "encrypted-blob", null, false);
        workspace.id = id;
        return workspace;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isActive()  { return revokedAt == null; }
    public boolean isRevoked() { return revokedAt != null; }

    public UUID getId()                  { return id; }
    public String getTenantId()          { return tenantId; }
    public UUID getTeamId()              { return teamId; }
    public String getSlackTeamId()       { return slackTeamId; }
    public String getBotTokenEncrypted() { return botTokenEncrypted; }
    public String getDefaultChannel()    { return defaultChannel; }
    public boolean isBroadcastEnabled()  { return broadcastEnabled; }
    public Instant getInstalledAt()      { return installedAt; }
    public Instant getRevokedAt()        { return revokedAt; }
    public Instant getCreatedAt()        { return createdAt; }
    public Long getVersion()             { return version; }
}
