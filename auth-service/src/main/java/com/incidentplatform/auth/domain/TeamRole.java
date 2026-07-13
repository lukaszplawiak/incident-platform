package com.incidentplatform.auth.domain;

/**
 * Role of a user within a specific {@link Team}.
 *
 * <h2>Two-level role model</h2>
 * This is a team-level role — separate from the tenant-level role in
 * {@link Role} ({@code ROLE_ADMIN}/{@code ROLE_RESPONDER}).
 *
 * <ul>
 *   <li>Tenant-level ({@link Role}): controls platform-wide permissions
 *       (who can create users, manage the system).</li>
 *   <li>Team-level ({@link TeamRole}): controls team-specific permissions
 *       (who can manage team membership and on-call schedules).</li>
 * </ul>
 *
 * <p>A user can be {@code ROLE_RESPONDER} at the tenant level (cannot manage
 * the platform) but {@code MANAGER} in their team (can manage that team's
 * on-call schedule). This mirrors the PagerDuty model.
 */
public enum TeamRole {

    /**
     * Can manage team membership, on-call schedules, and escalation policies
     * for this team. Corresponds to "Team Manager" in PagerDuty.
     */
    MANAGER,

    /**
     * Receives and responds to incidents routed to this team.
     * Cannot manage team configuration.
     * Corresponds to "Responder" in PagerDuty.
     */
    RESPONDER
}