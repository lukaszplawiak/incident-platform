# Proofs: 0-8

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | incident-service compiles and `IncidentEventPublisherTest` runs; `git grep publishEscalated` finds no code | test |
| AC2 | `IncidentEventPublisherTest#everyPublishMethodCarriesTeamId` asserts times(4) and the teamId in each payload; `publishEscalatedWritesOutboxRow` absent | test |
| AC3 | diff of `IncidentEscalationEventConsumer` class Javadoc | review |

Test run: `./mvnw test -pl incident-service -am -Dtest=IncidentEventPublisherTest` - PASS (run by the implementer; the gate's log is `.ai/runs/0-8/`).
