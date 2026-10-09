# Handoff: 0-8

## Changed
- incident-service `IncidentEventPublisher`: removed `publishEscalated` and the `IncidentEscalatedEvent` import.
- incident-service `IncidentEventPublisherTest`: removed `publishEscalatedWritesOutboxRow`; `everyPublishMethodCarriesTeamId` now calls the four remaining publish methods, `times(4)`, still asserting the teamId on each payload.
- incident-service `IncidentEscalationEventConsumer`: class Javadoc names `EscalationScheduler` as the only producer, one sentence on non-colliding levels (ADR-0017), "Changed (backlog #0-8)" note.
- incident-service `Incident#recordEscalation` Javadoc: now says it consumes escalation-service's event.

## How to verify
- `./mvnw test -pl incident-service -am -Dtest=IncidentEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false` - passed (the module compiles without `publishEscalated`).

## Tests changed and why
- `IncidentEventPublisherTest#publishEscalatedWritesOutboxRow`: deleted, AC2 requires it because the method under test is gone.
- `IncidentEventPublisherTest#everyPublishMethodCarriesTeamId`: `publishEscalated` call removed, `times(5)` to `times(4)` (AC2).

## Deliberately out of scope
- `V4__add_escalation_level_to_incidents.sql` comment: a migration on main is not edited.

## Noticed, not touched
- none

## Follow-up needed

## Disputed
