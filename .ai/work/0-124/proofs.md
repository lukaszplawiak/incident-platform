# Proofs: 0-124

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/0-124/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `<Class>#<method>` asserts <outcome> | test |
| AC2 | `.github/scripts/check-<x>.sh` fails without the change (its test case: `<name>`) | CI check |

Test run: `./mvnw verify -pl <modules>` — PASS, <n> tests, log `.ai/runs/0-124/tests-r<round>.log`.
