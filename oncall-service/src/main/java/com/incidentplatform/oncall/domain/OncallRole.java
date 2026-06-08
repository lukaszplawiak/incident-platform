package com.incidentplatform.oncall.domain;

/**
 * On-call schedule role — defines the escalation tier of a schedule entry.
 *
 * <p>Used as {@code @Enumerated(EnumType.STRING)} in {@link OncallSchedule}
 * so the database stores the same string values as before ({@code "PRIMARY"},
 * {@code "SECONDARY"}, {@code "MANAGER"}) — no Flyway migration required.
 *
 * <p>Escalation order: PRIMARY → SECONDARY → MANAGER
 */
public enum OncallRole {

    /** First responder — notified immediately when an incident opens. */
    PRIMARY,

    /** Second responder — notified at escalation level 1. */
    SECONDARY,

    /** Management escalation — notified at escalation level 2. */
    MANAGER
}