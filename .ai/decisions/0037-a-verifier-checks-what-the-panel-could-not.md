# ADR-0037: A verifier agent checks what the review panel could not, with WebFetch from an allow-list that a session-level hook enforces (backlog #0-160)

- **Status:** Accepted (2026-10-24)
- **Backlog:** #0-160 (part C; part D, the test gate's artifact, shipped in PR #513)
- **Date:** 2026-10-23
- **Author:** human (owner chose the hook (E2), the allow-list and the split into two PRs; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

The reviewers are read-only on purpose (`readonly-bash.sh`: git only, no Maven, no network), so every claim that
depends on the outside goes to the verdict's `unverified` list (`.ai/rules/review/_common.md`), and nothing read
it: in the autopilot it existed only in the `VERDICT` schema of `backlog-autopilot.js`. In the review of #0-157
all six reviewers left the core of the change unverified (that Tomcat 10.1.61 exists and fixes six CVEs). Part D
gave them the gate's artifact (tests, coverage, `dependency:tree`); what needs the web was still unchecked.

`settings.autopilot.json` denied `WebFetch` and `WebSearch` for the whole session. Claude Code's documentation
(checked 2026-10-23): a bare deny removes the tool for the session, subagents included, and no allow rule or
hook gives it back to one agent; a subagent has no permission rules of its own (only `tools`,
`disallowedTools`, `permissionMode`, `hooks`); a `WebFetch(domain:…)` rule matches the host only, for every
agent, so `github.com/search?q=<data>` passes `domain:github.com`; frontmatter hooks do not run in `claude -p`;
session-level hooks run in subagents, with `agent_type` in their input. The spike of #0-160 (2026-10-23)
confirmed `agent_type` for agents the `Workflow` runtime spawns too: `"review-docs"` for
`agentType: 'review-docs'`, `"workflow-subagent"` without one; the session's own calls carry no `agent_type`.
A second spike (2026-10-24, the review of this change) ran the `verifier` itself from a Workflow: `agent_type`
was `"verifier"`, and its frontmatter `read-scope.sh` refused `README.md` while it let `.ai/runs/…` through —
although the documentation names frontmatter hooks only for an agent spawned through the Agent tool, an
@-mention or `--agent`, and only in a trusted workspace, never in `claude -p` (`sub-agents.md`).

What the documentation states (code.claude.com/docs/en, read 2026-10-24): a PreToolUse hook that exits 2 blocks
the call "before permission rules are evaluated, so the block applies even when an allow rule would otherwise
let the call proceed" (`permissions.md`); any other exit code, or a hook that does not start, is a
"non-blocking error" and the action goes ahead (`hooks.md`); hook entries merge across the user, project and
local settings (`hooks.md`; `--settings` is not named there, and the strict hook sits in the file it adds, so
either reading keeps it); WebFetch does not follow a redirect to another host, it returns it for a second call
(`tools-reference.md`), so a redirect within the same host is followed unseen by the hook. Where the request
goes out from is not stated: Claude Code "runs locally" (`data-usage.md`) and WebFetch is an "in-process" tool
(`sandboxing.md`), which reads as the local process fetching, so the devcontainer firewall applies — an
inference, not a documented fact; what is documented is that only the hostname goes to `api.anthropic.com`
first, for a safety check (`data-usage.md`), and the page's content to a model call.

## Decision

- **A `verifier` agent** (`.claude/agents/verifier.md`, Sonnet, `tools: Read, WebFetch`) gets only the panel's
  claims, the commit and the gate's artifact path — never the diff — and answers per claim `verified` (source +
  quote), `refuted` (source + quote + why) or `unverifiable` (why). Its Read is scoped to the files the gate
  writes: what it can read is what it could send. `--name 'gate-r*.json' --name 'dependency-tree-r*.txt'
  .ai/runs/`, not all of `.ai/runs/`: the gate's logs hold what the branch's tests print, and the branch's code
  runs during the gate and can write there; a file with a second hard link is refused (it can be another name of
  `docker/.env`, which a path check does not see; review round 3). Twice: `read-scope.sh <scope>` in its
  frontmatter, and session-level `read-scope.sh --agent verifier <scope>` on Read in both settings files (passes
  every other agent), the documented path for every subagent, since nothing promises frontmatter hooks to
  Workflow agents.
- **Enforcement is a session-level PreToolUse hook on WebFetch** (`.claude/hooks/webfetch-allowlist.sh`):
  `agent_type` must be `verifier`; the URL https, a lowercase host, no user info, port, query string,
  fragment, `%`, `..`, `//` or backslash, and host + path under a prefix of its list. The list lives once, in
  the hook: `api.osv.dev/v1/`, `osv.dev/vulnerability/`, `github.com/advisories/`, `repo1.maven.org/maven2/`,
  `tomcat.apache.org/security-`, `spring.io/security/`. `settings.autopilot.json` runs it without arguments
  (every agent and the session itself are refused), drops the bare WebFetch deny, keeps WebSearch denied, and
  allows `WebFetch(domain:<host>)` for exactly the list's hosts so that `dontAsk` runs what the hook passed.
  `settings.json` runs it with `--interactive` (only the verifier is checked; the owner's session and its other
  agents keep their prompts) and has no WebFetch allow rule, so the owner approves each fetch in `/review`.
- **The firewall** (`.devcontainer/init-firewall.sh`) opens `api.osv.dev`, `osv.dev`, `tomcat.apache.org` and
  `spring.io`; GitHub and Maven Central were open already.
- **The workflow** runs the verifier when a round approves, on the deduplicated `unverified` lists of every
  dimension's latest verdict (none → no call). A missing answer, or `verified`/`refuted` without source and
  quote, counts as `unverifiable`. `refuted` → a blocking finding (`dimension: verification`, `ruleId: VERIFY`,
  a stable id `ver-<8 hex>` per claim) for the next round, handed to the dimensions that raised it as their own
  finding to resolve (`previous`); at the last round the item stops. `unverifiable` → the item stops
  `NEEDS_HUMAN`; the owner accepts the claims they read by id, resuming the branch with
  `acceptUnverifiable: ["ver-…"]`, and those ship listed as accepted, never as verified; a claim the resumed panel
  raises anew has another id and stops the item again (a flag would accept it unread). After an acceptance fix the
  same check runs on the delta round, with no fix round left. A resumed panel starts again at round 1 and words
  its claims anew, so an accepted id may not come back: the acceptance then stops the item once more instead of
  passing it (fails closed; review round 3). `record.verification` reaches the PR ("Unverified
  claims", `ship-record`). `/review` runs the same stage after its panel (Step 5).
- **A structural check** (`.github/scripts/check-web-access.sh`, in Factory guards) keeps the wiring: every
  agent declares `tools:`; only the verifier has WebFetch, none WebSearch; the verifier has only Read and
  WebFetch, its read scope on a Read hook, and its definition names exactly the hook's prefixes; both settings
  run the hook in their mode and the session-level read scope; the autopilot allows only the list's
  hosts, each of them; the owner's settings allow no WebFetch; the firewall opens every host of the list.

## Alternatives considered

- **Permission rules only (E1)**: `WebFetch(domain:…)` in `settings.autopilot.json`. Host-only (a query string or
  any path of `github.com` passes), for every agent, and WebFetch's built-in preapproved documentation domains
  need no rule in `dontAsk`. It cannot say "only the verifier".
- **A separate `claude -p` process for the verifier (E3)**, with its own settings, keeping the bare deny in the
  autopilot session. The verdicts live in the workflow's memory and would need a new path to a file, a nested
  authenticated `claude`, and a cost cap outside the agent budget (`MAX_AGENT_CALLS`). Kept as the fallback if
  `agent_type` stops reaching session hooks.
- **Network or Maven for the reviewers**: running `mvn` runs the plugins a reviewed POM declares, and a reviewer
  reads the untrusted diff, so a fetch could leak what it read. Rejected in #0-160 itself.
- **A wider allow-list** (`nvd.nist.gov`, `security.snyk.io`, `docs.spring.io`): every host is opened in the
  firewall for the whole container, not for the verifier alone; OSV already aggregates NVD and GHSA with
  fixed-version ranges. Added only when a claim needs it.

## Consequences

- **A weaker layer, named**: before, WebFetch was absent from autopilot sessions; now a hook decides who uses it.
  A bug in the hook would let any agent fetch from the six hosts (any path, any query), since the allow rules
  are by host, and so would a hook that fails with any exit code but 2 (a non-blocking error): both new hooks
  refuse with 2 when they cannot load their helper (the older hooks do not yet: #0-162); `test-hooks.sh` (AC4 cases) and `check-web-access.sh` are what keep it, and README
  "Infrastructure Hardening" says so.
- **Still a channel**: a URL path can carry data to an allowed host (`osv.dev/vulnerability/<data>`). The
  verifier reads only the gate's artifact and dependency tree (no secret, as long as the gate wrote them: the
  branch's code runs during the gate and could leave a file of that name, as text, beforehand — a second hard
  link is refused, a plain file it wrote is not, and that code can already send data out itself, #0-116), so it
  has little to send. The claims it gets are written by reviewers who read
  the diff, so the change can steer them indirectly; the verifier treats them as data, a refuted claim only
  blocks, and nothing it says approves a change by itself.
- The new firewall hosts are open to every process in the devcontainer, and by IP (2026-10-24: `api.osv.dev` is
  a CNAME of `ghs.googlehosted.com`, Google's shared front end; `osv.dev` a Google Cloud load balancer address;
  `spring.io` on Cloudflare, `tomcat.apache.org` on Fastly), so other sites on those addresses are reachable by
  their own name too (#0-116 still applies to code the agents run; GitHub is a wider channel already).
- The hook checks the URL of the call, not where a redirect within the same host leads, which WebFetch follows
  itself; a redirect to another host comes back as a second call, which the hook checks. No redirect from the
  listed prefixes to a path anyone else controls is known.
- Cost: at most one verifier call per approving round, inside `MAX_AGENT_CALLS`; none when no claim is open.
- A reviewer recognises a violation as: WebFetch or WebSearch in any other agent's `tools:`, an agent
  definition without `tools:`, a `WebFetch` allow rule in `settings.json` or one off the list in
  `settings.autopilot.json`, a list entry without a path prefix, a claim reaching a PR without a verdict, or the
  verifier's prompt carrying the diff, or its read scope wider than the gate's files.
