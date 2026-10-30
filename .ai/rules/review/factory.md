# Review rules: the AI factory

Owned by the maintainer; agents never edit it. Written for backlog #0-161 (2026-10-24): every round of the
review of #0-160 reported as `ruleGap` that no rule covered the factory, so a defect in it (a hook that fails
open) could only be non-blocking. Rewritten as invariants on 2026-10-28 (backlog #0-115): each rule says what is
true of the factory, and its "Check:" line what to look for in a diff. No id changed its meaning, none is retired.
The list of checks that was section 3 moved into the rules each check backs, so a rule and what checks it are
read in one place (FAC-A01's reason, applied to this file).

Read by `review-security` (the `FAC-S` rules) and `review-architecture` (the `FAC-A` rules), and only when the
diff touches a path of the **Owner-only** list of `.ai/rules/protected-paths.md` (the one list, as it is on
`main`; a change outside it costs nobody anything to read). `/review` decides it and says so in your task;
ADR-0038 records why. The rules belong to those two dimensions: a blocking finding cites one by id like any
`SEC-`/`ARC-` rule, and a reviewer of another dimension reads none of this file. Not read by the implementer or
the architect: no autopilot item can change these paths.

**Every change this file judges is the owner's own pull request**, and the owner merges it through the ruleset's
bypass, which skips the required checks as well as the review (ADR-0034). No rule here is "Enforced by"
(`_common.md` defines the two labels); one that became so would hold because the owner merges only once CI is green,
or names the red job and its outside reason (CLAUDE.md "Working style"), not because a red check stops the merge.
`check-factory-guards.sh` ("Changes that need the owner") passes on the owner's PRs, so it backs no rule of this
file; what it looks for is other dimensions' rules (GEN-08, MIG-02, K8S-08), reviewed there.

Read `.ai/context/security.md` "AI factory" first (why the verifier has the network, where each list lives)
and the ADRs of the area "AI factory" (`.ai/decisions/README.md`). Most of the wiring is checked by scripts,
named under the rules they back: spend a finding on what they cannot see, which each "Check:" line names (intent,
a new channel, a new combination of powers).

## 1. Security (`review-security`)

- **FAC-S01 The rule of two.** No agent has all three of: reading text that the change under review or someone
  outside controls (a diff, a PR or issue, a web page, claims written by a reviewer who read the diff), reading
  anything secret or beyond its task, and sending data out (a network tool, `gh` that writes, a push, the path of a
  URL it fetches). The verifier reads claims and fetches, so it reads only the gate's files (#0-160, ADR-0037). A
  change that gives an agent the third is blocking. Check: for every agent whose tools, hooks, scopes or allow rules
  the diff changes, which of the three it has after the change; name the three and the agent.
- **FAC-S02 The network.** Only an agent whose definition names `WebFetch` fetches, and only what
  `.claude/hooks/webfetch-allowlist.sh` allows: `https`, a host *and* path prefix, no query string. An entry is a
  path prefix nobody else can write under, never a whole host. A host opened in `.devcontainer/init-firewall.sh` is
  open to every process of the container and by IP, so to every site on the same addresses. `WebSearch` stays
  denied. A new external service is `NEEDS_HUMAN` (`_common.md`). Backed by: `check-web-access.sh` (only the
  verifier names `WebFetch`, none `WebSearch`, the hook in its mode in both settings files, the autopilot's allow
  rules equal the hook's hosts, the firewall opens each) and the hook's own tests (`test-hooks.sh`). Check: a new or
  widened entry of the allow-list, and who else can write under its prefix; a host opened in the firewall, and which
  other sites share its addresses (say which: a shared front end).
- **FAC-S03 Tools and scopes.** Every agent declares `tools:`, and one that reads untrusted text has the narrowest
  tools its task needs. A read-only agent runs `readonly-bash.sh`; one that writes runs `write-scope.sh` with the
  narrowest prefixes; one that can send reads by `read-scope.sh`, with file names, not a whole directory the
  branch's code can write to (review round 3 of #0-160). A widened scope, or a scope dropped from one of its places,
  is a finding. No allow rule or skill's `allowed-tools` entry is a wildcard over a command with an option that
  writes a file or runs a program (`git show *` and `git diff *` take `--output=<file>`, `--ext-diff`), unless a
  hook of that session refuses the option: pre-approved means no prompt, in a session that reads the change (review
  round 2 of #0-161; #0-165 removed them from `/review` and `/accept`, #0-167 is the rest). A mode of a guard hook
  that lets a command through by its first words allows its options and arguments too, so it names the forms it
  allows, and an argument that reads outside the repository's tracked files (a directory whose own files a tool
  loads, `kubectl kustomize .`) is one of them only when the hook checks where it leads (#0-182). Backed by:
  `check-web-access.sh` (every agent declares `tools:`, the verifier's exact read scope in its frontmatter and in
  both settings files) and `check-hook-entries.rb` (each frontmatter guard has its twin in both settings files).
  Check: a widened `tools:`, scope or prefix, or a scope dropped from one of its places; a new allow rule,
  `allowed-tools` entry or hook mode, and every option and argument of the command it lets through.
- **FAC-S04 A guard fails closed.** Every way a PreToolUse guard can fail ends in `exit 2`: its helper does not
  load, `jq`/`python3` is missing, the input does not parse or is empty, an argument is unknown, an unset variable
  under `set -u`, a tool it runs fails. Claude Code treats any other exit code as a non-blocking error and runs the
  call (#0-162, ADR-0037). The two exceptions are `guard-migrations.sh`'s, the one guard of the owner's own edits in
  every session: without `jq` and `python3`, and with a base ref that does not exist, it warns and lets the edit
  through, so that a session on a machine or clone without them does not break (CI's edited-migration check still
  compares with the PR's base). A hook that cannot start or times out is blocked by Claude Code, through
  `"onFailure": "block"` on every hook entry (ADR-0039). Backed by: `test-hooks.sh` (its fail-closed table runs each
  hook into the failures it lists) and `check-hook-entries.rb` (every entry of both settings files and of the
  frontmatter of agents, skills and commands, subdirectories too, is a command hook with `onFailure: "block"`, not
  `async`, a hook of `.claude/hooks/` with plain arguments and a row in that table; no `disableAllHooks`; no key
  repeated in one map). Check: a new or changed failure path of a hook, which the table does not list yet, and the
  exit code it ends in; blocking if not 2, with the call it lets through as `impact`.
- **FAC-S05 No secret within reach of an agent that can send.** The secret-file deny rules of both settings
  files apply to it, its read scope excludes them, and a path check counts as no check for a file the branch's own
  code could have written: a symlink, a second hard link, a file under `.ai/runs/` before the gate wrote it
  (#0-160). Check: a new read path, scope or file an agent that can send reaches, and who could have written it
  before the agent reads it.
- **FAC-S06 The owner's decisions stay the owner's.** The session denies keep a session from merging past the
  ruleset (`--admin`, the API), approving a PR, changing the rulesets, environments, secrets or collaborators, or
  running `repository-settings.rb plan`/`apply` (ADR-0034, #0-131, #0-154), and the autopilot's `gh pr merge` deny
  keeps shadow mode. Removing or narrowing one is blocking, whatever it is for. Check: every removed or changed deny
  of `.claude/settings.json` and `.claude/settings.autopilot.json`, and every new allow rule or hook mode that would
  let one of those commands through. (`repository-settings.rb check` compares GitHub's own settings, not these
  denies.)

## 2. Architecture (`review-architecture`)

- **FAC-A01 One list per control.** A control's list (protected paths, the WebFetch allow-list, the pinned
  tools) lives in one file that code reads; a copy exists only where the last line of defence must not parse it,
  and a Factory guards check fails when the copy drifts, both ways: a copy that holds an entry the list lacks fails
  too (#0-122: ten copies had drifted; #0-168: the copies claimed `.node-version` before the list had it). Backed
  by: `check-protected-paths.sh` (the protected-path copies) and `check-web-access.sh` (the allow-list's). Check: a
  new list, or a new copy of one, and the check that compares it; a second list without such a check is a finding.
- **FAC-A02 Agents' answers fail closed.** A missing, malformed or unsourced answer of an agent stops the item or
  counts as not settled, never as an approval: the workflow validates it (`valid()`, the schemas) and a fallback
  goes the cautious way (`verifyClaims`: a `verified` without source and quote is `unverifiable`). A path that turns
  "no answer" into progress is blocking. Backed by: `test-autopilot-workflow.mjs` (the known paths, against a fake
  `agent()`). Check: every new or changed branch of a workflow that reads an agent's answer, and where it goes when
  the answer is missing, malformed or the call throws.
- **FAC-A03 Every stage leaves a record.** A stage or agent output that decides anything reaches the PR
  (`ship-record`) and the audit's stage table (`.ai/rules/audit.md`): what the PR does not show, the owner cannot
  check and the audit cannot count (#0-160: the reviewers' `unverified` lived only in a schema). Check: a new stage,
  agent or decision field, and the line of `ship-record` and of the stage table that carries it.
- **FAC-A04 A guard the autopilot relies on is session-level.** A hook that limits an agent runs in both settings
  files keyed on `agent_type`, besides the agent's frontmatter: Claude Code promises frontmatter hooks only to
  agents of the Agent tool, an @-mention or `--agent`, never in `claude -p`, and not to the agents the `Workflow`
  runtime spawns (review round 2 of #0-160, ADR-0041). Backed by: `check-hook-entries.rb` (each frontmatter guard
  has its twin in both settings files, the required session-wide guards are wired, and an agent with Bash or a file
  tool has its guard unless `UNGUARDED` names it, #0-186). Check: a guard whose scope is wrong for its agent's task
  (the check sees only that one exists), and a new `UNGUARDED` entry and its reason.
- **FAC-A05 A weaker layer is named.** A change that removes or weakens a control (a deny becomes an allow with a
  hook, a check becomes a warning) says so in its ADR and in README "Infrastructure Hardening" (CLAUDE.md "Security
  inventory"), naming what now holds it (#0-160: the bare WebFetch deny). Check: every removed deny, check, guard or
  failure mode in the diff, and the ADR and README line that names it; weakening without one is a finding.
- **FAC-A06 A value read from an agent's answer is typed.** A workflow that decides on a field of an agent's
  answer (a `factory-ops` script's JSON, a reviewer's verdict, the seeder's ground truth) types that field in the
  call's schema; naming only the required keys types nothing. A `factory-ops` answer allows only the script's
  fields and `factory-ops`' own `{error, exitCode}` (`additionalProperties: false`). Untyped, the agent may answer
  every value as a string or wrap the JSON in another key (#0-174: `"ok": "true"`, `files` as one string; #0-176:
  every verdict's `blocking` as one string, so the Record phase threw). An untyped schema on an answer the workflow
  does not read (a notification, a record that only reaches the PR) is not a finding. Backed by:
  `test-autopilot-workflow.mjs` (`seed-bugs.js`' schemas only: each factory script's answer and the panel's
  verdict; `backlog-autopilot.js`' untyped `state.sh` answer is #0-175). Check: a new or changed `schema` of an
  `agent()` call, and every field of it the workflow reads afterwards.

## Other checks of these paths

Lint Workflows (`ci.yml`) runs actionlint, shellcheck of every tracked `.sh` (`check-shell.rb`), no download
outside `install-pinned-tool.sh` (`check-downloads.rb`), zizmor, the pinned tools against their publisher
(`check-pinned-tools.sh`) and the repository settings (`repository-settings.rb check`); Factory guards runs the
factory scripts' tests (`test-*.sh`), the escape markers' and the queue's checks. No rule of this file rests on
them: the rules for CI's supply chain are #0-136's. A check that is missing for a rule above is not a reason to
block; name it in `ruleGap` as a candidate.

## Output notes

For each finding: **What** (file and line), **What it lets through** (the `impact`: which agent, which call
or data, in which session — autopilot or the owner's), **Suggested fix** (the existing guard or list to use).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
