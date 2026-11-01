# Proofs: 0-192

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/0-192/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `OncallDenyByDefaultTest#everyRouteRefusesAnAnonymousCaller`, `#everyRouteRefusesAPurposeToken`, `#everyRouteRefusesAnUnknownApiKey`; `PostmortemDenyByDefaultTest#` the same three (EXEMPT `/error` only; nested `TestApplication` scanning the service root) | test |
| AC2 | `NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers` (unmapped path, three callers), `#slackActionsWithoutSignatureIs401FromTheRealVerifier` (401, `processAction` never called), `#slackActionsCorrectlySignedIs200` (real verifier, test secret) | test |
| AC3 | `OncallDenyByDefaultTest.OpenRoute#theWalkFailsAndNamesTheOpenRoute`, `PostmortemDenyByDefaultTest.OpenRoute#…`, `NotificationDenyByDefaultTest.OpenRoute#…` | test |
| AC4 | the three `OpenRoute` cases assert the message `GET /api/v1/test-only/open [anonymous] -> 200` | test |
| AC5 | `OncallDenyByDefaultTest#enumerationFindsTheKnownRoutes` (>= 9, by-user and schedules), `PostmortemDenyByDefaultTest#enumerationFindsTheKnownRoutes` (>= 4, `GET /api/v1/postmortems`, no springdoc route), `NotificationDenyByDefaultTest#exemptSetIsTheSlackRouteAndErrorOnly` | test |
| AC6 | the `EXEMPT` literal with a reason per entry in each of the three tests; no reference to `PUBLIC_PATHS` in code | reviewer |
| AC7 | `README.md` "Infrastructure Hardening", the "Every route refuses an unauthenticated caller" entry | reviewer |
| AC8 | `OncallDenyByDefaultTest#aValidUserTokenGetsPastAuthentication` (403), `PostmortemDenyByDefaultTest#aValidUserTokenGetsPastAuthentication`, `NotificationDenyByDefaultTest#aValidUserTokenGetsPastAuthentication` (not 401) | test |
| AC9 | `routes()`/`allRoutes()` use `EnumSet.allOf(RequestMethod.class)` for a method-less mapping; `isExempt` with `/**` matching copied; `NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers` sends every `RequestMethod` | test |

Test run: `./mvnw test -pl notification-service,oncall-service,postmortem-service -am` — PASS (BUILD SUCCESS), run by
the implementer in this session; the gate's own run is `.ai/runs/0-192/` (`scripts/factory/run-tests.sh`).
