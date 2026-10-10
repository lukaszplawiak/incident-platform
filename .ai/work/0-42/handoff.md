# Handoff: 0-42

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Status
BLOCKED before any code change (implementer r1).

- AC9 requires `.github/scripts/check-k8s-mail.sh`, its test `.github/scripts/test-k8s-mail.sh`, and a new
  step in `.github/workflows/ci.yml` (`validate-k8s-manifests`). Plan steps 5, 6 and 7 write under `.github/`.
- The implementer may not write `.github/`: `.claude/agents/implementer.md` ("Out of scope, never: … `.github/`
  … If the item cannot be done without one of them, stop and answer `blocked: true`"),
  `.ai/rules/implementation.md` ("Never … touch … `.github/` … if the item needs it, stop and say so"), and
  `.claude/settings.autopilot.json` denies `Edit(./.github/**)` and `Write(./.github/**)`;
  `.claude/hooks/guard-protected-bash.sh` refuses Bash writes there.
- The item is `Risk: high` and its Touches line names `ci (.github/scripts, .github/workflows/ci.yml)`, so the
  owner expected this; the autopilot's permissions do not allow it.

## Changed
- Nothing outside `.ai/work/0-42/` (this file and `progress.md`).

## How to verify
- `git diff 87aace9 -- . ':!.ai/work'` is empty.

## Proposed split (owner decides)
- A: the `.github/` part (check-k8s-mail.sh, test-k8s-mail.sh, the ci.yml step) written by the owner, or the
  whole item done interactively — plan steps 5–7 in `progress.md` are complete enough to follow.
- B: the k8s / notification-service comments / README / `.ai/context/infrastructure.md` part (plan steps 1–4
  and 8) can run through the autopilot once A exists on the branch (or on `main`), since AC1–AC8 have no
  test without the checker.

## Deliberately out of scope
- Everything in the plan: the item stops on AC9, which cannot be met without writing `.github/`.

## Noticed, not touched

## Follow-up needed

## Disputed
