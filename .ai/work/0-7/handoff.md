# Handoff: 0-7

## Changed
- incident-service IncidentEscalationEventConsumer: `escalationLevel` is validated (JSON int in 1..MAX_ESCALATION_LEVEL=2, private constant) instead of `asInt(0)`; invalid values throw IllegalArgumentException into the existing poison-pill catch (deadLetterThenAcknowledge). Javadoc "Fixed (backlog #0-7)" added.
- IncidentEscalationEventConsumerTest: new nested class EscalationLevelValidation and a raw-level record helper; existing tests unchanged.

## How to verify
- `./mvnw test -pl shared,incident-service -am -Dtest=IncidentEscalationEventConsumerTest -Dsurefire.failIfNoSpecifiedTests=false` - all pass.

## Deliberately out of scope
- Shared constant: #0-117. Level lowered by out-of-order event: #0-118. postmortem durationMinutes: #0-119.

## Noticed, not touched
- none

## Follow-up needed

## Disputed
