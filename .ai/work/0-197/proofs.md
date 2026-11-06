# Proofs: #0-197

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/<item>/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `ApiKeyDenyByDefaultTest#everyExemptEntryHasAReason` and `IntrospectionTokenDenyByDefaultTest#everyExemptEntryHasAReason` assert no blank reason and a valid `<METHOD> <pattern>` / bare-pattern key; `#exemptMatchingFollowsRule3` (both) asserts a `/**` key covers its base and below but not a sibling, a METHOD key that method only; `#exemptEntriesNameRealRoutes` (both) pins the key set and that each exact key is a route of the handler mapping. Neither file contains `PUBLIC_PATHS` or `startsWith` (diff, reviewer-checked) | test + review |
| AC2 | `ApiKeyDenyByDefaultTest#everyOtherRouteIsForbidden` walks every route outside `EXEMPT` and `KEY_ROUTES`; `#exemptEntriesNameRealRoutes` pins `KEY_ROUTES` to the six exact team patterns; `#everyExemptEntryHasAReason` asserts each has a reason; `#teamsReadScope`, `#jwtUnaffected` unchanged and pass | test |
| AC3 | `ApiKeyDenyByDefaultTest.OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping` asserts the failure names `POST /api/v1/test-only/any-method -> 200`; `IntrospectionTokenDenyByDefaultTest#theWalkSendsEveryMethodToAMethodlessMapping` asserts `routes()` gives all 8 `RequestMethod`s for a method-less mapping | test |
| AC4 | `ApiKeyDenyByDefaultTest.OpenRoute#theWalkFailsAndNamesTheOpenRoute` asserts the walk throws an `AssertionError` naming `GET /api/v1/test-only/open -> 200` (`@Nested`, `@NestedTestConfiguration(OVERRIDE)`, chain at `HIGHEST_PRECEDENCE`); `ApiKeyDenyByDefaultTest#everyOtherRouteIsForbidden` asserts the outer walk `doesNotContain` both test-only routes | test |
| AC5 | README.md "Infrastructure Hardening", #0-191 entry: the auth-service sentence | reviewer-checked |
| AC6 | `.ai/decisions/0048-every-route-is-walked-deny-by-default.md` Context ("until #0-197") and "Alternatives considered" ("as the auth-service tests did until #0-197") | reviewer-checked |
| AC7 | `./mvnw test -pl auth-service -am` | command |

Test run: `./mvnw test -pl auth-service -am` — PASS, 1010 tests, 0 failures, 0 errors, 0 skipped (implementer
round 1, seen in the console; the hook refused redirecting it to a file). The gate's own log is the autopilot's
`.ai/runs/0-197/` run of `scripts/factory/run-tests.sh`.
