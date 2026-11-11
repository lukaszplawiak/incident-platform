# Proofs: 0-189

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `OncallScheduleOptionalStatusIntegrationTest#activeStatusReturnsOnlyTheTenantsActiveRows` and `#nullStatusReturnsEveryStatusOfTheTenant` run the query with a status and with null on postgres:16-alpine | test |
| AC2 | same two tests plus `#otherTenantSeesOnlyItsOwnRows` (positive control) | test |
| AC3 | `#pageOfOneWithStatusHoldsLatestMatchingRowAndFullTotal`, `#pageOfOneWithoutStatusHoldsLatestRowOfTenantAndFullTotal` | test |

Test run: `./mvnw -q test -pl oncall-service -am -Dtest=OncallScheduleOptionalStatusIntegrationTest` — no failure; no log file saved.
