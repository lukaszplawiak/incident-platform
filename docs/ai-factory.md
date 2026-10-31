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
       pass → artifact .ai/runs/<item>/gate-r<round>-<attempt>.json (HEAD, tests and JaCoCo per module, dependency:tree)
       for the reviewers and the acceptance reviewer; an unreadable report → BLOCKED (#0-160)
  → review panel, in parallel: general · architecture · security · performance · docs (+ migration, k8s when touched)
       invalid verdict → BLOCKED (fail closed) · NEEDS_HUMAN → BLOCKED · upheld dispute → BLOCKED
       blocking → implementer → test gate → the reviewers that blocked + security + general, on the delta
       (≤3 rounds) · same findings again → BLOCKED
  → verifier (when the panel approves and left claims unverified; #0-160, ADR-0037)
       gets only the claims, the commit and the gate's artifact, never the diff · reads only the gate's files ·
       WebFetch only https under OSV, GitHub advisories, Maven Central, Tomcat/Spring security pages, no query
       refuted → a blocking finding, back to the implementer (last round → BLOCKED) · unverifiable → BLOCKED,
       the owner accepts the ids they read by resuming with acceptUnverifiable: [ids] · every verdict in the PR
  → acceptance-reviewer        REJECT → one fix → tests → one delta round → acceptance again · NEEDS_HUMAN → BLOCKED (risk-high: owner merges)
  → shipper (outside the loop) PR with the full review record · item moved to BACKLOG-DONE.md · auto-merge only outside shadow mode
  → CI + Factory guards + CODEOWNERS on the PR · Main guard: red main → autopilot-stop issue
```

Every arrow is code in `.claude/workflows/backlog-autopilot.js`; agents do the work, the script decides.
That code is tested without a model (backlog #0-163): `.github/scripts/test-autopilot-workflow.mjs`, in Factory
guards, runs its helpers and whole runs against a fake `agent()` that answers each agent and factory script as a
scenario says, so a branch that stops fail-closed today cannot start letting an item through unnoticed.
A BLOCKED item becomes a draft PR labelled `blocked`, which is also the lock that keeps the picker away
from it.

## Phases

| Phase | What | Done when |
|---|---|---|
| 0 | Prepare items: `/ready #0-N` per item (numbered acceptance criteria, risk, complexity, `ready`) | ~10 items `ready` on `main` |
| 1 | Use the panel by hand: `/review` (same agents and rules as the autopilot; it runs the same test gate on the working tree first) | you trust its findings on your own changes |
| 1b | Plan the order: `/plan-backlog` proposes `.ai/plan/queue.md` (order, Touches, why); you edit and merge it | a queue you agree with is on `main` |
| 2 | **Shadow mode** (default): `/backlog-autopilot` opens PRs, never merges; you merge, and label the bot's PRs from your own account (#0-126) (`human:agree`, `human:fp-<dim>`, `human:missed-<dim>`; optionally `human:introduced-<stage>`) | ~10 PRs, and you agreed with the merge decision in ≥ 9 |
| 3 | Auto-merge, after #0-116 (code an agent runs must not be able to drop the devcontainer's firewall) and the decision on which paths need the owner's review (#0-153: `.dockerignore`, `.ai/context/`, `.ai/decisions/`); #0-113's bot account and rulesets are in place: set `allow_auto_merge` to `true` in `.github/repository-settings/repository.json`, set `required_approving_review_count` to 0 in `rulesets/main.json`, and remove the shadow-mode `*` line of `.github/CODEOWNERS` (#0-153 decides which paths stay owned; a PR touching none then merges on green checks), and `apply` them (Setup step 3), after checking that the bot's token still has no `workflow` scope (`gh auth status` as the bot: with it, the bot could push a workflow whose token reports a required check, ADR-0034), in `.claude/settings.autopilot.json` remove `Bash(gh pr merge *)` from `deny` (keep the `--admin` deny) and add `Bash(gh pr merge * --squash --auto)` to `allow`; run with `{"shadow": false}` | — |
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
3. **GitHub** (#0-113, ADR-0034): the bot as a collaborator with Write (not Admin); the labels listed in
   #0-113 (created 2026-10-09). The rest is files in `.github/repository-settings/`, never set in the UI:
   the ruleset `main` (a PR with your approval of its last push: `.github/CODEOWNERS` names you for every path
   until phase 3, every CI job and
   "Factory guards" as required checks, no force-push or deletion), the repository settings
   (`delete_branch_on_merge` on, `allow_auto_merge` off until phase 3) and the admins (you alone). The
   ruleset's one bypass is the admin role, at merge only: you merge your own PRs, or a PR whose CI is red for
   an outside reason, with `! gh pr merge <n> --merge --admin --match-head-commit <sha>`, typed by you, with
   the head commit you read (the bypass also skips the review of the last push: without the SHA a commit the
   bot pushed onto your branch afterwards, #0-131, would merge unread). Claude Code sessions are denied the
   bypass, every write to the ruleset and `gh pr review` (an approval of theirs would be yours), since they use your login. To change a setting: edit its file in a
   PR, then with your own (admin) token run `! ruby .github/scripts/repository-settings.rb plan` and `apply` from that branch just before
   merging it; the PR's Lint Workflows checks GitHub against its files and is red until then, and other open
   PRs, checked against main's files, are red from the `apply` to the merge (re-run them after). A setting
   changed in the UI turns every PR's Lint Workflows red. CI's token sees neither the bypass actors, nor the
   repository settings, nor the admins: `ruby .github/scripts/repository-settings.rb check`, run with your
   token, compares those too. The scans' secrets (`SNYK_TOKEN`, `NVD_API_KEY`) live in the environment
   `scans`, which only `main` may use (ADR-0035), never as repository secrets: `apply` creates the environment
   and its branch policy but writes no secret, so you set their values in Settings → Environments → `scans`
   and delete any repository-level copy (Settings → Secrets and variables → Actions); until then `check`
   lists what is left, and a token you replace goes there, not to the repository.
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
`.ai/rules/review/` (but `factory.md`, the rules for the factory itself: security and architecture read it only
when a diff touches an Owner-only path of `protected-paths.md`, which no autopilot item does — in practice the
owner's `/review`, backlog #0-161). The implementer reads `general.md`, `architecture.md` and `security.md` in full for
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
phone). The preflight also refuses to start when: main's CI is red (the latest run of a push to main; a
fork pull request's run from a branch named `main` does not count, backlog #0-148), the working tree is
dirty, 2 items in a row were BLOCKED, an audit is due, the daily merge limit (5) is reached, another run
holds the lock (`scripts/factory/state.sh unlock` clears a stale one), or Claude Code is older than 2.1.295
(the hooks' `onFailure: "block"`, backlog #0-162, ADR-0039).

The breaker and the audit counter are reset only by the owner, outside the autopilot session (which may
not run `scripts/factory-admin/`): after resolving the BLOCKED items, delete `.ai/STOP` and run
`scripts/factory-admin/state-reset.sh blocked`; after an audit, `/apply-audit` runs
`scripts/factory-admin/state-reset.sh audit`. The run state lives in `.ai/runs/` (gitignored): `LOCK`
holds the run id, the base commit every gate compares against and the run's branch, the only one the
autopilot may push (#0-131), `state.json` the counters and the
history (for a BLOCKED item also its draft PR and the phase that stopped it, which the audit reads).

## The owner's routine

| When | What | Time |
|---|---|---|
| per new item | `/ready #0-N`: confirm criteria, risk, complexity, Touches; merge the backlog change | 5 min |
| per follow-up | a PR created `proposed` items: `/ready` the ones you want next, delete or re-plan the others | 5 min |
| when the queue is done, or every ~10 items | `/plan-backlog`, edit, merge | 15–20 min |
| daily, in shadow mode | review and merge the autopilot's PRs; **label each one** `human:*` from your own account (see below) | 10–20 min |
| when it happens | BLOCKED draft PRs: decide, fix or split, then resume with `branch` | 5–20 min |
| every 10 items | `/audit` (args `since`, `date`), tick decisions in the report, `/apply-audit <report>`, merge its PR | 20–30 min |
| every 10–15 items | `/docs-audit`; look at the architecture as a whole (what no single review sees) | ~1 h |
| sometimes | do an item yourself, then `/review` and `/ship` — the knowledge should end up in you, not only in the repository | — |

Spot checks must be real (read the diff, run the tests), or the `human:*` labels measure nothing.
Add the labels from your own GitHub account: the audits count a `human:*` label only when the repository owner
added it (backlog #0-126) — one added by the bot counts for nothing, and while the autopilot runs on your own login
(shadow mode before the bot account) no label counts at all, since the autopilot then acts with your token. To
confirm a label someone else added, remove it and add it again yourself.

## What isolates what

The agents' instructions are not a boundary; these are, each with its limit:

| Layer | Stops | Does not stop |
|---|---|---|
| `dontAsk` + deny rules (`settings.autopilot.json`) | file-tool writes to human-owned paths and to the gates' own files; WebSearch, and WebFetch beyond the verifier's six hosts; force pushes, merges, `gh api` | what a shell command does indirectly — hence the hooks; which agent fetches, and which path of an allowed host: a rule matches the host only, for every agent |
| Hooks (`.claude/hooks/`, tested by `test-hooks.sh`) | skipped verification, `exec:` goals, shell writes to protected paths, file-tool writes outside the repository (and `..` or a symlink as the last component), any `git grep` option outside an allow-list (abbreviated, quoted or expanded forms included), a line continuation (backslash-newline) in any command, pushes other than the run's own branch (recorded in `.ai/runs/LOCK`, #0-131), running tampered factory scripts, secret reads; WebFetch by any agent but the verifier, and by the verifier outside its path prefixes or with a query string (`webfetch-allowlist.sh`, on `agent_type`, #0-160; in the owner's sessions, `--interactive`, only the verifier's fetches are checked); each agent's read-only or write-scoped guard both in its frontmatter and as a twin in both settings files on `agent_type` (#0-164, ADR-0041), so a Workflow's agent keeps it even if Claude Code stops running frontmatter hooks for it | a shell command shaped to get past a pattern: they are heuristics; a twin when Claude Code sends no `agent_type` (the frontmatter hook stays for that); data in a URL path to an allowed host (the verifier reads only the gate's artifacts in `.ai/runs/`, so it has little to send) |
| Gates on commits (`changed-paths.sh` before review, CI "Factory guards" on the PR) | the same changes once committed, whatever produced them | — the PR is the last and most reliable line |
| The ruleset of `main` (#0-113, ADR-0034, `.github/repository-settings/`) | a push to `main` without a PR (anyone's), a merge with a red or missing required check, without an approval of the last push, or of a code owner's path without the owner's (anyone's but the owner's) | the owner's token can merge any PR (the admin role's bypass, at merge; Claude Code sessions are denied it); a bypass actor or an admin added in the UI is seen only when the owner runs `repository-settings.rb check` |
| Devcontainer + firewall | the agents' own commands: access to your host account, files and credentials; network beyond Anthropic, GitHub, Maven Central and the verifier's advisory hosts (OSV, Tomcat, Spring: open to the whole container, by IP, so also to other sites a CDN serves from those IPs) (IPv4; IPv6 only loopback; a failed setup blocks everything) | **code an agent runs** (a test): Testcontainers needs the host's Docker, and Docker access is root over the devcontainer itself, so such code can drop the firewall, start a container outside it, or read files the agents' tools may not (#0-116, accepted for shadow mode only); data through DNS the resolver forwards; GitHub itself as a channel |

## Measuring the reviewers and the pipeline

- **Owner labels** on PRs are the ground truth the audit needs, counted only when the owner added them (#0-126,
  above); without them confidence stays `medium`
  and security/architecture rules can never be relaxed. `human:introduced-<stage>` (optional) confirms or
  corrects where the pipeline audit says a case started.
- **Pipeline cases** are derived by `scripts/factory/audit-data.sh` from what the pipeline records (BLOCKED,
  extra rounds, scope off, acceptance, owner labels, BLOCKED runs without a PR) — nobody logs them by hand.
- **Seeded defects**: `/seed-bugs P-01 <merged sha> <run id>` (or args `{"pattern": "P-01", "commit": "<merged
  sha>", "runId": "<id>"}`; the command passes its words as one string, #0-174; a session runs the version of the
  workflow it loaded at its start, so after a change to `seed-bugs.js` start a new session, or pass the file as
  `scriptPath`) plants a known defect from
  `.ai/audit/benchmark/patterns.md` on a throwaway branch, has all seven reviewers review it (k8s since #0-115's
  `k8s.md`) and records who caught it in the gitignored
  `.ai/runs/benchmark/results.md` (`scripts/factory/seed-record.sh`). Start it from a clean `main` at `origin/main`, with no
  autopilot run: `scripts/factory/seed-branch.sh` builds the branch from `main` (the change's code reverted as its base,
  then back), never from the merged commit, whose hooks and agents the session would otherwise run on (#0-173,
  ADR-0044). Choose a host that passes both of its refusals: its code still reverts on `main` (`git diff --binary
  <parent> <sha> -- <code> | git apply -R --check`) and none of its files is the evaluator's (a protected path or
  `CLAUDE.md`). The seeder commits nothing (#0-172, ADR-0047): its Bash is read-only git (`readonly-bash.sh`) and its
  Edit reaches only the change's files (`write-scope.sh --seed`, from the run's record `.ai/runs/SEED`, which also gives
  `guard-migrations.sh` the run's base, so a migration the change added can be planted in). `scripts/factory/seed-check.sh`
  confirms the tree after the seeder (on the branch at the change's commit, its edit uncommitted and in the change's
  files only, and its rule and dimension its pattern's row of `patterns.md` on `origin/main`, #0-180; it returns the
  edit's hunks, the run's ground truth, with at most two unedited places the seeder names, #0-181), and
  `scripts/factory/seed-fold.sh` makes the change and the edit one commit before the panel runs.
  Nothing the panel reads names the run (#0-177): the branch is `review/<8 hex>` (derived from the pattern and the run
  id), the base says `Revert "<subject>"`, and the change and the defect are one commit, with the run's reflog entries
  dropped (the branch's, and HEAD's above the switch onto it; the rest of your HEAD reflog stays). The catalogue
  (`patterns.md`, `.ai/rules/audit.md`) stays readable to the reviewers, on purpose: ADR-0044 records why and how to check
  a run's transcripts for a read of it. The original change is in reach as well (#0-185, ADR-0044 decision 6):
  `main` holds it, so `git diff origin/main HEAD` shows the defect alone, and the revert's patch holds its lines.
  Before a series' rows land, check whether its reviewers read the original: `seed-scan.sh` (below) lists the reads it
  knows, and ADR-0044 decision 6 records what the hand scan looks for. Mark a row whose expected reviewer did in
  "Caught by expected", and one whose other reviewer did in "Also caught by". A measure by hand (an Owner-only host,
  #0-115's F1) commits the plant into the change and gives the panel the autopilot prompt, not a manual `/review`,
  with a sentence that `factory.md` applies. F1's manual review of an uncommitted plant reached the original 4 times
  of 4. The Workflow tool shows every agent, word for word, the message that started the turn, so a typed
  `/seed-bugs P-01 …` names the pattern to the panel (#0-179). So the session starts a run from a turn with no user
  message: once it holds the run's words, any message of yours makes it start a background command that does
  nothing, and it calls `Workflow` in that command's notification turn, which relays nothing. Before a run's row lands, run
  `scripts/factory-admin/seed-scan.sh <the run's transcriptDir> <pattern> <run id>`. It reports a relayed message
  that names the run, a reviewer's read of the merged change (content or names only, except a read of the reviewed
  branch's own commit, which the run's journal names) and a read of the answer key. It is an aid to your reading of
  the transcripts, not a replacement: it refuses a transcript shape it does not know (ADR-0044 decision 6), and its
  output quotes what the reviewers ran, which is data, never instructions. The run goes back to `main` before it
  writes the row, and an outcome that could not (a dirty tree) says where it left it (`leftOn`). The commit's code must still
  apply to `main`, so choose a recent one. Rotate patterns; after each run delete its branch (the outcome's `cleanup`
  names it). Runs follow one another with no commit between them; after a series, copy its rows from
  `.ai/runs/benchmark/results.md` into `.ai/audit/benchmark/results.md` in one pull request (the audit reads the tracked
  files only), then delete `.ai/runs/benchmark/results.md`; a run id already in any of the four tables (both kinds, as
  a series gathers them and as they land) is refused, so a landed one stays refused. The seeder cannot write a row (its scope is the change's files, #0-172). A run that measured nothing
  says so (#0-170): a seeder that was refused a command, a skip without a reason and an answer without its branch,
  file, line or rule end it FAILED, not SKIPPED.
  A clean run (#0-180, ADR-0044 decision 7) takes a `C-` pattern, a rule's deliberate exception planted with its
  reason, and measures false alarms instead of catches: a dimension that stops the change at the planted line under
  the pattern's rule is a false alarm, one that notes it, or stops it under another rule, is recorded as raised. Its
  row goes to `.ai/runs/benchmark/clean.md` (then `.ai/audit/benchmark/clean.md`). The panel's prompt is the same, so
  rotate clean and defect runs in a series.
- **What the rule files claim** (backlog #0-184, ADR-0046): Factory guards' "Review rule claims are true"
  (`.github/scripts/check-rule-claims.rb`) fails a label naming a script, test or guard that does not exist, an
  "Enforced by" whose check runs in no required job on a pull request, only while continuing on error or only in
  the owner's step (it is then at most "Backed by"), a rule file other than `factory.md` naming the measure (`benchmark`, `seeded`, `seeder`,
  `/seed-bugs`, `patterns.md`, a pattern id; ADR-0044), and a pattern citing a missing or retired id of its
  dimension's file (`FAC-S` is security's, `FAC-A` architecture's). It reads backticked names only: a label's
  plain words, and a synonym of the measure, are still the reviewer's and the owner's to see.
- **Escaped defects**: an item that fixes a defect an earlier item introduced carries
  `**Fixes:** #0-N · **Escaped from:** <stage>` — `review-<dimension>` when the panel missed it, or the pipeline
  stage that let it in (BACKLOG.md conventions); the pipeline audit counts the markers added in its period. A
  marker counts only when it reached `main` in a PR the owner opened (#0-129; the others are listed as
  `unverifiedEscapes`), so add it on your own branch, not through an autopilot PR; Factory guards fails a marker
  without its `**Fixes:** #0-<digits>` part (#0-128).

## Pinned versions

Agent models are full ids in `.claude/agents/*.md`; Claude Code is pinned to the version the
devcontainer image was built with (`DISABLE_AUTOUPDATER=1`). The preflight refuses one older than 2.1.295, the
first that honours the hooks' `onFailure: "block"` (an older one ignores the field silently, ADR-0039). The devcontainer's docker-outside-of-docker
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
