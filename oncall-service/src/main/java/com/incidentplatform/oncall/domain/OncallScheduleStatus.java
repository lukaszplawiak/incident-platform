package com.incidentplatform.oncall.domain;

/**
 * Status of an {@link OncallSchedule} entry — added for the supersede
 * pattern (backlog #43), extended with {@link #CANCELLED} for soft-delete
 * (backlog #44).
 *
 * <p>Three states, deliberately without a permanent-purge/archival-cleanup
 * concept — a non-ACTIVE row is kept indefinitely for history, not
 * eventually purged, unlike e.g. {@code IncidentEventOutboxStatus}'s
 * PENDING/PUBLISHED pair (which tracks in-flight delivery attempts, a
 * fundamentally different kind of state). Matches
 * {@code EscalationTaskStatus}'s naming for the same underlying concept
 * ({@code CANCELLED} — a row that was removed before its window
 * completed, as opposed to one that ran its course or was replaced).
 */
public enum OncallScheduleStatus {
    ACTIVE,
    SUPERSEDED,

    /**
     * Removed via {@code DELETE /schedules/{id}} — kept for history
     * rather than physically deleted. Only reachable for a schedule
     * whose window has not yet fully elapsed
     * ({@link OncallSchedule#hasFullyElapsed}) — matches how PagerDuty
     * handles the same operation ("you can only delete present or
     * future overrides"). See {@code OncallScheduleService.cancel}'s
     * Javadoc for the exact rule and rejection behavior.
     */
    CANCELLED
}