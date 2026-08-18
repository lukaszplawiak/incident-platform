package com.incidentplatform.oncall.validation;

import java.time.Instant;

/**
 * Implemented by any request record needing {@link StartBeforeEnd}
 * validation — introduced for backlog #43, when
 * {@code UpdateOncallScheduleRequest} needed the exact same
 * {@code startsAt < endsAt} check as {@code CreateOncallScheduleRequest},
 * but {@link StartBeforeEndValidator} was hard-typed to
 * {@code CreateOncallScheduleRequest} specifically (Jakarta Validation
 * resolves a validator by matching the annotated type against the
 * validator's declared generic parameter — an unrelated record, even one
 * with an identical shape, would not have matched, causing "no validator
 * found" at validation time rather than silently skipping the check).
 * This interface is the fix: both records implement it, and the
 * validator's generic type is this interface instead of one specific
 * record.
 *
 * <h2>Precedent</h2>
 * Same underlying pattern as {@code com.incidentplatform.shared.events.IncidentEvent}
 * — a minimal shared interface (there, just {@code incidentId()}) exposed
 * specifically so generic infrastructure code
 * ({@code IncidentEventKafkaSender.send}) can operate on any of several
 * otherwise-unrelated record types without needing to know which one it
 * has. Checked for this precedent only after implementing this fix
 * (should have checked first) — confirmed it validates the same design
 * choice: extract a minimal capability interface rather than duplicate
 * the shared logic across a second, near-identical validator.
 *
 * <p>Deliberately NOT sealed, unlike {@code IncidentEvent} — that
 * interface's closed set of implementors matters because consuming code
 * sometimes needs to reason about all of them exhaustively (e.g.
 * pattern-matching). Nothing here needs that: any future request record
 * that ever needs {@code startsAt < endsAt} validation should be free to
 * implement this interface without also having to extend a
 * {@code permits} clause it has no other reason to touch.
 */
public interface HasScheduleWindow {
    Instant startsAt();
    Instant endsAt();
}