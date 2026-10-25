// ============================================================
// Tests the logic of .claude/workflows/backlog-autopilot.js (backlog #0-163): its helper functions one by one, and
// whole runs with a fake agent() that answers each agent and factory script as a scenario says.
// And how .claude/workflows/seed-bugs.js turns the seeder's answer into an outcome (backlog #0-170), reads its args and
// types the factory scripts' answers (#0-174).
//
// The workflow is not a module: Claude Code's workflow runtime evaluates its text as the body of an async function,
// with agent, parallel, phase, log and args in scope, and has no import (the workflow-authoring reference: "No
// filesystem or Node.js API access"). So this reads the file and evaluates it the same way, with stubs:
//   - helpers: the text up to the `// ---- run` marker, plus a `return { …the functions… }`, so each function can be
//     called with chosen inputs;
//   - runs: the whole text, whose `return` is the run's outcome.
// `export const meta` loses its `export`, and Date.now(), new Date() and Math.random() throw, as they do in the
// runtime (they would break a resume). A marker that moved or is not unique fails every test loudly.
//
// What it is not: a copy of the runtime. The stubs' behaviour (parallel() resolving a thunk that throws to null,
// Date and Math throwing) is what the workflow-authoring reference of Claude Code states, not something tested
// against the runtime, so a wrong reference would be repeated here; the workflow's own "VERIFY BEFORE THE FIRST
// RUN" still applies. The fake agent() does not validate an answer against its `schema` (the runtime does), and
// Node's globals (process, globalThis and the rest) stay reachable, so a workflow change that leans on either passes
// here and fails in the runtime. It tests the script's decisions, not its fit to the runtime. It records each call's
// schema, though: seed-bugs.js's are checked against the scripts' output with conforms() (#0-174, where an untyped
// schema let the agent answer in strings).
//
// Node's built-in modules only (no npm, ADR-0043). Run by Factory guards after .github/actions/setup-node; locally:
//   node .github/scripts/test-autopilot-workflow.mjs
// ============================================================
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FILE = process.env.WORKFLOW
  ?? join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.claude', 'workflows', 'backlog-autopilot.js')
const SRC = readFileSync(FILE, 'utf8')
const HELPERS = '// ---------------------------------------------------------------- helpers'
const RUN = '// ---------------------------------------------------------------- run'
const EXPORTED = [
  'scopeOf', 'valid', 'ruleFilesOf', 'claimKey', 'claimId', 'openClaims', 'verifyClaims', 'refutedFindings',
  'unsettledReason', 'q', 'record', 'CLAIM_ID', 'ACCEPT_UNVERIFIABLE', 'as', 'stopItem',
]

function once(marker) {
  const at = SRC.indexOf(marker)
  if (at < 0 || SRC.indexOf(marker, at + 1) >= 0) {
    throw new Error(`${FILE}: the marker "${marker}" must appear exactly once (this test reads the script by it)`)
  }
  return at
}
once(HELPERS)
const RUN_AT = once(RUN)
if (!SRC.startsWith('export const meta = ')) throw new Error(`${FILE}: must begin with "export const meta = "`)
const strip = (text) => text.replace(/^export const meta = /, 'const meta = ')

// What the runtime forbids, as it forbids it.
class RuntimeDate extends Date {
  constructor(...a) {
    if (!a.length) throw new Error('new Date() without arguments throws in the workflow runtime')
    super(...a)
  }
  static now() { throw new Error('Date.now() throws in the workflow runtime') }
}
const RuntimeMath = Object.create(Math, { random: { value() { throw new Error('Math.random() throws in the workflow runtime') } } })
const AsyncFunction = (async () => {}).constructor

function evaluate(body, { args, agent, logs }) {
  // parallel() resolves a thunk that throws to null and never rejects (the workflow-authoring reference).
  const parallel = (thunks) => Promise.all(thunks.map((t) => Promise.resolve().then(t).catch(() => null)))
  const fn = new AsyncFunction('agent', 'parallel', 'phase', 'log', 'args', 'Date', 'Math', body)
  return fn(agent, parallel, () => {}, (m) => logs.push(m), args, RuntimeDate, RuntimeMath)
}

// The helpers of a run started with `args`, and the agent calls they make.
async function helpers({ args = {}, answers = {} } = {}) {
  const fake = fakeAgent(answers)
  const fns = await evaluate(`${strip(SRC.slice(0, RUN_AT))}\nreturn { ${EXPORTED.join(', ')} }`, { args, agent: fake.agent, logs: [] })
  return { ...fns, calls: fake.calls }
}

// One whole run: its outcome, the agent calls in order and the log lines.
async function run({ args = {}, answers = {} } = {}) {
  const fake = fakeAgent(answers)
  const logs = []
  const outcome = await evaluate(strip(SRC), { args, agent: fake.agent, logs })
  return { outcome, calls: fake.calls, logs }
}

// ---------------------------------------------------------------- the fake agent

const SHA = (c) => c.repeat(40)
const BASE = SHA('a'), HEAD = SHA('c')

// A run that goes through: every agent and script answers as on a good day (low risk and complexity, one round).
const DEFAULTS = {
  'ops:preflight.sh': { ok: true, reasons: [], baseSha: BASE, runId: 'run-1' },
  isolation: { tools: ['Read', 'Grep', 'Glob', 'Bash'], definitionLoaded: true },
  'ops:next-item.sh': { ok: true, item: '#0-1', source: 'queue', position: 1, eligible: true, modules: ['incident-service'],
    risk: 'low', complexity: 'low' },
  picker: { none: false, itemId: '#0-1', title: 'An item', branch: 'fix/0-1-an-item', workDir: '.ai/work/0-1',
    risk: 'low', complexity: 'low', acceptanceCriteria: ['AC1. It works.'] },
  architect: { blocked: false, plan: { steps: [] }, adrs: [], rules: [{ id: 'GEN-01' }], modules: ['incident-service'] },
  implementer: { blocked: false, headSha: SHA('b') },
  'implementer-light': { blocked: false, headSha: SHA('b') },
  'ops:changed-paths.sh': { needsHuman: false, files: ['x.java'], areas: ['incident-service'], ruleFiles: ['general'],
    modules: ['incident-service'], mergeBase: BASE, head: HEAD, migrationTouched: false, k8sOrBuildTouched: false },
  'ops:run-tests.sh': { status: 'pass', head: HEAD, artifact: '.ai/runs/0-1/gate-r1.json', log: '.ai/runs/0-1/tests.log',
    modules: 'incident-service' },
  verifier: { items: [] },
  'acceptance-reviewer': { verdict: 'ACCEPT', criteria: [{ id: 'AC1', met: true }] },
  shipper: { mode: 'ship', prNumber: 7, prUrl: 'https://github.com/o/r/pull/7', followUpsCreated: [] },
  // state.sh branch <name> answers the branch it recorded (backlog #0-131); its other commands, {}.
  'ops:state.sh': ({ prompt }) => { const m = prompt.match(/state\.sh 'branch' '([^']+)'/); return m ? { branch: m[1] } : {} },
  'ops:notify.sh': {}, 'ops:return-to-main.sh': {}, 'ops:local-review.sh': {},
}
const approve = (dimension, extra = {}) => ({ dimension, verdict: 'APPROVE', blocking: [], nonBlocking: [], resolved: [],
  upheldDisputes: [], unverified: [], ...extra })

// answers: key → an answer, or a function of { prompt, opts, n } (n: this key's call number, from 1). Keys are the
// agent type, `ops:<script>` for factory-ops, `isolation` for the isolation check.
function fakeAgent(answers) {
  const calls = []
  const agent = async (prompt, opts = {}) => {
    const type = opts.agentType
    let key = type
    if (type === 'factory-ops') key = `ops:${(prompt.match(/scripts\/factory\/([\w.-]+)/) ?? [])[1]}`
    else if (opts.label === 'isolation check') key = 'isolation'
    calls.push({ key, type, label: opts.label, prompt, schema: opts.schema })
    const n = calls.filter((c) => c.key === key).length
    let answer = key in answers ? answers[key] : DEFAULTS[key]
    if (answer === undefined && type?.startsWith('review-')) answer = approve(type.slice('review-'.length))
    if (answer === undefined) throw new Error(`the fake agent has no answer for ${key}`)
    if (typeof answer === 'function') answer = answer({ prompt, opts, n })
    return answer === null ? null : structuredClone(answer)
  }
  return { agent, calls }
}
const keys = (calls) => calls.map((c) => c.key)

// ---------------------------------------------------------------- helpers

test('the script is read by its markers and the runtime stubs', async () => {
  const h = await helpers()
  for (const name of EXPORTED) assert.ok(h[name] !== undefined, `${name} is defined before the run marker`)
})

test('scopeOf: every category of the three predictions', async () => {
  const { scopeOf } = await helpers()
  const cat = (t, p, a) => scopeOf(t, p, a).category
  assert.equal(cat(['a'], ['a'], null), 'not-measured')
  assert.equal(cat(null, null, ['a']), 'not-measured')
  assert.equal(cat(null, ['a', 'b'], ['a']), 'within-plan')
  assert.equal(cat(null, ['a'], ['a', 'b']), 'diff-outside-plan')
  assert.equal(cat(['a', 'b'], null, ['b']), 'within-touches')
  assert.equal(cat(['a'], null, ['c']), 'diff-outside-touches')
  assert.equal(cat(['a', 'b'], ['a'], ['a']), 'consistent')
  assert.equal(cat(['a'], ['a', 'b'], ['b']), 'backlog-estimate-off')
  assert.equal(cat(['a', 'b'], ['a'], ['b']), 'plan-off')
  assert.equal(cat(['a'], ['a'], ['a', 'b']), 'implementation-drift')
  assert.equal(cat(['a'], ['b'], ['c']), 'unclear-item')
  const s = scopeOf(['b', 'a', 'a'], [], ['a', 'c'])
  assert.deepEqual(s.touches, ['a', 'b'], 'sorted, without repeats')
  assert.equal(s.plan, null, 'an empty prediction is no prediction')
  assert.deepEqual(s.outsideTouches, ['c'])
})

test('valid: a verdict with every list, and blocking findings with id, ruleId and impact', async () => {
  const { valid } = await helpers()
  assert.ok(valid(approve('general')))
  assert.ok(valid(approve('general', { blocking: [{ id: 'g-1', ruleId: 'GEN-01', impact: 'x' }] })))
  assert.ok(!valid(null))
  assert.ok(!valid({ ...approve('general'), verdict: undefined }))
  for (const list of ['blocking', 'resolved', 'upheldDisputes']) {
    assert.ok(!valid({ ...approve('general'), [list]: undefined }), `no ${list}`)
  }
  for (const field of ['id', 'ruleId', 'impact']) {
    const f = { id: 'g-1', ruleId: 'GEN-01', impact: 'x' }
    delete f[field]
    assert.ok(!valid(approve('general', { blocking: [f] })), `a blocking finding without ${field}`)
  }
})

test('ruleFilesOf: rule ids to their files, unknown prefixes dropped, each file once', async () => {
  const { ruleFilesOf } = await helpers()
  assert.deepEqual(ruleFilesOf([{ id: 'GEN-03' }, { id: 'GEN-04' }, { id: 'SEC-01' }, { id: 'FAC-S01' }, { id: 'XYZ-1' }, {}]),
    ['general', 'security', 'factory'])
  assert.deepEqual(ruleFilesOf(undefined), [])
})

test('claimKey and claimId: one id per claim, whatever its spacing and case', async () => {
  const { claimKey, claimId, CLAIM_ID } = await helpers()
  assert.equal(claimKey('  The  Gate\tran \n'), 'the gate ran')
  assert.equal(claimId('The gate ran'), claimId('  the   GATE ran '))
  assert.notEqual(claimId('The gate ran'), claimId('The gate did not run'))
  assert.match(claimId('anything'), CLAIM_ID)
  // Pinned: an owner accepts claims by these ids (acceptUnverifiable), so the hash must not change unnoticed.
  assert.equal(claimId('The gate ran'), 'ver-ea2281f6')
})

test('openClaims: the last verdict of each dimension, the same claim once with every dimension that raised it', async () => {
  const h = await helpers()
  h.record.rounds.push({ round: 1, verdicts: [
    approve('general', { unverified: ['Old claim'] }), approve('security', { unverified: ['Shared  claim', ' '] }), null] })
  h.record.rounds.push({ round: 2, verdicts: [approve('general', { unverified: ['shared claim', 'New claim'] })] })
  assert.deepEqual(h.openClaims(), [
    { claim: 'shared claim', dimensions: ['general', 'security'] },
    { claim: 'New claim', dimensions: ['general'] },
  ])
})

const ITEM = { itemId: '#0-1', id: '0-1' }
async function verifying(claims, answer, args) {
  const h = await helpers({ args, answers: { verifier: answer } })
  h.record.rounds.push({ round: 1, verdicts: [approve('general', { unverified: claims })] })
  return { h, result: await h.verifyClaims(ITEM, 1, HEAD, 'gate.json') }
}

test('verifyClaims: no claim, no verifier', async () => {
  const h = await helpers()
  const result = await h.verifyClaims(ITEM, 1, HEAD, 'gate.json')
  assert.deepEqual(result, { ok: true, items: [] })
  assert.equal(h.calls.length, 0)
})

test('verifyClaims: the verifier gets the claims, the commit and the artifact, never the diff', async () => {
  const { h } = await verifying(['A claim'], { items: [] })
  assert.equal(h.calls.length, 1)
  assert.match(h.calls[0].prompt, /commit c{40}/)
  assert.match(h.calls[0].prompt, /gate\.json/)
  assert.match(h.calls[0].prompt, /"claim":"A claim"/)
})

test('verifyClaims: no answer stops; a missing or unsourced answer is unverifiable (fail closed)', async () => {
  assert.deepEqual((await verifying(['A'], null)).result, { ok: false, reason: 'verifier gave no answer' })
  const { result } = await verifying(['A', 'B', 'C', 'D'], { items: [
    { index: 1, verdict: 'verified', source: 'https://x', quote: null, why: 'w' },
    { index: 2, verdict: 'refuted', source: null, quote: 'q', why: 'w' },
    { index: 3, verdict: 'verified', source: 'https://x', quote: 'q', why: 'it says so' },
  ] })
  assert.ok(result.ok)
  const [a, b, c, d] = result.items
  assert.equal(a.verdict, 'unverifiable'); assert.equal(a.why, 'the verifier gave no answer for this claim')
  assert.equal(b.verdict, 'unverifiable'); assert.equal(b.why, 'answered verified without a source and a quote')
  assert.equal(c.verdict, 'unverifiable'); assert.equal(c.why, 'answered refuted without a source and a quote')
  assert.equal(d.verdict, 'verified'); assert.equal(d.why, 'it says so')
})

test('verifyClaims: a sourced refutation stays refuted', async () => {
  const { result } = await verifying(['A'], { items: [{ index: 0, verdict: 'refuted', source: 'https://x', quote: 'no', why: 'w' }] })
  assert.equal(result.items[0].verdict, 'refuted')
})

test('acceptUnverifiable: the owner accepts an unverifiable claim by its id, on a resumed branch only', async () => {
  const unverifiable = { items: [{ index: 0, verdict: 'unverifiable', source: null, quote: null, why: 'w' }] }
  const { claimId } = await helpers()
  const id = claimId('A claim')
  const resumed = await verifying(['A claim'], unverifiable, { branch: 'fix/0-1-x', acceptUnverifiable: [id] })
  assert.equal(resumed.result.items[0].acceptedByOwner, true)
  const other = await verifying(['A claim'], unverifiable, { branch: 'fix/0-1-x', acceptUnverifiable: [claimId('Another')] })
  assert.equal(other.result.items[0].acceptedByOwner, undefined, 'another id accepts nothing')
  const noBranch = await verifying(['A claim'], unverifiable, { acceptUnverifiable: [id] })
  assert.equal(noBranch.result.items[0].acceptedByOwner, undefined, 'without branch the ids count for nothing')
  const refuted = await verifying(['A claim'], { items: [{ index: 0, verdict: 'refuted', source: 's', quote: 'q', why: 'w' }] },
    { branch: 'fix/0-1-x', acceptUnverifiable: [id] })
  assert.equal(refuted.result.items[0].acceptedByOwner, undefined, 'a refuted claim cannot be accepted')
})

test('refutedFindings and unsettledReason', async () => {
  const { refutedFindings, unsettledReason } = await helpers()
  const items = [
    { id: 'ver-00000001', claim: 'R', dimensions: ['security'], verdict: 'refuted', source: 's', quote: 'q', why: 'w' },
    { id: 'ver-00000002', claim: 'U', dimensions: ['general'], verdict: 'unverifiable', why: 'no source' },
    { id: 'ver-00000003', claim: 'A', dimensions: ['general'], verdict: 'unverifiable', why: 'w', acceptedByOwner: true },
    { id: 'ver-00000004', claim: 'V', dimensions: ['general'], verdict: 'verified', why: 'w' },
  ]
  const [f, ...rest] = refutedFindings(items)
  assert.equal(rest.length, 0)
  assert.equal(f.dimension, 'verification'); assert.equal(f.id, 'ver-00000001'); assert.deepEqual(f.raisedBy, ['security'])
  assert.ok(f.impact && f.ruleId, 'shaped like a blocking finding (valid() needs id, ruleId, impact)')
  const why = unsettledReason(items)
  assert.match(why, /^NEEDS_HUMAN — the verifier could not settle 1 claim/)
  assert.match(why, /ver-00000002 U/)
  assert.doesNotMatch(why, /ver-00000003/)
  assert.equal(unsettledReason(items.filter((i) => i.id !== 'ver-00000002')), null)
})

test('q: only ids, refs and shas reach a factory script', async () => {
  const { q } = await helpers()
  assert.equal(q('0-1'), "'0-1'")
  assert.equal(q('origin/main'), "'origin/main'")
  assert.equal(q(SHA('a')), `'${SHA('a')}'`)
  for (const bad of ['a b', "a'b", 'a;b', '$(x)', 'a`b', '', 'a\nb']) assert.throws(() => q(bad), /unsafe argument/, bad)
})

// ---------------------------------------------------------------- runs

test('a good run ships in shadow mode, never merging', async () => {
  const { outcome, calls } = await run()
  assert.equal(outcome.outcome, 'SHIPPED')
  assert.equal(outcome.mode, 'shadow')
  assert.equal(outcome.rounds, 1)
  const ship = calls.find((c) => c.key === 'shipper')
  assert.match(ship.prompt, /shadow: true\. risk-high: false\. merge: false\./)
  assert.ok(keys(calls).includes('implementer-light'), 'low complexity in the backlog and the picker: the lighter implementer')
  assert.ok(!keys(calls).includes('verifier'), 'no claim, no verifier')
})

test('the panel is the five dimensions, plus migration and k8s when the paths call for them', async () => {
  const plain = await run()
  const panel = (calls) => calls.filter((c) => c.key.startsWith('review-')).map((c) => c.key).sort()
  assert.deepEqual(panel(plain.calls), ['review-architecture', 'review-docs', 'review-general', 'review-performance', 'review-security'])
  const both = await run({ answers: { 'ops:changed-paths.sh': { ...DEFAULTS['ops:changed-paths.sh'], migrationTouched: true, k8sOrBuildTouched: true } } })
  assert.ok(panel(both.calls).includes('review-migration') && panel(both.calls).includes('review-k8s'))
})

test('preflight: no answer, a refusal or no base commit stops before anything starts', async () => {
  const none = await run({ answers: { 'ops:preflight.sh': null } })
  assert.equal(none.outcome.outcome, 'NOT_STARTED'); assert.match(none.outcome.reasons[0], /AGENT_TYPE_OPTION/)
  const refused = await run({ answers: { 'ops:preflight.sh': { ok: false, reasons: ['STOP file present'] } } })
  assert.deepEqual(refused.outcome, { outcome: 'NOT_STARTED', reasons: ['STOP file present'] })
  const noBase = await run({ answers: { 'ops:preflight.sh': { ok: true, reasons: [], baseSha: 'main' } } })
  assert.deepEqual(noBase.outcome.reasons, ['preflight returned no base commit'])
})

test('isolation: a reviewer that can edit files, or without its definition, stops the run', async () => {
  for (const iso of [{ tools: ['Read', 'Edit'], definitionLoaded: true }, { tools: ['Read'], definitionLoaded: false }, null]) {
    const { outcome, calls } = await run({ answers: { isolation: iso } })
    assert.equal(outcome.outcome, 'NOT_STARTED')
    assert.match(outcome.reasons[0], /agent isolation check failed/)
    assert.ok(keys(calls).includes('ops:state.sh'), 'the lock is released')
    assert.ok(!keys(calls).includes('picker'))
  }
})

test('args: acceptUnverifiable without branch, a malformed item, malformed claim ids', async () => {
  const noBranch = await run({ args: { acceptUnverifiable: ['ver-00000001'] } })
  assert.match(noBranch.outcome.reasons[0], /acceptUnverifiable needs branch/)
  const badItem = await run({ args: { item: '0-1; rm -rf /' } })
  assert.match(badItem.outcome.reasons[0], /args\.item must look like #0-25/)
  assert.ok(!keys(badItem.calls).includes('ops:next-item.sh'), 'refused before any script sees it')
  for (const ids of [[], ['ver-xyz'], 'ver-00000001', ['ver-00000001', 'nope']]) {
    const bad = await run({ args: { branch: 'fix/0-1-x', acceptUnverifiable: ids } })
    assert.equal(bad.outcome.outcome, 'NOT_STARTED', JSON.stringify(ids))
    assert.match(bad.outcome.reasons[0], /acceptUnverifiable must be a list of claim ids/)
  }
})

test('the item: nothing to do, a malformed next item, a malformed or different pick', async () => {
  const nothing = await run({ answers: { 'ops:next-item.sh': { ok: true, item: null, reason: 'queue empty' } } })
  assert.equal(nothing.outcome.outcome, 'NOTHING_TO_DO')
  const malformed = await run({ answers: { 'ops:next-item.sh': { ...DEFAULTS['ops:next-item.sh'], item: '0-1' } } })
  assert.match(malformed.outcome.reasons[0], /malformed item/)
  for (const [pick, why] of [
    [{ ...DEFAULTS.picker, acceptanceCriteria: [] }, /picker answer incomplete/],
    [{ ...DEFAULTS.picker, branch: 'fix/0-1 x' }, /picker answer incomplete/],
    [{ ...DEFAULTS.picker, itemId: '#0-2' }, /picker prepared #0-2 instead of #0-1/],
    [null, /picker gave no answer/],
  ]) {
    const { outcome } = await run({ answers: { picker: pick } })
    assert.equal(outcome.outcome, 'BLOCKED'); assert.match(outcome.reason, why)
  }
})

test("the run's branch is recorded in the lock before any agent works on it, or the run stops", async () => {
  const { calls } = await run()
  const at = calls.findIndex((c) => /state\.sh 'branch' 'fix\/0-1-an-item'/.test(c.prompt))
  assert.ok(at > 0, 'state.sh branch <the picked branch>')
  assert.ok(at > calls.findIndex((c) => c.key === 'picker') && at < calls.findIndex((c) => c.key === 'architect'))
  const resumed = await run({ args: { branch: 'fix/0-1-an-item', item: '#0-1' } })
  assert.ok(resumed.calls.some((c) => /state\.sh 'branch' 'fix\/0-1-an-item'/.test(c.prompt)), 'a resumed run records it too')
  for (const answer of [{ error: 'this run already recorded its branch (fix/0-9-x)' }, { branch: 'fix/0-9-other' }, null]) {
    const { outcome, calls: c } = await run({ answers: { 'ops:state.sh': ({ prompt }) => (/'branch'/.test(prompt) ? answer : {}) } })
    assert.equal(outcome.outcome, 'BLOCKED')
    assert.match(outcome.reason, /could not record the run's branch fix\/0-1-an-item/)
    assert.ok(!c.some((x) => x.key === 'architect' || x.key === 'shipper'), 'nothing works on, or pushes, a branch the lock does not name')
  }
})

test("the run's branch must be the item's own, and on resume the owner's", async () => {
  for (const branch of ['feat/0-40-owners-work', 'fix/0-10-x', 'fix/0-1', 'fix/0-1-', 'fix/x0-1-y', 'fix/0-1-a/b']) {
    const { outcome, calls } = await run({ answers: { picker: { ...DEFAULTS.picker, branch } } })
    assert.equal(outcome.outcome, 'BLOCKED', branch)
    assert.match(outcome.reason, /an item's branch is <type>\/0-1-<slug>|picker answer incomplete/, branch)
    assert.ok(!calls.some((c) => /state\.sh 'branch'/.test(c.prompt)), `${branch}: never recorded`)
  }
  const other = await run({ args: { branch: 'fix/0-1-an-item', item: '#0-1' }, answers: { picker: { ...DEFAULTS.picker, branch: 'fix/0-1-another' } } })
  assert.match(other.outcome.reason, /picker answered the branch fix\/0-1-another, not fix\/0-1-an-item/)
  assert.ok(!other.calls.some((c) => /state\.sh 'branch'/.test(c.prompt)))
})

test('complexity: anything but low, in the backlog or the pick, takes the full implementer', async () => {
  for (const [next, pick] of [['high', 'low'], ['low', 'medium'], [undefined, 'low']]) {
    const { calls } = await run({ answers: {
      'ops:next-item.sh': { ...DEFAULTS['ops:next-item.sh'], complexity: next }, picker: { ...DEFAULTS.picker, complexity: pick } } })
    assert.ok(keys(calls).includes('implementer') && !keys(calls).includes('implementer-light'), `${next}/${pick}`)
  }
})

test('plan and implementation: a block or no answer stops the item', async () => {
  for (const [answers, why] of [
    [{ architect: null }, /architect gave no answer/],
    [{ architect: { blocked: true, reason: 'irreversible' } }, /architect: irreversible/],
    [{ 'implementer-light': null }, /implementer gave no answer/],
    [{ 'implementer-light': { blocked: true, reason: 'ambiguous', headSha: SHA('b') } }, /implementer: ambiguous/],
  ]) {
    const { outcome } = await run({ answers })
    assert.equal(outcome.outcome, 'BLOCKED'); assert.match(outcome.reason, why)
  }
})

test('self-check: a rule file the diff calls for and the plan did not cover', async () => {
  const paths = { ...DEFAULTS['ops:changed-paths.sh'], ruleFiles: ['general', 'migration'] }
  const { calls } = await run({ answers: { 'ops:changed-paths.sh': paths } })
  const check = calls.find((c) => c.label === 'self-check migration')
  assert.ok(check, 'the implementer checks the change against migration.md')
  assert.match(check.prompt, /\.ai\/rules\/review\/migration\.md/)
  const covered = await run({ answers: { 'ops:changed-paths.sh': paths,
    architect: { ...DEFAULTS.architect, rules: [{ id: 'MIG-02' }] } } })
  assert.ok(!covered.calls.some((c) => c.label?.startsWith('self-check')), 'the plan listed a migration rule: no self-check')
  const none = await run({ answers: { 'ops:changed-paths.sh': (c) => (c.n === 1 ? { ...paths, ruleFiles: undefined } : paths) } })
  assert.match(none.outcome.reason, /changed-paths returned no rule files/)
})

test('the path gate: a change that needs the owner stops before the tests run', async () => {
  const paths = { ...DEFAULTS['ops:changed-paths.sh'], needsHuman: true, protectedTouched: ['.github/workflows/ci.yml'] }
  const { outcome, calls } = await run({ answers: { 'ops:changed-paths.sh': (c) => (c.n === 1 ? DEFAULTS['ops:changed-paths.sh'] : paths) } })
  assert.equal(outcome.outcome, 'BLOCKED')
  assert.match(outcome.reason, /NEEDS_HUMAN — human-owned paths changed: \.github\/workflows\/ci\.yml/)
  assert.ok(!keys(calls).includes('ops:run-tests.sh'))
})

test('the test gate: stop, a pass without its artifact, another commit, failing tests after the fixes', async () => {
  const gate = DEFAULTS['ops:run-tests.sh']
  for (const [answers, why] of [
    [{ 'ops:run-tests.sh': { status: 'stop', log: 'l', summary: ['Docker is not running'] } }, /test gate stopped \(log l\): Docker is not running/],
    [{ 'ops:run-tests.sh': { ...gate, artifact: null } }, /passed without its artifact/],
    [{ 'ops:run-tests.sh': { ...gate, head: SHA('d') } }, /the tested commit \(d{40}\) is not the branch HEAD/],
    [{ 'ops:run-tests.sh': { ...gate, status: 'fixable', summary: ['1 failed'] } }, /tests still failing after 2 fix attempts/],
    [{ 'ops:run-tests.sh': null }, /test gate returned no result/],
  ]) {
    const { outcome } = await run({ answers })
    assert.equal(outcome.outcome, 'BLOCKED'); assert.match(outcome.reason, why)
  }
  const fixed = await run({ answers: { 'ops:run-tests.sh': (c) => (c.n === 1 ? { ...gate, status: 'fixable', summary: ['1 failed'] } : gate) } })
  assert.equal(fixed.outcome.outcome, 'SHIPPED', 'a fixable failure the implementer fixes goes on')
  assert.ok(fixed.calls.some((c) => c.label === 'fix tests r1'))
})

test('the panel: an invalid verdict, a wrong dimension, NEEDS_HUMAN or an upheld dispute stops the item', async () => {
  for (const [answer, why] of [
    [{ ...approve('security'), blocking: [{ id: 's-1', ruleId: 'SEC-01' }] }, /no valid verdict in round 1 \(fail closed\)/],
    [approve('general'), /no valid verdict in round 1/],
    [null, /no valid verdict in round 1/],
    [approve('security', { verdict: 'NEEDS_HUMAN' }), /NEEDS_HUMAN from security/],
    [approve('security', { upheldDisputes: [{ id: 's-1' }] }), /dispute upheld by security/],
  ]) {
    const { outcome } = await run({ answers: { 'review-security': answer } })
    assert.equal(outcome.outcome, 'BLOCKED'); assert.match(outcome.reason, why)
  }
})

const FINDING = { id: 'sec-1a2b', ruleId: 'SEC-01', issue: 'a leak', impact: 'tenant data' }

test('a blocking finding is fixed in a second round, judged by its dimension, security and general', async () => {
  const { outcome, calls } = await run({ answers: {
    'review-architecture': (c) => (c.n === 1 ? approve('architecture', { verdict: 'CHANGES_REQUESTED', blocking: [FINDING] }) : approve('architecture')) } })
  assert.equal(outcome.outcome, 'SHIPPED'); assert.equal(outcome.rounds, 2)
  const fix = calls.find((c) => c.label === 'fix r1')
  assert.match(fix.prompt, /sec-1a2b/)
  const round2 = calls.slice(calls.indexOf(fix)).filter((c) => c.key.startsWith('review-')).map((c) => c.key).sort()
  assert.deepEqual(round2, ['review-architecture', 'review-general', 'review-security'])
  assert.match(round2.length && calls.filter((c) => c.key === 'review-architecture')[1].prompt, /previousRoundSha: c{40}/)
})

test('the same blocking findings after a fix round: no progress; three rounds without approval: BLOCKED', async () => {
  const blocked = approve('architecture', { verdict: 'CHANGES_REQUESTED', blocking: [FINDING] })
  const same = await run({ answers: { 'review-architecture': blocked } })
  assert.equal(same.outcome.outcome, 'BLOCKED'); assert.match(same.outcome.reason, /no progress: the same blocking findings \(sec-1a2b\)/)
  const moving = await run({ answers: { 'review-architecture': (c) =>
    approve('architecture', { verdict: 'CHANGES_REQUESTED', blocking: [{ ...FINDING, id: `arc-${c.n}` }] }) } })
  assert.equal(moving.outcome.outcome, 'BLOCKED'); assert.match(moving.outcome.reason, /review did not converge in 3 rounds/)
  const one = await run({ args: { maxRounds: 1 }, answers: { 'review-architecture': blocked } })
  assert.match(one.outcome.reason, /review did not converge in 1 rounds/)
})

test('a claim the panel could not check: unverifiable stops for the owner, who accepts it by id on resume', async () => {
  const claim = 'The library is not affected by CVE-2026-1'
  const answers = {
    'review-general': approve('general', { unverified: [claim] }),
    verifier: { items: [{ index: 0, verdict: 'unverifiable', source: null, quote: null, why: 'no allowed source' }] },
  }
  const first = await run({ answers })
  assert.equal(first.outcome.outcome, 'BLOCKED')
  const id = first.outcome.reason.match(/ver-[0-9a-f]{8}/)[0]
  assert.match(first.outcome.reason, /NEEDS_HUMAN — the verifier could not settle 1 claim/)
  const resumed = await run({ args: { branch: 'fix/0-1-an-item', item: '#0-1', acceptUnverifiable: [id] }, answers })
  assert.equal(resumed.outcome.outcome, 'SHIPPED')
  assert.ok(!keys(resumed.calls).includes('architect') && !keys(resumed.calls).includes('ops:next-item.sh'), 'resume skips pick, plan, implement')
  const ship = resumed.calls.find((c) => c.key === 'shipper')
  assert.match(ship.prompt, /"acceptedByOwner":true/)
  const another = await run({ args: { branch: 'fix/0-1-an-item', item: '#0-1', acceptUnverifiable: ['ver-00000000'] }, answers })
  assert.equal(another.outcome.outcome, 'BLOCKED', 'an id the owner did not give accepts nothing')
})

test('a refuted claim blocks like a finding, and goes back to the dimension that raised it', async () => {
  const { outcome, calls } = await run({ answers: {
    'review-performance': (c) => approve('performance', { unverified: c.n === 1 ? ['Kafka 4 batches by default'] : [] }),
    verifier: { items: [{ index: 0, verdict: 'refuted', source: 'https://kafka.apache.org', quote: 'it does not', why: 'w' }] },
  } })
  assert.equal(outcome.outcome, 'SHIPPED'); assert.equal(outcome.rounds, 2)
  const fix = calls.find((c) => c.label === 'fix r1')
  assert.match(fix.prompt, /refuted: Kafka 4 batches by default/)
  const round2 = calls.slice(calls.indexOf(fix)).filter((c) => c.key.startsWith('review-')).map((c) => c.key)
  assert.ok(round2.includes('review-performance'))
  const again = await run({ answers: {
    'review-performance': approve('performance', { unverified: ['Kafka 4 batches by default'] }),
    verifier: { items: [{ index: 0, verdict: 'refuted', source: 's', quote: 'q', why: 'w' }] } } })
  assert.match(again.outcome.reason, /no progress: the same blocking findings \(ver-[0-9a-f]{8}\)/)
})

test('acceptance: a rejection is fixed once and reviewed again; twice rejected, or no answer, stops', async () => {
  const reject = { verdict: 'REJECT', criteria: [{ id: 'AC1', met: false }] }
  const fixed = await run({ answers: { 'acceptance-reviewer': (c) => (c.n === 1 ? reject : DEFAULTS['acceptance-reviewer']) } })
  assert.equal(fixed.outcome.outcome, 'SHIPPED')
  assert.ok(fixed.calls.some((c) => c.label === 'fix acceptance'))
  assert.ok(fixed.calls.some((c) => c.key.startsWith('review-') && /round: 3/.test(c.prompt)), 'the delta gets a final round')
  assert.match((await run({ answers: { 'acceptance-reviewer': reject } })).outcome.reason, /acceptance rejected twice/)
  assert.match((await run({ answers: { 'acceptance-reviewer': null } })).outcome.reason, /acceptance-reviewer gave no answer/)
  const failing = await run({ answers: { 'acceptance-reviewer': (c) => (c.n === 1 ? reject : DEFAULTS['acceptance-reviewer']),
    'review-docs': (c) => (/round: 3/.test(c.prompt) ? approve('docs', { verdict: 'CHANGES_REQUESTED' }) : approve('docs')) } })
  assert.match(failing.outcome.reason, /the acceptance fix did not pass a final review round/)
})

test('risk: anything but low, in the backlog or the pick, is high; only low risk with shadow off auto-merges', async () => {
  const off = { shadow: false }
  const low = await run({ args: off })
  assert.equal(low.outcome.mode, 'auto-merge')
  assert.match(low.calls.find((c) => c.key === 'shipper').prompt, /merge: true\./)
  for (const answers of [
    { picker: { ...DEFAULTS.picker, risk: 'medium' } },
    { 'ops:next-item.sh': { ...DEFAULTS['ops:next-item.sh'], risk: 'high' } },
    { picker: { ...DEFAULTS.picker, risk: undefined } },
  ]) {
    const { outcome, calls } = await run({ args: off, answers })
    assert.equal(outcome.mode, 'risk-high')
    assert.match(calls.find((c) => c.key === 'shipper').prompt, /risk-high: true\. merge: false\./)
  }
  const needsHuman = await run({ answers: { 'acceptance-reviewer': { verdict: 'NEEDS_HUMAN', criteria: [], reason: 'unclear AC' } } })
  assert.match(needsHuman.outcome.reason, /acceptance NEEDS_HUMAN: unclear AC/)
  const highNeedsHuman = await run({ answers: { picker: { ...DEFAULTS.picker, risk: 'high' },
    'acceptance-reviewer': { verdict: 'NEEDS_HUMAN', criteria: [], reason: 'unclear AC' } } })
  assert.equal(highNeedsHuman.outcome.outcome, 'SHIPPED', 'a high-risk item goes to the owner as a PR anyway')
})

test('ship: no PR stops; a PR with an error is recorded, not shipped again as blocked', async () => {
  assert.match((await run({ answers: { shipper: { mode: 'ship', prUrl: null, error: 'gh failed' } } })).outcome.reason, /shipper failed: gh failed/)
  const { outcome, calls } = await run({ answers: { shipper: { ...DEFAULTS.shipper, error: 'label missing' } } })
  assert.equal(outcome.outcome, 'SHIPPED_WITH_ERROR')
  assert.equal(calls.filter((c) => c.key === 'shipper').length, 1, 'no blocked-mode shipper on a published PR')
  const state = calls.filter((c) => c.key === 'ops:state.sh').map((c) => c.prompt).join('\n')
  assert.match(state, /'record' 'blocked' '0-1' 'https:\/\/github\.com\/o\/r\/pull\/7'/)
})

test('a BLOCKED item with a branch gets a draft PR and its stop stage recorded', async () => {
  const { outcome, calls } = await run({ answers: { architect: { blocked: true, reason: 'irreversible' } } })
  assert.equal(outcome.outcome, 'BLOCKED')
  const draft = calls.find((c) => c.key === 'shipper')
  assert.match(draft.prompt, /^You are running as the `shipper` agent[\s\S]*Mode: blocked\./)
  const state = calls.filter((c) => c.key === 'ops:state.sh').map((c) => c.prompt).join('\n')
  assert.match(state, /'record' 'blocked' '0-1' 'https:\/\/github\.com\/o\/r\/pull\/7' 'plan'/)
})

test('an unsafe value never reaches a factory script', async () => {
  const { outcome } = await run({ answers: { 'ops:preflight.sh': { ...DEFAULTS['ops:preflight.sh'], baseSha: undefined } } })
  assert.deepEqual(outcome.reasons, ['preflight returned no base commit'], 'no sha, no script call with it')
  const bad = await run({ answers: { shipper: { ...DEFAULTS.shipper, prUrl: 'https://x/pull/7; rm -rf /' } } })
  const state = bad.calls.filter((c) => c.key === 'ops:state.sh').map((c) => c.prompt).join('\n')
  assert.match(state, /'record' 'shipped' '0-1' '-'/, 'an odd PR URL is recorded as "-", the record is not lost')
  assert.doesNotMatch(state, /rm -rf/)
})

test('the agent budget: past 45 work-agent calls an agent answers nothing, and the stop names the budget', async () => {
  const h = await helpers({ answers: { implementer: { blocked: false, headSha: SHA('b') } } })
  for (let i = 0; i < 45; i++) assert.ok(await h.as('implementer', 'p'), `call ${i + 1} reaches the agent`)
  assert.ok(await h.as('factory-ops', 'Run exactly: scripts/factory/state.sh'), 'factory-ops is not budgeted')
  assert.ok(await h.as('shipper', 'p'), 'the shipper is not budgeted')
  const before = h.calls.length
  assert.equal(await h.as('implementer', 'p'), null, 'the 46th work-agent call')
  assert.equal(h.calls.length, before, 'it never reaches the agent')
  const stopped = await h.stopItem(null, 'implementer gave no answer')
  assert.equal(stopped.outcome, 'BLOCKED')
  assert.match(stopped.reason, /^agent budget of 45 calls exceeded — split the item \(last step: implementer gave no answer\)$/)
})

// ---------------------------------------------------------------- seed-bugs.js (backlog #0-170)

const SEED = readFileSync(join(dirname(FILE), 'seed-bugs.js'), 'utf8')
const SEED_ARGS = { pattern: 'P-10', commit: 'abc1234', runId: 'r1' }
const SEEDED = { ok: true, branch: 'seed/P-10-r1', base: 'b'.repeat(40), head: 'c'.repeat(40), files: ['svc/src/main/java/A.java'] }
const CHECKED = { ok: true, commit: 'd'.repeat(40), files: ['svc/src/main/java/A.java'] }
async function seed(answers, ...given) {
  const args = given.length ? given[0] : SEED_ARGS   // not a default parameter: a test passes undefined itself
  const fake = fakeAgent({ 'audit-synthesis': {}, 'ops:seed-branch.sh': SEEDED, 'ops:seed-check.sh': CHECKED,
    'ops:return-to-main.sh': { onMain: true }, ...answers })
  const outcome = await evaluate(strip(SEED), { args, agent: fake.agent, logs: [] })
  return { outcome, calls: fake.calls }
}
const TRUTH = { branch: 'seed/P-10-r1', pattern: 'P-10', dimension: 'performance', rule: 'PERF-01',
  file: 'svc/src/main/java/A.java', line: 40 }

test('seed-bugs: a blocked seeder is a failed run with its reason, not a skip', async () => {
  const { outcome, calls } = await seed({ 'defect-seeder': { blocked: true, reason: 'git -C /workspace show: refused' } })
  assert.equal(outcome.outcome, 'FAILED')
  assert.match(outcome.reason, /defect-seeder was blocked: git -C \/workspace show: refused/)
  assert.ok(!calls.some((c) => c.key.startsWith('review-')), 'no panel runs on a branch that was never made')
  const long = await seed({ 'defect-seeder': { blocked: true, reason: 'x'.repeat(5000) } })
  assert.ok(long.outcome.reason.length < 400, 'the reason is cut short')
})

test('seed-bugs: a skip says why, or the run failed', async () => {
  const fits = await seed({ 'defect-seeder': { skipped: true, reason: 'no repository loop in this change' } })
  assert.deepEqual(fits.outcome, { outcome: 'SKIPPED', reason: 'no repository loop in this change', cleanup: 'git branch -D seed/P-10-r1' })
  const silent = await seed({ 'defect-seeder': { skipped: true } })
  assert.equal(silent.outcome.outcome, 'FAILED')
  assert.match(silent.outcome.reason, /skipped without a reason/)
})

test('seed-bugs: a ground truth the Record phase cannot use fails the run', async () => {
  for (const truth of [{ ...TRUTH, branch: undefined }, { ...TRUTH, branch: 'main' }, { ...TRUTH, branch: 'seed/P-10-old' },
    { ...TRUTH, file: '' }, { ...TRUTH, file: 'svc/src/main/java/Other.java' }, { ...TRUTH, line: undefined },
    { ...TRUTH, rule: '' }, { ...TRUTH, dimension: 'k8s' }]) {
    const { outcome } = await seed({ 'defect-seeder': truth })
    assert.equal(outcome.outcome, 'FAILED', JSON.stringify(truth))
    assert.match(outcome.reason, /ground truth is incomplete/)
  }
  assert.equal((await seed({ 'defect-seeder': null })).outcome.outcome, 'FAILED')
})

test('seed-bugs: a planted defect is measured and recorded', async () => {
  const caught = approve('performance', { verdict: 'CHANGES_REQUESTED',
    blocking: [{ id: 'p-1', ruleId: 'PERF-01', file: TRUTH.file, line: 42, impact: 'N+1 per request' }] })
  const { outcome, calls } = await seed({ 'defect-seeder': TRUTH, 'review-performance': caught })
  assert.equal(outcome.outcome, 'MEASURED')
  assert.equal(outcome.caughtByExpected, true)
  assert.equal(outcome.blockingByExpected, true)
  assert.ok(calls.some((c) => c.key === 'audit-synthesis'), 'the row is recorded')
  const missed = await seed({ 'defect-seeder': TRUTH })
  assert.equal(missed.outcome.caughtByExpected, false)
})

test('seed-bugs: the branch comes from seed-branch.sh, never from the seeder, and the run ends on main (#0-173)', async () => {
  const { outcome, calls } = await seed({ 'defect-seeder': TRUTH })
  assert.equal(outcome.outcome, 'MEASURED')
  const at = (k) => calls.findIndex((c) => c.key === k)
  assert.ok(at('ops:seed-branch.sh') >= 0 && at('ops:seed-branch.sh') < at('defect-seeder'), 'the branch is made before the seeder runs')
  assert.match(calls[at('ops:seed-branch.sh')].prompt, /seed-branch\.sh 'P-10' 'r1' 'abc1234'/)
  assert.match(calls[at('defect-seeder')].prompt, /seed\/P-10-r1 is checked out[\s\S]*svc\/src\/main\/java\/A\.java/)
  const review = calls.find((c) => c.key === 'review-performance')
  assert.match(review.prompt, new RegExp(`mergeBase: ${SEEDED.base}`), 'the panel diffs against the seed base, not the old commit')
  assert.ok(at('defect-seeder') < at('ops:seed-check.sh') && at('ops:seed-check.sh') < at('review-performance'),
    'the tree is checked after the seeder, before the panel')
  assert.match(calls[at('ops:seed-check.sh')].prompt, new RegExp(`seed-check\\.sh 'P-10' 'r1' '${SEEDED.base}' '${SEEDED.head}'`))
  assert.ok(at('ops:return-to-main.sh') > at('review-performance') && at('ops:return-to-main.sh') < at('audit-synthesis'),
    'back on main before the row is written, so the row never rides onto a seed branch')
  assert.equal(calls.at(-1).key, 'audit-synthesis')
  assert.equal(outcome.recorded, true)
  assert.match(outcome.cleanup, /git branch -D seed\/P-10-r1; commit \.ai\/audit\/benchmark\/results\.md/)
})

test('seed-bugs: the panel runs only on a tree seed-check.sh confirmed (#0-173, review round 1)', async () => {
  for (const answer of [null, { ok: false, reason: 'the tree is on main, not on seed/P-10-r1' },
    { ok: true, files: ['svc/src/main/java/Other.java'] }, { ok: true }]) {
    const { outcome, calls } = await seed({ 'defect-seeder': TRUTH, 'ops:seed-check.sh': answer })
    assert.equal(outcome.outcome, 'FAILED', JSON.stringify(answer))
    assert.match(outcome.reason, /the seed branch is not as the seeder says/)
    assert.ok(!calls.some((c) => c.key.startsWith('review-')), 'no panel on an unconfirmed tree')
    assert.equal(calls.at(-1).key, 'ops:return-to-main.sh')
  }
})

test('seed-bugs: a run that cannot get back to main says where it left the tree (#0-173, review round 1)', async () => {
  const stuck = { onMain: false, branch: 'seed/P-10-r1', reason: 'working tree not clean: left as it is for the owner' }
  const measured = await seed({ 'defect-seeder': TRUTH, 'ops:return-to-main.sh': stuck })
  assert.equal(measured.outcome.outcome, 'MEASURED')
  assert.equal(measured.outcome.recorded, false)
  assert.equal(measured.outcome.leftOn, 'seed/P-10-r1')
  assert.match(measured.outcome.returnToMain, /not clean/)
  assert.ok(!measured.calls.some((c) => c.key === 'audit-synthesis'), 'no row written on the seed branch')
  const failed = await seed({ 'defect-seeder': { blocked: true, reason: 'x' }, 'ops:return-to-main.sh': stuck })
  assert.equal(failed.outcome.outcome, 'FAILED')
  assert.equal(failed.outcome.leftOn, 'seed/P-10-r1')
  const silent = await seed({ 'defect-seeder': TRUTH, 'ops:return-to-main.sh': null })
  assert.equal(silent.outcome.recorded, false, 'no answer is not "on main"')
  const ok = await seed({ 'defect-seeder': { blocked: true, reason: 'x' } })
  assert.ok(!('leftOn' in ok.outcome), 'nothing to say when it is back on main')
})

test('seed-bugs: a failed run that made the branch says how to delete it (#0-173, review round 2)', async () => {
  for (const answers of [{ 'defect-seeder': { blocked: true, reason: 'x' } }, { 'defect-seeder': { skipped: true, reason: 'y' } },
    { 'defect-seeder': TRUTH, 'ops:seed-check.sh': { ok: false, reason: 'z' } }, { 'ops:seed-branch.sh': { ...SEEDED, base: 'abc' } }]) {
    const { outcome } = await seed(answers)
    assert.equal(outcome.cleanup, 'git branch -D seed/P-10-r1', JSON.stringify(answers))
  }
  for (const answer of [null, { ok: false, reason: 'the working tree is not clean' }]) {
    const { outcome } = await seed({ 'ops:seed-branch.sh': answer })
    assert.ok(!('cleanup' in outcome), 'no branch, nothing to delete')
  }
  const named = await seed({ 'ops:seed-branch.sh': { ...SEEDED, branch: 'seed/P-10-r1 x; rm' } })
  assert.equal(named.outcome.cleanup, 'git branch -D seed/P-10-r1', 'the name the run asked for, never the answer\'s')
})

test('seed-bugs: no seed branch, no seeder; every outcome ends on main', async () => {
  for (const answer of [null, { ok: false, reason: 'the working tree is not clean' }, { ok: true, branch: 'seed/x' },
    { ...SEEDED, branch: 'seed/P-10-other' }, { ...SEEDED, base: 'abc' }, { ...SEEDED, head: undefined }, { ...SEEDED, files: [] }]) {
    const { outcome, calls } = await seed({ 'ops:seed-branch.sh': answer })
    assert.equal(outcome.outcome, 'FAILED', JSON.stringify(answer))
    assert.ok(!calls.some((c) => c.key === 'defect-seeder'))
    assert.equal(calls.at(-1).key, 'ops:return-to-main.sh')
  }
  const notClean = await seed({ 'ops:seed-branch.sh': { ok: false, reason: 'the working tree is not clean' } })
  assert.match(notClean.outcome.reason, /no seed branch: the working tree is not clean/)
  for (const answer of [{ blocked: true, reason: 'x' }, { skipped: true, reason: 'y' }, { skipped: true }, { ...TRUTH, line: undefined }]) {
    const { calls } = await seed({ 'defect-seeder': answer })
    assert.equal(calls.at(-1).key, 'ops:return-to-main.sh', JSON.stringify(answer))
  }
})

test('seed-bugs: `/seed-bugs P-10 <sha> <run id>` passes one string, read as the three args (#0-174)', async () => {
  const fromObject = await seed({ 'defect-seeder': TRUTH })
  for (const words of ['P-10 abc1234 r1', '  P-10\tabc1234   r1\n']) {
    const { outcome, calls } = await seed({ 'defect-seeder': TRUTH }, words)
    assert.deepEqual(outcome, fromObject.outcome, JSON.stringify(words))
    assert.deepEqual(calls.map((c) => c.prompt), fromObject.calls.map((c) => c.prompt), JSON.stringify(words))
  }
  for (const args of ['P-10 abc1234', 'P-10 abc1234 r1 extra', '', 'P-10 abc1234 r1;rm', "P-10 abc1234 'r1'", undefined, null,
    {}, { pattern: 'P-10', commit: 'abc1234' }, { ...SEED_ARGS, runId: 'r 1' }]) {
    const { outcome, calls } = await seed({}, args)
    assert.equal(outcome.outcome, 'NOT_STARTED', String(JSON.stringify(args)))
    assert.match(outcome.reason, /pattern, commit, runId/)
    assert.equal(calls.length, 0, `nothing runs on ${JSON.stringify(args)}`)
  }
})

// The runtime validates an agent's answer against its `schema` (the fake agent does not): this checks the schemas
// themselves, with the subset of JSON Schema they use, against what the scripts print and what the agent answered.
// A keyword or type outside that subset throws, anywhere in the schema (review round 2 of #0-174): ignored, a later
// `required` or `enum` would pass here unchecked.
const KNOWN_KEYWORDS = new Set(['type', 'items', 'properties', 'additionalProperties'])
const TYPE_OK = { object: (v) => v !== null && typeof v === 'object' && !Array.isArray(v), array: Array.isArray,
  string: (v) => typeof v === 'string', boolean: (v) => typeof v === 'boolean', number: (v) => typeof v === 'number' }
function knownSchema(schema) {
  const unknown = Object.keys(schema).filter((k) => !KNOWN_KEYWORDS.has(k))
  if (unknown.length) throw new Error(`conforms() does not know ${unknown.join(', ')}: extend it`)
  if (!Object.hasOwn(TYPE_OK, schema.type)) throw new Error(`conforms() does not know the type ${JSON.stringify(schema.type)}: extend it`)
  if (schema.items) knownSchema(schema.items)
  Object.values(schema.properties ?? {}).forEach(knownSchema)
}
function conforms(value, schema) {
  knownSchema(schema)
  if (!TYPE_OK[schema.type](value)) return false
  if (schema.type === 'array') return value.every((v) => conforms(v, schema.items))
  if (schema.type !== 'object') return true
  return Object.entries(value).every(([k, v]) => k in (schema.properties ?? {}) ? conforms(v, schema.properties[k])
    : schema.additionalProperties !== false)
}

test('conforms() refuses a schema keyword or type it does not check (#0-174, review round 2)', () => {
  for (const schema of [{ type: 'object', required: ['ok'] }, { type: 'string', enum: ['a'] }, { type: ['string', 'null'] },
    { type: 'integer' }, { type: 'object', properties: { files: { type: 'array', items: { type: 'string', pattern: 'x' } } } }]) {
    assert.throws(() => conforms({}, schema), /conforms\(\) does not know/, JSON.stringify(schema))
  }
})

test('seed-bugs: each factory script\'s answer is typed, so the agent cannot hand back strings (#0-174)', async () => {
  const { calls } = await seed({ 'defect-seeder': TRUTH })
  const schemaOf = (key) => calls.find((c) => c.key === key).schema
  const printed = {
    'ops:seed-branch.sh': [SEEDED, { ok: false, reason: 'the working tree is not clean' }],
    'ops:seed-check.sh': [CHECKED, { ok: false, reason: 'the tree is on main' }],
    'ops:return-to-main.sh': [{ onMain: true }, { onMain: true, left: 'seed/P-10-r1' },
      { onMain: false, branch: 'seed/P-10-r1', reason: 'working tree not clean: left as it is for the owner' }],
  }
  for (const [key, answers] of Object.entries(printed)) {
    for (const answer of [...answers, { error: 'jq: command not found', exitCode: 127 }]) {
      assert.ok(conforms(answer, schemaOf(key)), `${key} accepts ${JSON.stringify(answer)}`)
    }
  }
  // What factory-ops answered in the run of 2026-10-25 (P-10 on 76379e806fb4, run perf-after-p10).
  const answered = {
    'ops:seed-branch.sh': [{ ...SEEDED, ok: 'true' }, { ...SEEDED, files: JSON.stringify(SEEDED.files) }],
    'ops:seed-check.sh': [{ ...CHECKED, ok: 'true' }, { ...CHECKED, files: JSON.stringify(CHECKED.files) }],
    'ops:return-to-main.sh': [{ parameter: '{"onMain": true, "left": "seed/P-10-r1"}' }, { onMain: 'true' }],
  }
  for (const [key, answers] of Object.entries(answered)) {
    for (const answer of answers) assert.ok(!conforms(answer, schemaOf(key)), `${key} refuses ${JSON.stringify(answer)}`)
  }
})
