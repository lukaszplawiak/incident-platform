export const meta = {
  name: 'backlog-autopilot',
  description: 'Takes one ready backlog item from pick to pull request: plan, implement, test gate, review panel, acceptance, ship. Shadow mode by default.',
  phases: ['Preflight', 'Pick', 'Plan', 'Implement', 'Review', 'Acceptance', 'Ship'],
}

// ============================================================================================
// Backlog autopilot — one item per run. Run it in an autopilot session:
//   claude --settings .claude/settings.autopilot.json      then   /backlog-autopilot
// Which item: decided in code by scripts/factory/next-item.sh — a ready follow-up of a done item first,
// then the approved queue (.ai/plan/queue.md), strictly row by row, or priority order while there is no
// queue. One item at a time; the autopilot never runs two.
// Rules: the implementer always reads the core rule files (general, architecture, security); the
// architect's plan selects the rules of the other files that apply; after implementing, a rule file the
// diff calls for (changed-paths.sh "ruleFiles") from which the plan listed no rule triggers a self-check
// before the panel sees the change. The panel always judges against its full files.
// Scope: the item's Touches, the architect's planned modules and the areas the diff reached are compared
// and recorded in the PR (.ai/rules/planning.md, "Touches") — a measurement, never a gate.
// Args (all optional):
//   item:     '#0-25'           take this item instead (it must still be `ready` on main, deps done, no lock)
//   branch:   'fix/0-25-…'      resume an existing branch (after a BLOCKED item was resolved, or a
//                               branch from scripts/factory-admin/local-implement.sh): skips pick/plan/implement
//   shadow:   true (default)    open the PR, never merge. Set false only after backlog #0-113.
//   maxRounds: 3                review rounds before the item is BLOCKED
//   localReview: false          advisory second opinion from a local model (scripts/factory/local-review.sh)
//
// The script decides; agents only do. Every branch below is code, every limit is a constant, and a
// missing or malformed answer from an agent stops the item (fail closed) instead of counting as "OK".
// The script has no shell: deterministic steps run through the `factory-ops` agent, which may only run
// scripts/factory/*.sh and returns their JSON unchanged.
//
// VERIFY BEFORE THE FIRST RUN (run /workflow-authoring to load the script reference):
//   1. AGENT_TYPE_OPTION — the agent() option that selects a custom agent from .claude/agents/. The public
//      docs checked on 2026-10-05 mention "agent type" but not the option's name. The isolation check in
//      Preflight stops the run if the option is wrong (a reviewer that can edit files is not a reviewer).
//   2. parallel() — that it takes an array of functions returning agent() promises, as used in runAll().
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
const SHADOW = args?.shadow !== false
const MAX_ROUNDS = Math.min(Math.max(args?.maxRounds ?? 3, 1), 3)
const MAX_TEST_FIXES = 2          // fix-tests attempts per gate before the item is BLOCKED
const MAX_AGENT_CALLS = 45        // budget per item; a runaway item stops instead of eating the limit

const PANEL_ALWAYS = ['general', 'architecture', 'security', 'performance', 'docs']
const CORE_RULE_FILES = ['general', 'architecture', 'security']          // the implementer always reads these in full
const RULE_FILE_OF = { GEN: 'general', ARC: 'architecture', SEC: 'security', PERF: 'performance', MIG: 'migration', K8S: 'k8s', DOC: 'docs' }

let calls = 0                     // work agents only; ops, shipper and notifications are not budgeted
let budgetExceeded = false
let runId = 'unknown'
const base = { ref: 'origin/main', sha: null }
const record = { item: null, source: null, implementer: null, plannedRules: [], selfCheck: null, scope: null,
  rounds: [], acceptance: null, tests: [], nonBlocking: [], adrs: [], local: null }

// ---------------------------------------------------------------- helpers

const UNBUDGETED = new Set(['factory-ops', 'shipper'])

function as(type, prompt, opts = {}) {
  if (!UNBUDGETED.has(type)) {
    calls += 1
    if (calls > MAX_AGENT_CALLS) { budgetExceeded = true; return Promise.resolve(null) }
  }
  const preamble = `You are running as the \`${type}\` agent defined in .claude/agents/${type}.md. ` +
    `If your instructions are not that definition, read the file and follow it exactly. Autopilot run ${runId}.\n\n`
  return agent(preamble + prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })
}

function runAll(thunks) {
  return parallel(thunks)
}

// Arguments reach a shell: only ids, refs and shas that match a strict pattern are passed, quoted.
const SAFE = /^[A-Za-z0-9._\/:-]+$/
function q(value) {
  const s = String(value)
  if (!SAFE.test(s)) throw new Error(`unsafe argument for a factory script: ${s}`)
  return `'${s}'`
}
function ops(script, argv, schema, label) {
  let command
  try {
    command = [`scripts/factory/${script}`, ...argv.map(q)].join(' ')
  } catch (e) {
    log(`refused to call ${script}: ${e.message}`)
    return Promise.resolve(null)          // callers treat null as "no result" and stop the item
  }
  return as('factory-ops', `Run exactly: ${command}`, { schema, label: label ?? script })
}

const ANY_OBJECT = { type: 'object' }

const PREFLIGHT = {
  type: 'object', required: ['ok', 'reasons'],
  properties: { ok: { type: 'boolean' }, reasons: { type: 'array', items: { type: 'string' } },
    baseRef: { type: 'string' }, baseSha: { type: 'string' }, runId: { type: 'string' }, today: { type: 'string' } },
}
const ISOLATION = {
  type: 'object', required: ['tools', 'definitionLoaded'],
  properties: { tools: { type: 'array', items: { type: 'string' } }, definitionLoaded: { type: 'boolean' } },
}
const PICK = {
  type: 'object', required: ['none'],
  properties: { none: { type: 'boolean' }, reason: { type: 'string' }, itemId: { type: 'string' }, title: { type: 'string' },
    type: { type: 'string' }, risk: { type: 'string' }, complexity: { type: 'string' }, branch: { type: 'string' },
    workDir: { type: 'string' }, acceptanceCriteria: { type: 'array', items: { type: 'string' } } },
}
const PLAN = {
  type: 'object', required: ['blocked'],
  properties: { blocked: { type: 'boolean' }, reason: { type: ['string', 'null'] }, plan: ANY_OBJECT,
    adrs: { type: 'array', items: { type: 'string' } }, risks: { type: 'array', items: { type: 'string' } },
    areas: ANY_OBJECT, modules: { type: 'array', items: { type: 'string' } },
    rules: { type: 'array', items: { type: 'object', required: ['id'], properties: { id: { type: 'string' }, why: { type: 'string' } } } } },
}
const IMPL = {
  type: 'object', required: ['blocked', 'headSha'],
  properties: { blocked: { type: 'boolean' }, reason: { type: ['string', 'null'] }, headSha: { type: 'string' },
    commits: { type: 'array', items: { type: 'string' } }, summary: { type: 'string' },
    fixed: { type: 'array', items: { type: 'string' } },
    disputed: { type: 'array', items: ANY_OBJECT }, noticedNotTouched: { type: 'array', items: { type: 'string' } } },
}
const TESTS = {
  type: 'object', required: ['status'],
  properties: { status: { type: 'string', enum: ['pass', 'fixable', 'stop'] }, exitCode: { type: 'number' },
    modules: { type: 'string' }, log: { type: 'string' }, head: { type: 'string' }, summary: { type: 'array', items: { type: 'string' } } },
}
const PATHS = {
  type: 'object', required: ['needsHuman', 'files', 'areas', 'ruleFiles'],
  properties: { files: { type: 'array', items: { type: 'string' } }, protectedTouched: { type: 'array', items: { type: 'string' } },
    migrationTouched: { type: 'boolean' }, editedAppliedMigrations: { type: 'array', items: { type: 'string' } },
    k8sOrBuildTouched: { type: 'boolean' }, pomSupplyChainChange: { type: 'boolean' },
    deletedTests: { type: 'array', items: { type: 'string' } }, disabledTestsAdded: { type: 'number' },
    docsTouched: { type: 'boolean' }, needsHuman: { type: 'boolean' }, mergeBase: { type: 'string' }, head: { type: 'string' },
    modules: { type: 'array', items: { type: 'string' } }, areas: { type: 'array', items: { type: 'string' } },
    ruleFiles: { type: 'array', items: { type: 'string' } } },
}
const FINDING = {
  type: 'object', required: ['id', 'ruleId', 'issue', 'impact'],
  properties: { id: { type: 'string' }, ruleId: { type: 'string' }, file: { type: 'string' }, line: { type: ['number', 'null'] },
    issue: { type: 'string' }, impact: { type: 'string' }, fix: { type: 'string' }, isRegression: { type: 'boolean' } },
}
const VERDICT = {
  type: 'object', required: ['dimension', 'verdict', 'blocking', 'nonBlocking', 'resolved', 'upheldDisputes'],
  properties: {
    dimension: { type: 'string' }, round: { type: 'number' },
    verdict: { type: 'string', enum: ['APPROVE', 'CHANGES_REQUESTED', 'NEEDS_HUMAN'] },
    blocking: { type: 'array', items: FINDING }, nonBlocking: { type: 'array', items: ANY_OBJECT },
    resolved: { type: 'array', items: { type: 'string' } }, upheldDisputes: { type: 'array', items: ANY_OBJECT },
    outOfScope: { type: 'array', items: { type: 'string' } }, ruleGap: { type: 'array', items: { type: 'string' } },
    unverified: { type: 'array', items: { type: 'string' } },
  },
}
const ACCEPT = {
  type: 'object', required: ['verdict', 'criteria'],
  properties: { item: { type: 'string' }, verdict: { type: 'string', enum: ['ACCEPT', 'REJECT', 'NEEDS_HUMAN'] },
    criteria: { type: 'array', items: ANY_OBJECT }, scopeCreep: { type: 'array', items: { type: 'string' } },
    reason: { type: 'string' } },
}
const SHIP = {
  type: 'object', required: ['mode'],
  properties: { mode: { type: 'string' }, prNumber: { type: ['number', 'null'] }, prUrl: { type: ['string', 'null'] },
    merged: { type: 'string' }, labelsMissing: { type: 'array', items: { type: 'string' } },
    followUpsCreated: { type: 'array', items: { type: 'string' } }, error: { type: ['string', 'null'] } },
}
const NEXT = {
  type: 'object', required: ['ok'],
  properties: { ok: { type: 'boolean' }, reasons: { type: 'array', items: { type: 'string' } },
    item: { type: ['string', 'null'] }, source: { type: 'string' }, eligible: { type: 'boolean' },
    reason: { type: ['string', 'null'] }, touches: { type: 'string' }, modules: { type: 'array', items: { type: 'string' } },
    followUpOf: { type: ['string', 'null'] }, position: { type: ['number', 'null'] }, skipped: { type: 'array', items: ANY_OBJECT },
    risk: { type: 'string' }, complexity: { type: 'string' } },
}

async function notStarted(reasons, outcome = 'NOT_STARTED') {
  await ops('state.sh', ['unlock'], ANY_OBJECT, 'state')
  await ops('return-to-main.sh', [], ANY_OBJECT, 'back to main')
  return outcome === 'NOT_STARTED' ? { outcome, reasons } : { outcome, reason: reasons.join('; ') }
}

async function notify(event, detail) {
  // Event and detail are identifiers only (no free text reaches the shell).
  await ops('notify.sh', [event, detail ?? '-'], ANY_OBJECT, 'notify')
}

async function stopItem(item, reason) {
  if (budgetExceeded) reason = `agent budget of ${MAX_AGENT_CALLS} calls exceeded — split the item (last step: ${reason})`
  log(`BLOCKED ${item?.itemId ?? ''}: ${reason}`)
  if (item?.branch) {
    await as('shipper', [
      `Mode: blocked. Item ${item.itemId} ("${item.title}"), branch ${item.branch}, run ${runId}.`,
      `Reason: ${reason}`,
      `Review record so far (JSON): ${JSON.stringify(record)}`,
    ].join('\n'), { schema: SHIP })
    await ops('state.sh', ['record', 'blocked', item.id], ANY_OBJECT, 'state')
  } else {
    await ops('state.sh', ['unlock'], ANY_OBJECT, 'state')
    await ops('return-to-main.sh', [], ANY_OBJECT, 'back to main')
  }
  await notify('blocked', item?.id ?? 'run')
  return { outcome: 'BLOCKED', item: item?.itemId ?? null, reason, agentCalls: calls }
}

// Rule ids the architect selected → the rule files they come from (GEN-03 → general).
function ruleFilesOf(rules) {
  return [...new Set((rules ?? []).map((r) => RULE_FILE_OF[String(r.id ?? '').split('-')[0]]).filter(Boolean))]
}

// Three predictions of the same reach, made at different times from different knowledge: the item's
// Touches (at /ready), the architect's modules (just before implementing), the diff (after). A prediction
// "holds" when the diff stays within it; predicting more than happened is not a miss. Which prediction
// failed says which stage was off (.ai/rules/audit.md, "Signals"). Measured, never a gate.
function scopeOf(touches, planned, actual) {
  const set = (x) => (Array.isArray(x) && x.length ? [...new Set(x)].sort() : null)
  const t = set(touches), p = set(planned), a = set(actual)
  const outside = (pred) => (pred && a ? a.filter((m) => !pred.includes(m)) : [])
  const holds = (pred) => outside(pred).length === 0
  const same = (x, y) => x.length === y.length && x.every((v, i) => v === y[i])
  let category
  if (!a || (!t && !p)) category = 'not-measured'            // the diff reached no module, or nothing was predicted
  else if (!t) category = holds(p) ? 'within-plan' : 'diff-outside-plan'
  else if (!p) category = holds(t) ? 'within-touches' : 'diff-outside-touches'
  else if (holds(t) && holds(p)) category = 'consistent'
  else if (holds(p)) category = 'backlog-estimate-off'        // the architect corrected the estimate
  else if (holds(t)) category = 'plan-off'                    // the plan narrowed it wrongly; the estimate held
  else if (same(t, p)) category = 'implementation-drift'      // both predictions agreed, the diff went beyond
  else category = 'unclear-item'                              // the predictions disagreed and both missed
  return { touches: t, plan: p, actual: a ?? [], category, outsideTouches: outside(t), outsidePlan: outside(p) }
}

function valid(v) {
  return v && typeof v.verdict === 'string' && Array.isArray(v.blocking) && Array.isArray(v.resolved)
    && Array.isArray(v.upheldDisputes) && v.blocking.every((f) => f.id && f.ruleId && f.impact)
}

// Test gate: run, let the implementer fix a fixable failure up to MAX_TEST_FIXES times.
async function testGate(item, round) {
  for (let attempt = 0; attempt <= MAX_TEST_FIXES; attempt++) {
    const t = await ops('run-tests.sh', [item.id, `${round}-${attempt}`, base.ref], TESTS, `tests r${round}`)
    if (!t) return { ok: false, reason: 'test gate returned no result' }
    if (t.status === 'stop') return { ok: false, reason: `test gate stopped (log ${t.log}): ${(t.summary ?? []).slice(-3).join(' | ')}` }
    const head = await ops('changed-paths.sh', [base.ref], PATHS, 'head check')
    if (!head || !t.head || t.head !== head.head) return { ok: false, reason: `the tested commit (${t.head}) is not the branch HEAD (${head?.head}): something changed the tree during the gate` }
    record.tests.push({ round, attempt, status: t.status, modules: t.modules })
    if (t.status === 'pass') return { ok: true, head: t.head }
    if (attempt === MAX_TEST_FIXES) break
    const fix = await as(IMPL_TYPE, [
      `Mode: fix-tests. Item ${item.itemId}, branch ${item.branch}, round ${round}.`,
      `The test gate failed (log ${t.log}). Summary:\n${(t.summary ?? []).join('\n')}`,
    ].join('\n'), { schema: IMPL, label: `fix tests r${round}` })
    if (!fix || fix.blocked) return { ok: false, reason: fix?.reason ?? 'implementer gave no answer while fixing tests' }
  }
  return { ok: false, reason: `tests still failing after ${MAX_TEST_FIXES} fix attempts` }
}

// Paths only the owner may change, edited migrations, removed tests, POM supply chain.
async function pathGate(previousSha) {
  const p = await ops('changed-paths.sh', previousSha ? [base.ref, previousSha] : [base.ref], PATHS, 'changed paths')
  if (!p) return { ok: false, reason: 'changed-paths returned no result' }
  if (p.needsHuman) {
    const why = [
      p.protectedTouched?.length ? `human-owned paths changed: ${p.protectedTouched.join(', ')}` : null,
      p.editedAppliedMigrations?.length ? `applied migrations edited: ${p.editedAppliedMigrations.join(', ')}` : null,
      p.pomSupplyChainChange ? 'POM adds a plugin, repository or dependency' : null,
      p.deletedTests?.length ? `tests deleted: ${p.deletedTests.join(', ')}` : null,
      p.disabledTestsAdded ? `${p.disabledTestsAdded} @Disabled added` : null,
      p.readyFlagAdded ? 'a backlog item was marked ready' : null,
      p.buildConfigChanged?.length ? `build configuration changed: ${p.buildConfigChanged.join(', ')}` : null,
      p.claudeMdOutsideBlocks ? 'CLAUDE.md changed outside its agent-editable blocks' : null,
    ].filter(Boolean).join('; ')
    return { ok: false, reason: `NEEDS_HUMAN — ${why}`, paths: p }
  }
  return { ok: true, paths: p }
}

async function reviewRound(item, round, dimensions, mergeBase, previousSha, previous) {
  const answers = await runAll(dimensions.map((dim) => () => as(`review-${dim}`, [
    `Autopilot review. Item ${item.itemId} ("${item.title}"), branch ${item.branch}.`,
    `mergeBase: ${mergeBase}. round: ${round}.`,
    round > 1 ? `previousRoundSha: ${previousSha}. Your blocking findings of the previous round: ${JSON.stringify(previous[dim] ?? [])}` : '',
    `The test gate passed for this exact HEAD (./mvnw verify, affected modules, JaCoCo check).`,
    `Acceptance criteria of the item:\n${(item.acceptanceCriteria ?? []).join('\n')}`,
    `Answer with the verdict JSON only.`,
  ].filter(Boolean).join('\n'), { schema: VERDICT, label: `${dim} r${round}` })))
  // The dimension of each verdict is the one we asked for (by position), never what the reviewer wrote.
  const verdicts = answers.map((v, i) => (v && v.dimension === dimensions[i] ? v : null))
  record.rounds.push({ round, dimensions, verdicts })
  return verdicts
}

// ---------------------------------------------------------------- run

phase('Preflight')
const pre = await ops('preflight.sh', [], PREFLIGHT, 'preflight')
if (!pre) return { outcome: 'NOT_STARTED', reasons: ['preflight returned no result (is factory-ops loading? check AGENT_TYPE_OPTION)'] }
if (!pre.ok) {
  await notify('not-started', '-')
  return { outcome: 'NOT_STARTED', reasons: pre.reasons }
}
runId = pre.runId
// Every comparison uses the commit the preflight recorded, never a ref the session could move.
if (!/^[0-9a-f]{40}$/.test(pre.baseSha ?? '')) return { outcome: 'NOT_STARTED', reasons: ['preflight returned no base commit'] }
base.ref = pre.baseSha
base.sha = pre.baseSha

// A reviewer that can edit files is not a reviewer: if the custom agent type did not apply, stop here.
const iso = await as('review-general',
  'Isolation check, no review: list the names of the tools available to you in this session, and say whether your instructions are the review-general agent definition. Answer JSON only.',
  { schema: ISOLATION, label: 'isolation check' })
if (!iso || iso.definitionLoaded !== true || iso.tools.some((t) => /^(Edit|Write|MultiEdit|NotebookEdit)$/.test(t))) {
  await ops('state.sh', ['unlock'], ANY_OBJECT, 'state')
  return { outcome: 'NOT_STARTED', reasons: [`agent isolation check failed (tools: ${iso?.tools?.join(', ') ?? 'no answer'}): set AGENT_TYPE_OPTION in .claude/workflows/backlog-autopilot.js`] }
}

let item
let next = null
let plannedModules = null      // the architect's prediction of the modules the change reaches
phase('Pick')
if (!args?.branch) {
  // The choice is code: next-item.sh reads the queue, the backlog and the locks on the base commit.
  const wanted = args?.item ? String(args.item).replace(/^#/, '') : null
  if (wanted !== null && !/^0-\d+$/.test(wanted)) return notStarted([`args.item must look like #0-25, got ${String(args.item).slice(0, 40)}`])
  next = await ops('next-item.sh', wanted ? [base.sha, wanted] : [base.sha], NEXT, 'next item')
  if (!next) return notStarted(['next-item returned no result'])
  if (!next.ok) return notStarted(next.reasons ?? ['next-item refused'])
  if (!next.item || next.eligible === false) {
    const skipped = (next.skipped ?? []).map((s) => `${s.item}: ${s.why}`).join('; ')
    return notStarted([`${next.reason ?? 'nothing can start'}${skipped ? ` (${skipped})` : ''}`], 'NOTHING_TO_DO')
  }
  if (!/^#0-\d+$/.test(next.item)) return notStarted([`next-item returned a malformed item: ${String(next.item).slice(0, 40)}`])
  record.source = next.source === 'queue' ? `queue row ${next.position}` : next.source === 'follow-up' ? `follow-up of #${next.followUpOf}` : next.source
  log(`next item ${next.item} (${record.source})`)
}
if (args?.branch) {
  item = await as('picker', [
    `Resume mode: do not create a branch. Switch to the existing branch ${args.branch} (git switch ${args.branch}) for item ${args.item ?? '(read it from .ai/work/*/progress.md on that branch)'}.`,
    `Validate the item exactly as in "Choose the item" except the lock check (its own draft PR may be open), and answer the same JSON with that branch.`,
    `Base ref ${base.ref} (${base.sha}). Run ${runId}.`,
  ].join('\n'), { schema: PICK })
} else {
  item = await as('picker', `Base ref ${base.ref} (${base.sha}). Run ${runId}. Take item ${next.item} (chosen by next-item.sh: ${record.source}).`, { schema: PICK })
}
if (!item) return stopItem(null, 'picker gave no answer')
if (item.none) {
  await ops('state.sh', ['unlock'], ANY_OBJECT, 'state')
  await ops('return-to-main.sh', [], ANY_OBJECT, 'back to main')
  return { outcome: 'NOTHING_TO_DO', reason: item.reason }
}
// Fail closed on an incomplete answer; `id` is the bare number used in script arguments and paths.
item.id = String(item.itemId ?? '').replace(/^#/, '')
if (!/^0-\d+$/.test(item.id) || !item.branch || !SAFE.test(item.branch) || !Array.isArray(item.acceptanceCriteria) || !item.acceptanceCriteria.length) {
  return stopItem(null, `picker answer incomplete or malformed (no state recorded, check the branches): ${JSON.stringify(item).slice(0, 300)}`)
}
if (next && item.itemId !== next.item) {
  return stopItem(null, `picker prepared ${item.itemId} instead of ${next.item} (no state recorded, check the branches)`)
}
record.item = item.itemId
// Complexity: low → the lighter implementer (same definition, Sonnet); anything else, or missing → Opus.
// Read from the base commit by next-item.sh when it chose the item; the picker's copy only on resume.
const isLow = (v) => String(v ?? '').trim().toLowerCase() === 'low'
const IMPL_TYPE = (next ? isLow(next.complexity) && isLow(item.complexity) : isLow(item.complexity)) ? 'implementer-light' : 'implementer'
record.implementer = IMPL_TYPE

if (!args?.branch) {
  phase('Plan')
  const plan = await as('architect', [
    `Plan item ${item.itemId} ("${item.title}") on branch ${item.branch}. Work dir ${item.workDir}. Risk ${item.risk}, complexity ${item.complexity}.`,
    `Acceptance criteria:\n${(item.acceptanceCriteria ?? []).join('\n')}`,
  ].join('\n'), { schema: PLAN })
  if (!plan) return stopItem(item, 'architect gave no answer')
  if (plan.blocked) return stopItem(item, `architect: ${plan.reason}`)
  record.adrs = plan.adrs ?? []
  record.plannedRules = (plan.rules ?? []).map((r) => r.id).filter(Boolean)
  plannedModules = plan.modules ?? null

  phase('Implement')
  const impl = await as(IMPL_TYPE, [
    `Mode: implement. Item ${item.itemId} ("${item.title}"), branch ${item.branch}, work dir ${item.workDir}.`,
    `Plan (also in progress.md): ${JSON.stringify(plan.plan)}`,
    `Rules the architect selected for this change (read each one; the core files ${CORE_RULE_FILES.join(', ')} are yours to read in full anyway): ${JSON.stringify(plan.rules ?? [])}`,
    `Acceptance criteria:\n${(item.acceptanceCriteria ?? []).join('\n')}`,
  ].join('\n'), { schema: IMPL })
  if (!impl) return stopItem(item, 'implementer gave no answer')
  if (impl.blocked) return stopItem(item, `implementer: ${impl.reason}`)

  // Safety net for a plan that missed an area: a rule file the diff calls for, from which the plan listed
  // no rule at all (and that is not core), is read in full and checked by the implementer before the
  // panel sees the change. Once, after implementing; later fix rounds are judged by the panel.
  const reach = await ops('changed-paths.sh', [base.ref], PATHS, 'rules for the diff')
  if (!reach || !Array.isArray(reach.ruleFiles)) return stopItem(item, 'changed-paths returned no rule files (rules for the diff)')
  const covered = new Set([...CORE_RULE_FILES, ...ruleFilesOf(plan.rules)])
  const missing = (reach.ruleFiles ?? []).filter((f) => !covered.has(f))
  record.selfCheck = missing
  if (missing.length) {
    const check = await as(IMPL_TYPE, [
      `Mode: self-check. Item ${item.itemId}, branch ${item.branch}.`,
      `Your diff reaches areas from whose rule files the plan listed nothing. Read these rule files in full and check your change against every rule in them: ${missing.map((f) => `.ai/rules/review/${f}.md`).join(', ')}.`,
      `Fix what violates a rule (commit), and record the result in progress.md as your rules say.`,
    ].join('\n'), { schema: IMPL, label: `self-check ${missing.join(',')}` })
    if (!check) return stopItem(item, 'implementer gave no answer to the self-check')
    if (check.blocked) return stopItem(item, `implementer (self-check): ${check.reason}`)
  }
}

phase('Review')
let previousSha = null
let lastHead = null           // HEAD the panel last reviewed
let previous = {}            // dimension -> blocking findings of the previous round
let lastIds = null
let mergeBase = null
let dimensions = null
let approved = false
let lastPaths = null          // the path gate's view of the last reviewed HEAD

for (let round = 1; round <= MAX_ROUNDS; round++) {
  // Path gate first: a plugin or build change on the branch must be flagged before `mvn verify` runs it.
  const pg = await pathGate(previousSha)
  if (!pg.ok) return stopItem(item, pg.reason)
  const gate = await testGate(item, round)
  if (!gate.ok) return stopItem(item, gate.reason)
  mergeBase = pg.paths.mergeBase
  lastPaths = pg.paths
  const head = pg.paths.head

  if (round === 1) {
    dimensions = [...PANEL_ALWAYS]
    if (pg.paths.migrationTouched) dimensions.push('migration')
    if (pg.paths.k8sOrBuildTouched) dimensions.push('k8s')
    if (args?.localReview) {
      record.local = await ops('local-review.sh', [item.id, base.ref], ANY_OBJECT, 'local second opinion')
    }
  }

  lastHead = head
  const verdicts = await reviewRound(item, round, dimensions, mergeBase, previousSha, previous)
  if (verdicts.some((v) => !valid(v))) return stopItem(item, `a reviewer returned no valid verdict in round ${round} (fail closed)`)
  verdicts.forEach((v) => {
    record.nonBlocking.push(...(v.nonBlocking ?? []).map((f) => ({ dimension: v.dimension, ...f })))
    record.nonBlocking.push(...(v.outOfScope ?? []).map((s) => ({ dimension: v.dimension, outOfScope: s })))
  })

  const needsHuman = verdicts.filter((v) => v.verdict === 'NEEDS_HUMAN')
  if (needsHuman.length) return stopItem(item, `NEEDS_HUMAN from ${needsHuman.map((v) => v.dimension).join(', ')}`)
  const upheld = verdicts.filter((v) => v.upheldDisputes.length)
  if (upheld.length) return stopItem(item, `dispute upheld by ${upheld.map((v) => v.dimension).join(', ')}: the owner decides`)

  const failing = verdicts.filter((v) => v.verdict === 'CHANGES_REQUESTED' || v.blocking.length)
  if (failing.length === 0) { approved = true; break }

  const ids = failing.flatMap((v) => v.blocking.map((f) => f.id)).sort().join(',')
  if (ids === lastIds) return stopItem(item, `no progress: the same blocking findings (${ids}) came back after a fix round`)
  lastIds = ids
  if (round === MAX_ROUNDS) break

  const fix = await as(IMPL_TYPE, [
    `Mode: fix-review. Item ${item.itemId}, branch ${item.branch}, round ${round}.`,
    `Fix exactly these blocking findings, by id, nothing else (dispute in handoff.md if you disagree):`,
    JSON.stringify(failing.flatMap((v) => v.blocking.map((f) => ({ dimension: v.dimension, ...f })))),
  ].join('\n'), { schema: IMPL, label: `fix r${round}` })
  if (!fix) return stopItem(item, 'implementer gave no answer to the review findings')
  if (fix.blocked) return stopItem(item, `implementer: ${fix.reason}`)

  previousSha = head
  previous = Object.fromEntries(failing.map((v) => [v.dimension, v.blocking]))
  // Round 2+: the reviewers that blocked, plus security and general on every delta — a fix for one
  // dimension can break another, and a tenant leak in a fix must not ship as an outOfScope sentence.
  dimensions = [...new Set([...failing.map((v) => v.dimension), 'security', 'general'])]
}
if (!approved) return stopItem(item, `review did not converge in ${MAX_ROUNDS} rounds`)

phase('Acceptance')
let acc = await as('acceptance-reviewer', [
  `Autopilot acceptance. Item ${item.itemId} ("${item.title}"), branch ${item.branch}, mergeBase ${mergeBase}, base ref ${base.ref}.`,
  `Risk: ${item.risk}. Answer with the acceptance JSON only.`,
].join('\n'), { schema: ACCEPT })
if (!acc) return stopItem(item, 'acceptance-reviewer gave no answer')

if (acc.verdict === 'REJECT') {
  const fix = await as(IMPL_TYPE, [
    `Mode: fix-acceptance. Item ${item.itemId}, branch ${item.branch}.`,
    `Unmet criteria: ${JSON.stringify(acc.criteria.filter((c) => !c.met))}`,
  ].join('\n'), { schema: IMPL, label: 'fix acceptance' })
  if (!fix || fix.blocked) return stopItem(item, fix?.reason ?? 'implementer gave no answer to the acceptance review')
  const pg = await pathGate(lastHead)
  if (!pg.ok) return stopItem(item, pg.reason)
  lastPaths = pg.paths
  const gate = await testGate(item, 'acc')
  if (!gate.ok) return stopItem(item, gate.reason)
  // The fix changed code after the panel approved: every reviewer of round 1 sees that delta once,
  // under round-3 rules (only correctness and security can still block).
  const round1 = record.rounds[0]?.verdicts?.map((v) => v.dimension) ?? PANEL_ALWAYS
  const delta = await reviewRound(item, 3, round1, mergeBase, lastHead, {})
  if (delta.some((v) => !valid(v) || v.verdict !== 'APPROVE')) return stopItem(item, 'the acceptance fix did not pass a final review round')
  acc = await as('acceptance-reviewer', `Autopilot acceptance, second and last time. Item ${item.itemId}, branch ${item.branch}, mergeBase ${mergeBase}. Answer with the acceptance JSON only.`, { schema: ACCEPT })
  if (!acc || acc.verdict === 'REJECT') return stopItem(item, 'acceptance rejected twice')
}
record.acceptance = acc

// Touches, plan and diff, compared: information for the PR and the audit, never a gate.
record.scope = scopeOf(next?.modules, plannedModules, lastPaths?.areas)

// Anything but an explicit "low" — in the backlog on main (next-item.sh) and in the picker's answer — is high.
const riskHigh = !isLow(item.risk) || (next !== null && !isLow(next.risk))
if (acc.verdict === 'NEEDS_HUMAN' && !riskHigh) return stopItem(item, `acceptance NEEDS_HUMAN: ${acc.reason}`)

phase('Ship')
const merge = !SHADOW && !riskHigh && acc.verdict === 'ACCEPT'
const shipped = await as('shipper', [
  `Mode: ship. Item ${item.itemId} ("${item.title}"), branch ${item.branch}, run ${runId}.`,
  `shadow: ${SHADOW}. risk-high: ${riskHigh}. merge: ${merge}.`,
  `Review record (JSON, put it in the PR as the ship-record skill says): ${JSON.stringify(record)}`,
].join('\n'), { schema: SHIP })
if (!shipped || !shipped.prUrl) return stopItem(item, `shipper failed: ${shipped?.error ?? 'no answer'}`)
if (shipped.error) {
  // The PR exists; do not run blocked mode on a published PR. Record it and leave the decision to the owner.
  await ops('state.sh', ['record', 'blocked', item.id, shipped.prUrl], ANY_OBJECT, 'state')
  await notify('ship-error', String(shipped.prNumber ?? item.id))
  return { outcome: 'SHIPPED_WITH_ERROR', item: item.itemId, pr: shipped.prUrl, error: shipped.error, agentCalls: calls }
}

await ops('state.sh', ['record', 'shipped', item.id, shipped.prUrl], ANY_OBJECT, 'state')
await notify(SHADOW || riskHigh ? 'pr-ready' : 'pr-auto-merge', String(shipped.prNumber ?? item.id))
// Follow-ups wait for the owner's /ready; tell the owner they exist.
const followUps = (shipped.followUpsCreated ?? []).map((f) => String(f).replace(/^#/, '')).filter((f) => /^0-\d+$/.test(f))
for (const f of followUps) await notify('follow-up-proposed', f)

return {
  outcome: 'SHIPPED', item: item.itemId, pr: shipped.prUrl, mode: SHADOW ? 'shadow' : riskHigh ? 'risk-high' : 'auto-merge',
  rounds: record.rounds.length, agentCalls: calls, nonBlocking: record.nonBlocking.length,
  picked: record.source, followUps: followUps.map((f) => `#${f}`), scope: record.scope,
}
