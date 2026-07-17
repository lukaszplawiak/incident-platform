package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Named connection between an external monitoring system and a Team.
 *
 * <h2>Routing model</h2>
 * Each Integration represents one alert source configured to send alerts
 * to this platform. The routing chain is:
 * <pre>
 * ApiKey (credentials) → Integration → Team → OncallSchedule → User notification
 * </pre>
 *
 * <h2>One Integration = One ApiKey</h2>
 * Creating an Integration automatically creates a TENANT {@link ApiKey}
 * with {@code alerts:ingest} scope. The key is passed to the external system
 * (Alertmanager, Grafana, etc.) as its authentication credential.
 * Revoking the Integration revokes its ApiKey (CASCADE).
 *
 * <h2>Source</h2>
 * The {@link #source} field determines which {@code AlertNormalizer} processes
 * incoming payloads: "prometheus", "wazuh", or "generic".
 */
@Entity
@Table(name = "integrations")
public class Integration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Alert source — matches {@code AlertNormalizer.getSourceName()}.
     * Determines normalization strategy for incoming payloads.
     */
    @Column(name = "source", nullable = false, updatable = false, length = 50)
    private String source;

    /**
     * Team responsible for alerts from this integration.
     * When null, escalation logs a warning and skips on-call routing
     * (fail-loudly — misconfiguration must be visible, not silently ignored).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /**
     * The API key used by the external system to authenticate.
     * Created automatically when the Integration is created.
     * Revoked automatically when the Integration is revoked (CASCADE).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id")
    private ApiKey apiKey;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null = active. Non-null = revoked (soft delete). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Integration() {}

    public static Integration create(String tenantId, String name,
                                     String source, Team team,
                                     ApiKey apiKey, String description) {
        final Integration integration = new Integration();
        integration.tenantId    = tenantId;
        integration.name        = name;
        integration.source      = source;
        integration.team        = team;
        integration.apiKey      = apiKey;
        integration.description = description;
        integration.createdAt   = Instant.now();
        return integration;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
        if (this.apiKey != null) {
            this.apiKey.revoke();
        }
    }

    public boolean isActive()  { return revokedAt == null; }
    public boolean isRevoked() { return revokedAt != null; }

    public UUID getId()            { return id; }
    public String getTenantId()    { return tenantId; }
    public String getName()        { return name; }
    public String getSource()      { return source; }
    public Team getTeam()          { return team; }
    public ApiKey getApiKey()      { return apiKey; }
    public String getDescription() { return description; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getRevokedAt()  { return revokedAt; }
}