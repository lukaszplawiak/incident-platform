# Review rules: the AI factory

Owned by the maintainer; agents never edit it. Written for backlog #0-161 (2026-10-24): every round of the
review of #0-160 reported as `ruleGap` that no rule covered the factory, so a defect in it (a hook that fails
open) could only be non-blocking.

Read by `review-security` (the `FAC-S` rules) and `review-architecture` (the `FAC-A` rules), and only when the
diff touches a path of the **Owner-only** list of `.ai/rules/protected-paths.md` (the one list, as it is on
`main`; a change outside it costs nobody anything to read). `/review` decides it and says so in your task;
ADR-0038 records why. The rules belong to those two dimensions: a blocking finding cites one by id like any
`SEC-`/`ARC-` rule, and a reviewer of another dimension reads none of this file. Not read by the implementer or
the architect: no autopilot item can change these paths.

Read `.ai/context/security.md` "AI factory" first (why the verifier has the network, where each list lives)
and the ADRs of the area "AI factory" (`.ai/decisions/README.md`). Most of the wiring is checked by scripts
(section 3): spend a finding on what they cannot see — intent, a new channel, a new combination of powers.

## 1. Security (`review-security`)

- **FAC-S01 The rule of two.** No agent may have all three of: reading text that the change under review or
  someone outside controls (a diff, a PR or issue, a web page, claims written by a reviewer who read the diff),
  reading anything secret or beyond its task, and sending data out (a network tool, `gh` that writes, a push,
  the path of a URL it fetches). A change that gives an agent the third is blocking; name the three and the
  agent. The verifier reads claims and fetches, so it reads only the gate's files (#0-160, ADR-0037).
- **FAC-S02 The network.** Only an agent whose definition names `WebFetch` fetches, and only what
  `.claude/hooks/webfetch-allowlist.sh` allows: `https`, a host *and* path prefix, no query string. A new
  entry is a path prefix nobody else can write under, never a whole host. A host opened in
  `.devcontainer/init-firewall.sh` is open to every process of the container and by IP, so to every site on
  the same addresses: say which (a shared front end). `WebSearch` stays denied. A new external service is
  `NEEDS_HUMAN` (`_common.md`).
- **FAC-S03 Tools and scopes.** Every agent declares `tools:`; an agent that reads untrusted text has the
  narrowest tools its task needs. A read-only agent runs `readonly-bash.sh`; one that writes runs
  `write-scope.sh` with the narrowest prefixes; an agent that can send reads by `read-scope.sh`, with file
  names, not a whole directory the branch's code can write to (review round 3 of #0-160). A widened scope,
  or a scope dropped from one of its places, is a finding. So is an allow rule or a skill's `allowed-tools`
  entry that is a wildcard over a command with an option that writes a file or runs a program (`git show *`
  and `git diff *` take `--output=<file>`, `--ext-diff`), unless a hook of that session refuses the option:
  pre-approved means no prompt, in a session that reads the change (review round 2 of #0-161; #0-165).
- **FAC-S04 A guard fails closed.** Every way a PreToolUse guard can fail ends in `exit 2`: its helper does
  not load, `jq`/`python3` is missing, the input does not parse or is empty, an argument is unknown, an unset
  variable under `set -u`, a tool it runs fails. Claude Code treats any other exit code as a non-blocking error
  and runs the call (#0-162, ADR-0037). The two exceptions are `guard-migrations.sh`'s, the one guard of
  the owner's own edits in every session: without `jq` and `python3`, and with a base ref that does not exist, it
  warns and lets the edit through, so that a session on a machine or clone without them does not break (CI's
  edited-migration check still compares with the PR's base). A new or changed failure path that exits
  otherwise is blocking; `impact` is the call it lets through. A hook that cannot start or times out is
  blocked by Claude Code, through `"onFailure": "block"` on every hook entry (ADR-0039; Factory guards checks
  the entries, section 3).
- **FAC-S05 No secret within reach of an agent that can send.** The secret-file deny rules of both settings
  files apply to it, its read scope excludes them, and a path check counts as no check for a file the
  branch's own code could have written: a symlink, a second hard link, a file under `.ai/runs/` before the
  gate wrote it (#0-160).
- **FAC-S06 The owner's decisions stay the owner's.** The denies that keep a session from merging past the
  ruleset (`--admin`, the API), approving a PR, changing the rulesets, environments, secrets or
  collaborators, or running `repository-settings.rb plan`/`apply` (ADR-0034, #0-131, #0-154), and the
  autopilot's `gh pr merge` deny of shadow mode. Removing or narrowing one is blocking, whatever it is for.

## 2. Architecture (`review-architecture`)

- **FAC-A01 One list per control.** A control's list (protected paths, the WebFetch allow-list, the pinned
  tools) lives in one file that code reads; a copy exists only where the last line of defence must not
  parse it, and a Factory guards check fails when the copy drifts (`check-protected-paths.sh`,
  `check-web-access.sh`). A second list without such a check is a finding (#0-122: ten copies had
  drifted).
- **FAC-A02 Agents' answers fail closed.** A missing, malformed or unsourced answer of an agent stops the
  item or counts as not settled — never as an approval: the workflow validates it (`valid()`, the schemas)
  and a fallback goes the cautious way (`verifyClaims`: a `verified` without source and quote is
  `unverifiable`). A path that turns "no answer" into progress is blocking.
- **FAC-A03 Every stage leaves a record.** A new stage or agent output that decides anything reaches the PR
  (`ship-record`) and the audit's stage table (`.ai/rules/audit.md`): what the PR does not show, the owner
  cannot check and the audit cannot count (#0-160: the reviewers' `unverified` lived only in a schema).
- **FAC-A04 A guard the autopilot relies on is session-level.** A hook that limits an agent runs in both
  settings files keyed on `agent_type`, besides the agent's frontmatter: Claude Code promises frontmatter
  hooks only to agents of the Agent tool, an @-mention or `--agent`, never in `claude -p`, and not to the
  agents the `Workflow` runtime spawns (review round 2 of #0-160). A new guard only in frontmatter is a
  finding; the existing ones are #0-164.
- **FAC-A05 A weaker layer is named.** A change that removes or weakens a control (a deny becomes an allow
  with a hook, a check becomes a warning) says so in its ADR and in README "Infrastructure Hardening"
  (CLAUDE.md "Security inventory"), naming what now holds it (#0-160: the bare WebFetch deny). Weakening
  without saying where it is recorded is a finding.

## 3. Enforced by checks — do not review by hand

Factory guards (`.github/workflows/factory-guards.yml`) fails on these; do not spend a finding on them, and if
it is red on one, say so in `unverified` (you cannot run it).

- Only the verifier has `WebFetch`, no agent `WebSearch`, every agent declares `tools:`, the verifier's exact
  read scope in its frontmatter and in both settings files, the hook in its mode in both settings, the
  autopilot's allow rules equal the hook's hosts, the firewall opens each (`check-web-access.sh`).
- The copies of the protected-path list (`check-protected-paths.sh`); deleted tests, weaker assertions, new
  POM plugins or dependencies, edited migrations, an item marked ready, the build configuration
  (`check-factory-guards.sh`).
- Every hook entry of both settings files and the frontmatter of agents, skills and commands (subdirectories
  too): a command hook with `onFailure: "block"`, not in the background (`async`), a hook of `.claude/hooks/`
  with plain arguments and a row in the fail-closed table, no `disableAllHooks`, no key repeated in one map
  (`check-hook-entries.rb`).
- The hooks' behaviour (`.claude/hooks/test-hooks.sh`), the factory scripts' (`test-*.sh` of the job).
- Lint Workflows (`ci.yml`): actionlint, shellcheck of every tracked `.sh` (`check-shell.rb`), no download
  outside `install-pinned-tool.sh` (`check-downloads.rb`), zizmor, the pinned tools against their publisher
  (`check-pinned-tools.sh`), the repository settings (`repository-settings.rb check`).

A check that is missing for a rule above is not a reason to block; name it in `ruleGap` as a candidate.

## Output notes

For each finding: **What** (file and line), **What it lets through** (the `impact`: which agent, which call
or data, in which session — autopilot or the owner's), **Suggested fix** (the existing guard or list to use).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
