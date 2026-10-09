# 0-7 incident-service coerces `escalationLevel` with `asInt(0)`

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-7-validate-escalation-level; base 8b10f427efe3b8dbc44d7c60063d81898b560421
NEXT: architect
- [architect] plan: IncidentEscalationEventConsumer validates escalationLevel (required JSON int, 1..MAX_ESCALATION_LEVEL=2, local constant) before recordEscalationLevel, throwing IllegalArgumentException into the existing poison-pill catch (deadLetterThenAcknowledge, KafkaFailures.reason), mirroring notification-service IncidentEventConsumer.extractEscalationLevel; tests in IncidentEscalationEventConsumerTest with a raw-payload record builder; ADR: none; modules: incident-service; rules: GEN-01, GEN-03, GEN-06, GEN-08, GEN-20, GEN-24, ARC-02, ARC-03, ARC-20, SEC-04, SEC-31, PERF-12, DOC-11
NEXT: implementer
- [implementer rules] GEN-01: EscalationLevelValidation tests | GEN-03: assertDeadLettered, InOrder in recordsValidEscalationLevel | GEN-06: malformed/out-of-range tests fail against asInt(0) | GEN-08: existing tests untouched | GEN-20: Javadoc in consumer | GEN-24: nothing outside item | ARC-02: existing catch reused | ARC-03: private constant | ARC-20: no direct ack on invalid | SEC-04: check runs after TenantContext.set | SEC-31: message names no value | PERF-12: IllegalArgumentException dead-lettered | DOC-11: Javadoc and comment describe validation
- [implementer r1] implemented escalationLevel validation and tests; targeted test class passes
NEXT: tests
