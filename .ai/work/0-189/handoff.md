# Handoff: 0-189

## Changed
- oncall-service/src/test/java/com/incidentplatform/oncall/repository/OncallScheduleOptionalStatusIntegrationTest.java: new Testcontainers (postgres:16-alpine) test of findByTenantIdAndOptionalStatus, 5 tests, two tenants, test-only.

## How to verify
- `./mvnw -q test -pl oncall-service -am -Dtest=OncallScheduleOptionalStatusIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` — ran, no failure (needs Docker). The gate's `verify -pl oncall-service` was not run by me.

## Deliberately out of scope
- src/main, migrations, OncallScheduleOverlapIntegrationTest: unchanged.

## Noticed, not touched
- none

## Follow-up needed

## Disputed
