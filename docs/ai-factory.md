# AI factory: runbook

How the autopilot works through `BACKLOG.md` unattended, what the owner does, and how to set it up.
The map of files and who may write them is in `.ai/README.md`; this document is the operating manual.

## In one picture

```
next-item.sh (code: ready follow-up → approved queue .ai/plan/queue.md, strictly in order → priority) → picker
  → architect (plan, predicted modules, the rules the implementer must have in front of it)
  → implementer (Opus; implementer-light on Sonnet for Complexity: low)
  → self-check: rule files the diff calls for, from which the plan listed no rule → implementer reads and checks them
  → path gate (scripts/factory/changed-paths.sh)                                human-owned path, applied migration, POM supply chain,
                                                                                 build config, deleted/disabled tests, ready flag,
                                                                                 CLAUDE.md outside its blocks → BLOCKED
  → test gate (scripts/factory/run-tests.sh: ./mvnw verify of HEAD, clean tree)  fixable → implementer (≤2) · env failure → BLOCKED
  → review panel, in parallel: general · architecture · security · performance · docs (+ migration, k8s when touched)
       invalid verdict → BLOCKED (fail closed) · NEEDS_HUMAN → BLOCKED · upheld dispute → BLOCKED
       blocking → implementer → test gate → the reviewers that blocked + security + general, on the delta
       (≤3 rounds) · same findings again → BLOCKED
  → acceptance-reviewer        REJECT → one fix → tests → one delta round → acceptance again · NEEDS_HUMAN → BLOCKED (risk-high: owner merges)
  → shipper (outside the loop) PR with the full review record · item moved to BACKLOG-DONE.md · auto-merge only outside shadow mode
  → CI + Factory guards + CODEOWNERS on the PR · Main guard: red main → autopilot-stop issue
```

Every arrow is code in `.claude/workflows/backlog-autopilot.js`; agents do the work, the script decides.
A BLOCKED item becomes a draft PR labelled `blocked`, which is also the lock that keeps the picker away
from it.

## Phases

| Phase | What | Done when |
|---|---|---|
| 0 | Prepare items: `/ready #0-N` per item (numbered acceptance criteria, risk, complexity, `ready`) | ~10 items `ready` on `main` |
| 1 | Use the panel by hand: `/review` (same agents and rules as the autopilot) | you trust its findings on your own changes |
| 1b | Plan the order: `/plan-backlog` proposes `.ai/plan/queue.md` (order, Touches, why); you edit and merge it | a queue you agree with is on `main` |
| 2 | **Shadow mode** (default): `/backlog-autopilot` opens PRs, never merges; you merge, and label each PR (`human:agree`, `human:fp-<dim>`, `human:missed-<dim>`; optionally `human:introduced-<stage>`) | ~10 PRs, and you agreed with the merge decision in ≥ 9 |
| 3 | Auto-merge, after #0-113 (bot account, branch protection) and #0-116 (code an agent runs must not be able to drop the devcontainer's firewall): in `.claude/settings.autopilot.json` remove `Bash(gh pr merge *)` from `deny` (keep the `--admin` deny) and add `Bash(gh pr merge * --squash --auto)` to `allow`; run with `{"shadow": false}` | — |
| 4 | Audits every 10 items (the preflight stops with "audit due"): `/audit` runs both targets — `reviewers` (one analyst per dimension) and `pipeline` (backlog #0-121: every case traced from the stage that introduced it to the one that detected it, with why each stage between let it through) — one report each | acceptance of recommendations stays in 40–80% |
| 5 | Later: cloud runs; a stage of the pipeline audit gets its own analyst only when the data shows it produces most of the cases. Never: two items at once (#0-114, decided against) | — |

## Setup (once)

1. **Devcontainer** (`.devcontainer/`). Docker Desktop: give the VM 16–20 GB, and restrict *Settings →
   Resources → File sharing* to the directory that holds your projects — the container can reach the
   host's Docker (Testcontainers needs it), and whoever controls Docker can mount any shared directory.
   Without an IDE: `npm i -g @devcontainers/cli`, then `devcontainer up --workspace-folder .` and
   `devcontainer exec --workspace-folder . bash` (`.devcontainer/devcontainer-lock.json` pins the
   docker-outside-of-docker feature by digest). Always enter with `devcontainer exec`: the container's
   main process runs as root, so a plain `docker exec` without `-u dev` lands as root. The firewall starts with the container and tests
   itself; `example.com` must be unreachable, and the start log ends with `Firewall up: … IPv6 closed`
   (or `not present`). If its setup fails, it blocks all traffic and says `FIREWALL SETUP FAILED`.
2. **Logins inside the container** (stored in named volumes, so a rebuild keeps them): `claude` (your
   subscription) and the **bot account's** token (#0-113), not yours: `gh auth login --with-token` (paste,
   Enter, Ctrl-D); `git push` uses that token through the credential helper set in the image (no `gh auth
   setup-git`, whose setting a rebuild would lose). The token: a fine-grained one cannot name a repository
   owned by another user's personal account (its "Resource owner" offers only the bot), so it is a
   **classic** token with the `repo` and `read:org` scopes (`gh` refuses a classic token without
   `read:org`; the bot belongs to no organisation, so it reads nothing) and **without** `workflow` (GitHub
   then refuses a push that changes `.github/workflows/`). The bot reaches nothing but this repository, so
   `repo` reaches no further — keep it that way (a classic token follows every repository the bot is later
   invited to). Give the token an expiry (90 days) and replace it before then: a classic token does not
   expire unless told to. The commit identity (the bot and its `…@users.noreply.github.com` address), git's
   trust of `/workspace` and its credential helper come from `devcontainer.json` and the image: nothing to
   set by hand, and a rebuild keeps them. On the bot account, *Settings → Emails → Keep my email addresses
   private* and *Block command line pushes that expose my email* keep a real address out of the history
   even if a commit gets past these variables. You work in the same container as the bot: a commit you make
   there is the bot's too. `.claude/settings.local.json` is personal: its allow rules would merge into the
   autopilot's `dontAsk` session, so the preflight refuses to start while it exists in the workspace (the
   container mounts the same directory). It is gitignored and never committed; keep it out of the workspace
   (rules you want on the host belong in `~/.claude/settings.json` there, which the container does not
   see).
3. **GitHub** (#0-113): bot as collaborator with Write; branch protection on `main` with required checks
   ("Build, Test & Coverage", "Docker Compose Smoke Test", "Factory guards"), required CODEOWNERS review
   (only once the bot account exists, see below), no bypass for administrators; "Automatically delete head
   branches"; the labels listed in #0-113 (created 2026-10-09). `.github/CODEOWNERS` names the owner's
   login. Shadow mode on the owner's own account, before the bot exists: leave the required CODEOWNERS
   review off (an author cannot approve their own PR, so every autopilot PR touching those paths could
   never merge); the protected paths are then guarded only by the autopilot's path gate and by your review
   of each PR.
4. **Before the first run**, in an autopilot session, run `/workflow-authoring` and check two things in
   `.claude/workflows/backlog-autopilot.js` (and the same constant in `audit.js`, `docs-audit.js`,
   `seed-bugs.js`): `AGENT_TYPE_OPTION`, the `agent()` option that selects a custom agent (not in the
   public docs we checked), and the call shape of `parallel()` in `runAll()`. The preflight's isolation
   check stops the run if the first is wrong.
5. Optional: export `FACTORY_NTFY_TOPIC` (a long random string) on the host before starting the
   container, for phone notifications; the container passes it on and the firewall then allows the ntfy
   host (`FACTORY_NTFY_SERVER`, default ntfy.sh). Only event names and item/PR numbers are sent.

## Running it

```bash
# inside the devcontainer, on a clean main
claude --settings .claude/settings.autopilot.json
> /backlog-autopilot                                   # next ready item, shadow mode
> /backlog-autopilot  with args {"item": "#0-25"}      # a specific item
> /backlog-autopilot  with args {"item": "#0-25", "branch": "refactor/0-25-…"}   # resume after you resolved a BLOCKED item
```

One item per run. The session settings make it `dontAsk`: everything not allowed is refused, nobody is
asked. `/workflows` shows progress; `p` pauses, `x` stops.

**Which item.** Code decides, not an agent: `scripts/factory/next-item.sh` reads `BACKLOG.md`,
`BACKLOG-DONE.md` and `.ai/plan/queue.md` on the run's base commit and the open PRs, and takes, in order:

1. a `ready` **follow-up** whose parent is done — work an item showed was needed runs right after it;
2. the **approved queue**, strictly in order: its first row that is not done, if it can start (ready,
   dependencies done, no open PR or branch). If it cannot, nothing starts — in shadow mode the autopilot
   waits for your merge of the previous item, so every item is built on code you have reviewed;
3. without a queue file, `ready` items by **priority**.

An item whose `**Touches:**` names a path of `.ai/rules/protected-paths.md` (the one list of what no
agent writes) never starts, whatever its Risk, and `check-queue.sh` refuses to queue it: it could only end
BLOCKED at the implementer, as #0-42 did (backlog #0-122). Make it `human-only` or split it
(`.ai/rules/ready.md` point 3). A bare `ci` in Touches counts as protected: name the path in the
parentheses. The autopilot's deny rules, its shell-write hook and CODEOWNERS keep literal copies of that
list; the "Factory guards" check fails when one stops covering it.

`{"item": "#0-25"}` overrides the choice (the item still has to be ready, unblocked and unlocked). When
nothing can start, the run ends with `NOTHING_TO_DO` and names the row and why.

**Which rules the implementer reads.** The panel judges every change against the full files of
`.ai/rules/review/`. The implementer reads `general.md`, `architecture.md` and `security.md` in full for
every item; from `performance.md`, `migration.md`, `k8s.md` and `docs.md` it reads the rules the architect
listed in the plan, each with why it applies. After implementing, `changed-paths.sh` maps the diff onto
rule files (a migration → `migration.md`, a Kafka listener or a repository → `performance.md`, an endpoint
or a `.md` → `docs.md`, a manifest or POM → `k8s.md`); a file from which the plan listed no rule sends
the implementer back to read it in full and check its change (`self-check`) before the panel sees it.
This runs once, after implementing; later fix rounds are judged by the panel. A
missed rule then costs a self-check, not a review round; the PR records which rules were planned and
which self-check ran, and the audit counts selection misses (`.ai/rules/audit.md`).

**Which implementer.** `Complexity: low` items go to `implementer-light` (the same definition, on
Sonnet), the rest to `implementer` (Opus) — the implementer is the most expensive agent of a run. A
light implementer that finds the item is not small stops it, so you can re-rate it.

## Planning the order

`/plan-backlog` (interactive, in your normal session) asks the `planner` agent for a queue: the items in
order, a `**Touches:**` line per item (modules and packages it will change, found in the code), the
reasoning per item, and what was left out and why. `scripts/factory/check-queue.sh` rejects an
inconsistent proposal (a dependency queued after its dependant, a human-only or design item, an unknown
module); the skill shows you the table, applies your
changes, and commits it on a `plan/<date>` branch. **Your merge is the approval**; the queue is
human-owned like `.ai/rules/` (CODEOWNERS, denied to the autopilot), and CI re-checks it on every PR.
Rules: `.ai/rules/planning.md`.

**Scope that grows.** An implementer that finds more work than the item planned finishes the item and
describes the rest under "Follow-up needed" in its handoff; the shipper adds each as a backlog item with
`**Autopilot:** proposed` and `**Follow-up of:**` in the same PR, and you get a notification. `/ready` it
and merge: once its parent is merged too, it runs next. Work without which the item itself cannot pass
stops the item (BLOCKED) instead.

**Scope, measured three times.** Each PR shows "Scope": the item's Touches (predicted at `/ready`), the
architect's modules (predicted just before implementing) and the areas the diff reached, with a
category — `consistent`, `backlog-estimate-off`, `plan-off`, `implementation-drift`, `unclear-item`
(`.ai/rules/audit.md` explains each). A prediction holds when the diff stays within it; which one failed
says which stage was off. Nothing is blocked on it. Touches is an experiment with
an exit criterion (`.ai/rules/planning.md`): if it never teaches anything, it goes.

**Stopping it**: create `.ai/STOP` (local), or open an issue labelled `autopilot-stop` (works from a
phone). The preflight also refuses to start when: main's CI is red, the working tree is dirty, 2 items
in a row were BLOCKED, an audit is due, the daily merge limit (5) is reached, or another run holds the
lock (`scripts/factory/state.sh unlock` clears a stale one).

The breaker and the audit counter are reset only by the owner, outside the autopilot session (which may
not run `scripts/factory-admin/`): after resolving the BLOCKED items, delete `.ai/STOP` and run
`scripts/factory-admin/state-reset.sh blocked`; after an audit, `/apply-audit` runs
`scripts/factory-admin/state-reset.sh audit`. The run state lives in `.ai/runs/` (gitignored): `LOCK`
holds the run id and the base commit every gate compares against, `state.json` the counters and the
history (for a BLOCKED item also its draft PR and the phase that stopped it, which the audit reads).

## The owner's routine

| When | What | Time |
|---|---|---|
| per new item | `/ready #0-N`: confirm criteria, risk, complexity, Touches; merge the backlog change | 5 min |
| per follow-up | a PR created `proposed` items: `/ready` the ones you want next, delete or re-plan the others | 5 min |
| when the queue is done, or every ~10 items | `/plan-backlog`, edit, merge | 15–20 min |
| daily, in shadow mode | review and merge the autopilot's PRs; **label each one** `human:*` | 10–20 min |
| when it happens | BLOCKED draft PRs: decide, fix or split, then resume with `branch` | 5–20 min |
| every 10 items | `/audit` (args `since`, `date`), tick decisions in the report, `/apply-audit <report>`, merge its PR | 20–30 min |
| every 10–15 items | `/docs-audit`; look at the architecture as a whole (what no single review sees) | ~1 h |
| sometimes | do an item yourself, then `/review` and `/ship` — the knowledge should end up in you, not only in the repository | — |

Spot checks must be real (read the diff, run the tests), or the `human:*` labels measure nothing.

## What isolates what

The agents' instructions are not a boundary; these are, each with its limit:

| Layer | Stops | Does not stop |
|---|---|---|
| `dontAsk` + deny rules (`settings.autopilot.json`) | file-tool writes to human-owned paths and to the gates' own files; the network tools; force pushes, merges, `gh api` | what a shell command does indirectly — hence the hooks |
| Hooks (`.claude/hooks/`, tested by `test-hooks.sh`) | skipped verification, `exec:` goals, shell writes to protected paths, file-tool writes outside the repository (and `..` or a symlink as the last component), any `git grep` option outside an allow-list (abbreviated, quoted or expanded forms included), a line continuation (backslash-newline) in any command, pushes other than a feature branch, running tampered factory scripts, secret reads | a shell command shaped to get past a pattern: they are heuristics |
| Gates on commits (`changed-paths.sh` before review, CI "Factory guards" on the PR) | the same changes once committed, whatever produced them | — the PR is the last and most reliable line |
| CODEOWNERS + branch protection (#0-113) | a merge of any of it without the owner | — once configured; until then shadow mode is what keeps `main` safe |
| Devcontainer + firewall | the agents' own commands: access to your host account, files and credentials; network beyond Anthropic, GitHub, Maven Central (IPv4; IPv6 only loopback; a failed setup blocks everything) | **code an agent runs** (a test): Testcontainers needs the host's Docker, and Docker access is root over the devcontainer itself, so such code can drop the firewall, start a container outside it, or read files the agents' tools may not (#0-116, accepted for shadow mode only); data through DNS the resolver forwards; GitHub itself as a channel |

## Measuring the reviewers and the pipeline

- **Owner labels** on PRs are the ground truth the audit needs; without them confidence stays `medium`
  and security/architecture rules can never be relaxed. `human:introduced-<stage>` (optional) confirms or
  corrects where the pipeline audit says a case started.
- **Pipeline cases** are derived by `scripts/factory/audit-data.sh` from what the pipeline records (BLOCKED,
  extra rounds, scope off, acceptance, owner labels, BLOCKED runs without a PR) — nobody logs them by hand.
- **Seeded defects**: `/seed-bugs` with args `{"pattern": "P-01", "commit": "<merged sha>", "runId":
  "<id>"}` plants a known defect from `.ai/audit/benchmark/patterns.md` on a throwaway `seed/` branch and
  records who caught it in `.ai/audit/benchmark/results.md`. Rotate patterns; delete the branch after.
- **Escaped defects**: an item that fixes a defect an earlier item introduced carries
  `**Fixes:** #0-N · **Escaped from:** <stage>` — `review-<dimension>` when the panel missed it, or the pipeline
  stage that let it in (BACKLOG.md conventions); the pipeline audit counts the markers added in its period.

## Pinned versions

Agent models are full ids in `.claude/agents/*.md`; Claude Code is pinned to the version the
devcontainer image was built with (`DISABLE_AUTOUPDATER=1`); the devcontainer's docker-outside-of-docker
feature is pinned by digest in `.devcontainer/devcontainer-lock.json` (`devcontainer upgrade` moves it).
Treat an upgrade of any of them like a dependency
upgrade: change it on purpose, then run a few seeded defects and compare `results.md` before trusting it.

## Local model (experimental, optional)

`scripts/factory/local-review.sh` (advisory second opinion in the PR, never blocks; the autopilot runs
it with `{"localReview": true}`) and `scripts/factory-admin/local-implement.sh` (you run it by hand, for a
`Complexity: low` item while your limit is exhausted; the branch stays local and is reviewed later by the
normal panel with `{"item", "branch"}`). Both point Claude Code at Ollama on the host (`OLLAMA_URL`). Measure first: with the test stack running, a 27B model may not fit
next to it in 32 GB.

## Cloud (later)

Everything is files in the repository, so the same setup can run elsewhere: Claude Code routines (check
first that the cloud environment can run Docker for Testcontainers) or GitHub Actions with this
devcontainer image. Check the current rules for subscription usage of non-interactive runs before
moving, and keep `--settings .claude/settings.autopilot.json` wherever it runs.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `NOT_STARTED: agent isolation check failed` | `AGENT_TYPE_OPTION` is wrong: reviewers ran as general-purpose agents |
| `NOT_STARTED: label '…' does not exist` | create the labels of #0-113 |
| a hook says "factory scripts were changed on this branch" | `scripts/factory/` differs from the base commit in `.ai/runs/LOCK` (or `origin/main` without a run) — either the branch touched it (owner's decision) or `main` moved on: rebase the branch by hand |
| every run `BLOCKED: test environment failure` | Docker not reachable from the container, or the firewall blocks Maven Central |
| `permission denied while trying to connect to the docker API` inside the container | the container's main process must run as root (`"containerUser": "root"` in `devcontainer.json`) for the docker-outside-of-docker feature to proxy the socket to `dev`; rebuild with `devcontainer up --workspace-folder . --remove-existing-container` |
| `mkdir: cannot create directory '/home/dev/.m2/wrapper': Permission denied` (or `gh` cannot store its login) | a volume created before the image made its mount point, so it is root's: remove it if it is empty (`docker volume rm incident-platform-m2` / `incident-platform-gh`) and start the container again; to keep its contents (a Maven cache, a login), give it to `dev` instead: `docker run --rm -v incident-platform-m2:/v alpine chown -R 1000:1000 /v` |
| `FIREWALL SETUP FAILED: all traffic is blocked` at container start | a required domain did not resolve or GitHub's IP ranges could not be fetched; the message above it names the cause. Fix it and restart the container (an optional domain only warns) |
| a reviewer "returned no valid verdict" | it answered prose instead of JSON; check its transcript; repeated → audit finding |
| `NOTHING_TO_DO` | the reason names the next queue row and why it cannot start: not `ready` on main, a dependency not done, a path the autopilot may not write ("make it human-only or split it"), or an open PR/branch (a lock — usually the previous item waiting for your merge). "The approved queue is done" → `/plan-backlog` |
| `NOT_STARTED: the queue on main is invalid` | a merged change broke `.ai/plan/queue.md` (e.g. an item it lists was removed); `scripts/factory/check-queue.sh` names the rows — fix them or re-plan |
| a hook blocks a legitimate command | read the hook's message; the hooks are tested by `.claude/hooks/test-hooks.sh` — fix the hook and its test together |
