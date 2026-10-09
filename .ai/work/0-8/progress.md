# 0-8 Dead `publishEscalated` and a stale Javadoc in incident-service

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch refactor/0-8-remove-dead-publish-escalated; base 8bf3ce2942e4fb2f6510369d7881a0866ca894c7
NEXT: architect
- [architect] plan: delete dead IncidentEventPublisher.publishEscalated + its import; drop publishEscalatedWritesOutboxRow, everyPublishMethodCarriesTeamId to 4 calls/times(4); rewrite IncidentEscalationEventConsumer class Javadoc (EscalationScheduler sole producer, one collision sentence citing ADR-0017) and fix the same "its own published event" claim in Incident#recordEscalation Javadoc; ADR: none; modules: incident-service; rules: GEN-08, GEN-24, GEN-20, ARC-05, DOC-10, DOC-11
NEXT: implementer
- [implementer r1] removed publishEscalated and its test, fixed the two Javadocs; IncidentEventPublisherTest passes
- [implementer rules] GEN-08: handoff.md "Tests changed and why" | GEN-03: IncidentEventPublisherTest#everyPublishMethodCarriesTeamId | GEN-24: only the four planned files | GEN-20: consumer Javadoc "Changed (backlog #0-8)" | ARC-05: consumer Javadoc cites ADR-0017 | DOC-10: no remaining producer claim | DOC-11: Incident.java recordEscalation | DOC-40: one collision sentence
