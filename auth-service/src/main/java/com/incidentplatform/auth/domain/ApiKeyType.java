package com.incidentplatform.auth.domain;

/**
 * Distinguishes between organization-level and user-bound API keys.
 *
 * @see ApiKey
 */
public enum ApiKeyType {

    /**
     * Organization-level key — not bound to any specific user.
     * Created by ADMIN only. Survives individual user departure.
     * Used for integrations: Grafana, CI/CD, Alertmanager webhooks.
     */
    TENANT,

    /**
     * User-bound key — inherits owner's permissions.
     * Created by any authenticated user for their own scripts/tools.
     * Automatically revoked when owner is archived or anonymized.
     */
    PERSONAL
}