package com.incidentplatform.notification.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads a tenant's Slack workspace connection from auth-service (backlog
 * #0-21/#0-30) — the credential {@link
 * com.incidentplatform.notification.channel.SlackNotificationChannel} needs
 * to call the Slack API on that tenant's behalf.
 */
public interface SlackWorkspaceClient {

    /**
     * Returns the tenant's active Slack workspace connection, or empty if
     * none is configured — a normal, expected outcome (most tenants may
     * never install one).
     *
     * @throws SlackWorkspaceLookupUnavailableException if auth-service could
     *         not answer. Deliberately not folded into the empty result: "no
     *         workspace" and "we don't know" call for different handling and
     *         different logs at the call site — see that exception's Javadoc
     *         for why callers catch it rather than letting it reach the
     *         scheduler the way {@link OncallLookupUnavailableException} does.
     */
    Optional<SlackWorkspaceInfo> getWorkspace(String tenantId);

    record SlackWorkspaceInfo(
            String botToken,
            String defaultChannel,
            boolean broadcastEnabled,
            UUID teamId
    ) {}
}
