# ADR-0023: Logs are one JSON object per line, in ECS (backlog #0-94, step 1)

- **Status:** Accepted
- **Backlog:** #0-94
- **Source:** written by the owner in `.ai/context/project.md` ("Known Invariants and Limitations")
  on 2026-10-09, while the ADR split was in preparation; carried over verbatim. The text below is the
  original record, not rewritten into the Context / Decision / Consequences form.

## Record

Every service logs through Spring Boot's structured logging in Elastic Common Schema, console and file,
set for all seven by `shared`'s `StructuredLoggingDefaults` (an `EnvironmentPostProcessor` registered in
`META-INF/spring.factories`, added after the config data as the lowest-precedence property source). ECS
over Logstash/GELF: a standard schema with room for trace ids, read by Loki, Elastic and Grafana as is.
ECS nests its own fields (`log.level`, `service.name`, `error.*`) and puts every MDC key at the top level,
so a new `MDC.put` is a new field in every line: `StructuredLoggingDefaultsTest` pins the set
(`tenantId`, `requestId`, `userId`, `kafkaMessageId`), and the interceptor's internal key
(`TenantKafkaRecordInterceptor.MDC_START_NANOS`) is in `logging.structured.json.exclude`. A default can be
overridden from anywhere, so `StructuredLoggingGuard` (an `ApplicationListener` on the prepared
environment, right after logging starts) refuses to start a service whose console or file format is not
`ecs`, that names a Logback file (`logging.config`, `logback.configurationFile`), or that has one on its
classpath (`logback*.xml`/`.groovy`, test ones included, in any jar: a dependency shipping one stops every
service, on purpose), or whose JSON object is reshaped (`logging.structured.json.*` beyond the platform's
own `exclude`), fail-closed like the JWT secret. Plain text needs `platform.logging.plain-text: true`
(plus an empty format) in a developer's gitignored `application-local.yml`; set anywhere else the switch
works too and is logged as a warning at every start, and CI's sixth structural rule fails on it in any
tracked file. Checking for the one switch, rather than every way of setting a format, is what makes the
CI check reliable: a format set any other way already stops the service in the smoke test. The encoder
makes a value safe to print, not safe to disclose (the rule on what not to log is in CLAUDE.md).
Collection is step 2 (next section); trace ids are step 3.
