package com.incidentplatform.oncall.domain;

/**
 * Status of an {@link OncallSchedule} entry — added for the supersede
 * pattern (backlog #43).
 *
 * <p>Only two states, deliberately without a permanent-failure or
 * archival-cleanup concept — a superseded row is kept indefinitely for
 * history, not eventually purged, unlike e.g. {@code IncidentEventOutboxStatus}'s
 * PENDING/PUBLISHED pair (which tracks in-flight delivery attempts, a
 * fundamentally different kind of state).
 */
public enum OncallScheduleStatus {
    ACTIVE,
    SUPERSEDED
}