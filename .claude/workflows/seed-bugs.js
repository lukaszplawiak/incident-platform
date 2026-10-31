export const meta = {
  name: 'seed-bugs',
  description: 'Measures the review panel against known answers: plants one defect pattern into a throwaway branch built on a merged change, runs the panel on it, records who caught it.',
  phases: ['Seed', 'Review', 'Record'],
}

// ============================================================================================
// Run in an autopilot session, from a clean main at origin/main, args
//   { pattern: 'P-01', commit: '<sha of a merged autopilot change>', runId: '<any unique id>' }
// or the same three words in that order, as `/seed-bugs P-01 <sha> <run id>` passes them (one string). A `C-` pattern
// (backlog #0-180) is a clean run: a correct change planted, its false alarms recorded in .ai/runs/benchmark/clean.md.
// The commit's code must still apply to main (seed-branch.sh refuses it otherwise): choose a recent one.
// The script cannot pick at random (relaunch must repeat the same calls): choose the pattern and the
// commit yourself, rotating through .ai/audit/benchmark/patterns.md. Never pushed, never merged; the
// ground truth stays out of the branch, so reviewers cannot see it. Each run's row goes to the gitignored
// .ai/runs/benchmark/results.md; after a series, its rows reach .ai/audit/benchmark/results.md in one pull request.
// See backlog-autopilot.js for AGENT_TYPE_OPTION and runAll(): verify both before the first run.
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
// Every dimension runs on every seeded branch, whether or not the change reaches its area. The autopilot differs here:
// it adds migration and k8s only when the change touches their paths (changed-paths.sh). A reviewer told nothing must
// decide itself that the change is not its own; for a pattern outside migration's or k8s's area their "Also caught by"
// or "No answer" entries have no autopilot counterpart. k8s joined with #0-115's k8s.md (P-20..P-22). The same list is
// seed-record.sh's DIMENSIONS, audit.js's ALL and backlog-autopilot.js's RULE_FILE_OF (a test compares all four).
const PANEL = ['general', 'architecture', 'security', 'performance', 'migration', 'docs', 'k8s']
const as = (type, prompt, opts = {}) => agent(prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })
const runAll = (thunks) => parallel(thunks)

// Fixed (backlog #0-174): `/seed-bugs P-10 <sha> <run id>` hands the script its words as one string, and the run
// stopped NOT_STARTED; the words are read in the order of the object's keys, and checked the same way.
const SAFE = /^[A-Za-z0-9._-]+$/
const words = typeof args === 'string' ? args.trim().split(/\s+/) : null
const params = words ? (words.length === 3 ? { pattern: words[0], commit: words[1], runId: words[2] } : {}) : (args ?? {})
if (![params.pattern, params.commit, params.runId].every((v) => typeof v === 'string' && SAFE.test(v))) {
  return { outcome: 'NOT_STARTED', reason: 'pass args { pattern, commit, runId }, or the words "<pattern> <commit> <run id>"' }
}

// Fixed (backlog #0-176): the panel's schema named its required keys only, so each verdict came back with every value
// a string (2026-10-25, P-12) and the Record phase threw. Typed as backlog-autopilot.js types its VERDICT and FINDING
// (a workflow cannot import), with the file and line of a non-blocking finding too: the Record phase matches both.
// Only `issue` is required of a finding: a measure must not lose a catch because a reviewer left out an id (the
// non-blocking findings of the review procedure carry none).
const FINDING = {
  type: 'object', required: ['issue'],
  properties: { id: { type: 'string' }, ruleId: { type: ['string', 'null'] }, file: { type: ['string', 'null'] },
    line: { type: ['number', 'null'] }, issue: { type: 'string' }, impact: { type: 'string' }, fix: { type: 'string' } },
}
const VERDICT = {
  type: 'object', required: ['dimension', 'verdict', 'blocking', 'nonBlocking'],
  properties: {
    dimension: { type: 'string' }, round: { type: 'number' },
    verdict: { type: 'string', enum: ['APPROVE', 'CHANGES_REQUESTED', 'NEEDS_HUMAN'] },
    blocking: { type: 'array', items: FINDING }, nonBlocking: { type: 'array', items: FINDING },
    resolved: { type: 'array', items: { type: 'string' } }, outOfScope: { type: 'array', items: { type: 'string' } },
    ruleGap: { type: 'array', items: { type: 'string' } }, unverified: { type: 'array', items: { type: 'string' } },
  },
}

// Every factory script runs through the factory-ops agent, as in backlog-autopilot.js; the arguments are SAFE-checked.
// Fixed (backlog #0-174): with the bare `{ type: 'object' }` the agent answered seed-branch.sh's JSON with every value
// a string ("ok": "true", "files": "[…]") and wrapped return-to-main.sh's in a "parameter" key, so the first run after
// #0-173 failed on a branch it had made. Each script's answer now has its fields typed and nothing else: what the
// script prints, or factory-ops' own error answer.
const ERROR_FIELDS = { error: { type: 'string' }, exitCode: { type: 'number' } }
const answerOf = (fields) => ({ type: 'object', additionalProperties: false, properties: { ...fields, ...ERROR_FIELDS } })
const SHA_FIELD = { type: 'string' }
const FILES_FIELD = { type: 'array', items: { type: 'string' } }
const ANSWERS = {
  'seed-branch.sh': answerOf({ ok: { type: 'boolean' }, reason: { type: 'string' }, branch: { type: 'string' }, base: SHA_FIELD,
    head: SHA_FIELD, files: FILES_FIELD }),
  'seed-check.sh': answerOf({ ok: { type: 'boolean' }, reason: { type: 'string' }, files: FILES_FIELD }),
  'seed-fold.sh': answerOf({ ok: { type: 'boolean' }, reason: { type: 'string' }, head: SHA_FIELD }),
  'seed-record.sh': answerOf({ appended: { type: 'boolean' }, reason: { type: 'string' }, file: { type: 'string' } }),
  'return-to-main.sh': answerOf({ onMain: { type: 'boolean' }, branch: { type: 'string' }, left: { type: 'string' },
    reason: { type: 'string' } }),
}
// Every argument must be pattern-checked before it gets here (SAFE, SHA, a rule id, a known dimension): each is put
// between single quotes without escaping, so a quote in an unchecked value, an agent's field above all, would end it.
const ops = (script, argv, label) => as('factory-ops', `Run exactly: scripts/factory/${script}${argv.map((a) => ` '${a}'`).join('')}`,
  { schema: ANSWERS[script], label: label ?? script })
// The run ends on main whatever happens, so the session and the owner's tree go on with main's hooks and agents. When
// return-to-main.sh cannot (a dirty tree, left for the owner), the outcome says where the tree was left (review round 1
// of #0-173: a run that reported nothing of it left the next one refused).
// It never throws (review round 1 of #0-176): finish() calls it from the catch of measure(), where a second error would
// escape and leave the tree on the seed branch with no outcome; a failure reads as "not on main", with its reason.
const backToMain = async () => {
  try {
    return await ops('return-to-main.sh', [], 'back to main')
  } catch (e) {
    return { onMain: false, reason: `return-to-main.sh did not answer: ${String(e?.message ?? e).slice(0, 200)}` }
  }
}
const notOnMain = (back) => back?.onMain === true ? null
  : { leftOn: String(back?.branch ?? 'unknown'), returnToMain: String(back?.reason ?? JSON.stringify(back)).slice(0, 300) }
// Fixed (backlog #0-177): the branch was `seed/<pattern>-<run id>` and the panel's prompt named it, so every reviewer
// was told that the change was a test, and which pattern (P-10..P-12; their transcripts show no read of the catalogue,
// but the measure must not rest on that). The name now says nothing: FNV-1a of the pattern and the run id, eight hex
// digits, the same on a relaunch (no Math.random in the runtime), and not readable back by a reviewer.
const seedBranchOf = (pattern, runId) => {
  let h = 0x811c9dc5
  for (const c of `${pattern}\n${runId}`) h = Math.imul(h ^ c.codePointAt(0), 0x01000193) >>> 0
  return `review/${h.toString(16).padStart(8, '0')}`
}
const BRANCH = seedBranchOf(params.pattern, params.runId)
// Once seed-branch.sh has made the branch, a failed run says how to delete it too (review round 2 of #0-173: a rerun
// with the same run id is refused while it exists). The name is the one the run asked for, not an agent's answer.
let made = null
const finish = async (outcome) => ({ ...outcome, ...(made && outcome.outcome !== 'MEASURED' ? { cleanup: `git branch -D ${made}` } : {}),
  ...notOnMain(await backToMain()) })

phase('Seed')
// Fixed (backlog #0-173): the seeder made its branch on the merged change itself, in the working tree the session (and
// the devcontainer, and the owner's host) runs on, so the run went on with that commit's hooks, settings and agents
// (2026-10-25: 253e5b2a's guard refused the seeder, its tree had no read-scope.sh). seed-branch.sh builds the branch
// from main instead (the change's code reverted as the base, then back), and refuses a tree that is not a clean main.
const seed = await ops('seed-branch.sh', [BRANCH, params.commit], 'seed branch')
if (!seed) return finish({ outcome: 'FAILED', reason: 'seed-branch.sh gave no answer' })
const SHA = /^[0-9a-f]{40}$/
if (seed.ok === true) made = BRANCH
if (seed.ok !== true || seed.branch !== BRANCH || !SHA.test(String(seed.base))
    || !SHA.test(String(seed.head)) || !Array.isArray(seed.files) || !seed.files.length) {
  return finish({ outcome: 'FAILED', reason: `no seed branch: ${String(seed.reason ?? JSON.stringify(seed)).slice(0, 300)}` })
}
// Fixed (backlog #0-176): an error after the branch existed (2026-10-25, P-12: a verdict's `blocking` came back a string
// and `.some` threw in the Record phase) ended the script on the seed branch, with no row and no cleanup named. Every
// step after the branch is made runs in measure(), and an error there ends the run like any failure: back on main, the
// branch named for deletion.
// A clean run (backlog #0-180): a `C-` pattern is a correct change that a careless reading of a rule would block, the
// rule's deliberate exception planted with its reason. The kind comes from the pattern id the owner passed, never from
// the seeder's answer; the panel's prompt is the same as for a defect, so a reviewer cannot tell the two apart. A clean
// run needs the seeder to say `kind: "clean"` itself (review round 2): one that left it out may have planted a defect,
// and a dimension that rightly blocked it would read as a false alarm. A defect run may leave it out.
const CLEAN = /^C-[0-9]+$/.test(params.pattern)
async function measure() {
  const truth = await as('defect-seeder', [
    `Pattern ${params.pattern}. Run id ${params.runId}. The branch ${seed.branch} is checked out: it holds merged change ${params.commit}`,
    `as its last commit, on the base ${seed.base}. Plant the pattern in one of the change's files: ${seed.files.join(', ')}.`,
    'Leave the edit uncommitted.',
  ].join('\n'), {
    schema: { type: 'object', properties: { skipped: { type: 'boolean' }, blocked: { type: 'boolean' }, reason: { type: 'string' },
      branch: { type: 'string' }, pattern: { type: 'string' }, kind: { type: 'string', enum: ['defect', 'clean'] },
      dimension: { type: 'string' }, rule: { type: 'string' }, file: { type: 'string' },
      line: { type: 'number' }, description: { type: 'string' } } },
  })
  // Fixed (backlog #0-170): the first run on record came back SKIPPED with no reason, measuring nothing: the seeder's
  // commands had been refused (`git -C …` matches none of the session's allow rules), and a blocked tool and "this
  // pattern does not fit" were one answer. Now they are two, a skip without its reason counts as a failure, and so does
  // a ground truth the Record phase could not use.
  if (!truth) return finish({ outcome: 'FAILED', reason: 'defect-seeder gave no answer' })
  const why = (r) => String(r ?? 'no reason given').slice(0, 300)
  if (truth.blocked) return finish({ outcome: 'FAILED', reason: `defect-seeder was blocked: ${why(truth.reason)}` })
  if (truth.skipped) {
    return finish(truth.reason ? { outcome: 'SKIPPED', reason: why(truth.reason) } : { outcome: 'FAILED', reason: 'defect-seeder skipped without a reason (blocked?)' })
  }
  // The Record phase matches findings to the file and line, and the panel reviews the branch: both must be this run's
  // (review round 1 of #0-170: without a line every catch read as a miss), and the file one of the change's. The rule
  // reaches seed-record.sh's command line (#0-177), so it must be a rule id and nothing else (FAC-A02's letter after
  // the dash too, #0-185, as seed-record.sh and check-rule-claims.rb read it). A FAC- truth is not measured here
  // today: the panel prompt below does not say that factory.md applies, and seed-branch.sh refuses an Owner-only host,
  // so such a row would read as a miss. A factory.md measure is run by hand (ADR-0044 decision 6).
  if (truth.branch !== seed.branch || !seed.files.includes(truth.file) || typeof truth.line !== 'number'
      || !/^[A-Z][A-Z0-9]*-[A-Z]?[0-9]+$/.test(String(truth.rule)) || !PANEL.includes(truth.dimension)
      || (CLEAN ? truth.kind !== 'clean' : (truth.kind ?? 'defect') !== 'defect')) {
    return finish({ outcome: 'FAILED', reason: `defect-seeder's ground truth is incomplete: ${JSON.stringify(truth).slice(0, 300)}` })
  }
  // The panel runs on the checked-out tree, not on the seeder's word (review round 1 of #0-173): the tree must be on the
  // seed branch at the change's commit, with the seeder's edit uncommitted in the change's files only. Fixed (backlog
  // #0-172): the seeder committed, with a Bash and an Edit no hook held (it could switch, write any file, append to a
  // migration); now its hooks hold it to read-only git and to the change's files (readonly-bash.sh, write-scope.sh
  // --seed), and a factory script makes the commit (ADR-0047).
  const check = await ops('seed-check.sh', [BRANCH, seed.base, seed.head], 'seed check')
  if (check?.ok !== true || !Array.isArray(check.files) || !check.files.includes(truth.file)) {
    return finish({ outcome: 'FAILED', reason: `the seed branch is not as the seeder says: ${String(check?.reason ?? JSON.stringify(check)).slice(0, 300)}` })
  }
  // Fixed (backlog #0-177): the defect sat in a commit of its own on top of the change, so `git show HEAD` was the
  // planted lines alone. seed-fold.sh makes the change and the edit one commit (the ground truth's line unchanged).
  // The label 'seed fold' is read back from the run's journal by scripts/factory-admin/seed-scan.sh (the reviewed
  // branch's head, backlog #0-179): renamed here, the scan reports a read of that commit as a hit again.
  const fold = await ops('seed-fold.sh', [BRANCH, seed.base, seed.head], 'seed fold')
  if (fold?.ok !== true || !SHA.test(String(fold.head))) {
    return finish({ outcome: 'FAILED', reason: `the seeder's edit was not folded: ${String(fold?.reason ?? JSON.stringify(fold)).slice(0, 300)}` })
  }

  phase('Review')
  // The reviewers see the merged change plus the planted defect, as one change: base = the seed branch's base commit.
  const verdicts = await runAll(PANEL.map((dim) => () => as(`review-${dim}`, [
    `Autopilot review. Branch ${BRANCH}. mergeBase: ${seed.base}. round: 1.`,
    'The test gate was not run for this branch. Answer with the verdict JSON only.',
  ].join('\n'), { schema: VERDICT, label: `${dim}` })))

  // The dimension of each verdict is the one we asked for (by position); one that is not a verdict counts as no answer.
  const answered = verdicts.map((v, i) => (v && Array.isArray(v.blocking) && Array.isArray(v.nonBlocking) ? { ...v, dimension: PANEL[i] } : null))

  phase('Record')
  const near = (f) => f.file === truth.file && (typeof f.line !== 'number' || Math.abs(f.line - (truth.line ?? 0)) <= 5)
  const caughtBy = answered.filter(Boolean).filter((v) => [...v.blocking, ...v.nonBlocking].some(near)).map((v) => v.dimension)
  // A verdict stops the change at the planted line when it lists it as blocking, or answers NEEDS_HUMAN with a finding
  // there: in the autopilot NEEDS_HUMAN stops the item too (GEN-08, ARC-01, ARC-05, ARC-06 ask for it; #0-180's Also).
  const stops = (v) => v.blocking.some(near) || (v.verdict === 'NEEDS_HUMAN' && v.nonBlocking.some(near))
  const noAnswer = PANEL.filter((d, i) => !answered[i])
  const result = CLEAN ? {
    // A clean run (#0-180): the change at the planted line is right. A dimension that stops it is a false alarm; one
    // that only notes it did what the rule asks. A blocking finding elsewhere is the host's, not this run's: recorded,
    // not counted (19 of 71 blocking findings of the defect runs before it were the host's or the plant's other half).
    runId: params.runId, pattern: params.pattern, kind: 'clean', rule: truth.rule, expected: truth.dimension,
    falseAlarmBy: answered.filter(Boolean).filter(stops).map((v) => v.dimension),
    raisedBy: answered.filter(Boolean).filter((v) => !stops(v) && [...v.blocking, ...v.nonBlocking].some(near)).map((v) => v.dimension),
    blockingElsewhere: answered.filter(Boolean).filter((v) => v.blocking.some((f) => !near(f))).map((v) => v.dimension),
    noAnswer,
  } : {
    runId: params.runId, pattern: params.pattern, rule: truth.rule, expected: truth.dimension,
    caughtByExpected: caughtBy.includes(truth.dimension), caughtBy, blockingByExpected:
      answered.filter(Boolean).some((v) => v.dimension === truth.dimension && stops(v)),
    noAnswer,
  }
  // Back on main before the row is written, so a run never ends on its branch. The row goes to the gitignored
  // .ai/runs/benchmark/results.md (backlog #0-177): it never dirties main, so the next run needs no commit first.
  const left = notOnMain(await backToMain())
  if (left) {
    return { outcome: 'MEASURED', recorded: false, ...result, ...left, reason: 'not back on main: the row was not recorded',
      cleanup: `git branch -D ${BRANCH} once the tree is back on main; record the row by hand` }
  }
  // A script writes the row (#0-177), and only its `appended` counts as recorded (#0-171's "Also": audit-synthesis's
  // answer was never checked). A measure is kept when the row cannot be written (review round 1 of #0-176): the outcome
  // carries it, for the owner to record by hand, instead of a FAILED that drops it.
  const list = (dims) => (dims.length ? dims.join(',') : '-')
  const yes = (b) => (b ? 'yes' : 'no')
  const rows = CLEAN ? '.ai/runs/benchmark/clean.md' : '.ai/runs/benchmark/results.md'
  let row
  try {
    row = await ops('seed-record.sh', CLEAN
      ? ['--clean', params.runId, params.pattern, truth.rule, truth.dimension, list(result.falseAlarmBy), list(result.raisedBy),
        list(result.blockingElsewhere), list(result.noAnswer)]
      : [params.runId, params.pattern, truth.rule, truth.dimension, yes(result.caughtByExpected),
        yes(result.blockingByExpected), list(result.caughtBy), list(result.noAnswer)], 'record result')
  } catch (e) {
    row = { appended: false, reason: String(e?.message ?? e) }
  }
  if (row?.appended !== true) {
    return { outcome: 'MEASURED', recorded: false, ...result,
      reason: `the row was not written: ${String(row?.reason ?? JSON.stringify(row)).slice(0, 200)}`,
      cleanup: `git branch -D ${BRANCH}; record the row by hand in ${rows}` }
  }

  return { outcome: 'MEASURED', recorded: true, ...result,
    cleanup: `git branch -D ${BRANCH}; after the series, copy its rows from ${rows} into ${rows.replace('.ai/runs/', '.ai/audit/')} in one pull request` }
}
try {
  return await measure()
} catch (e) {
  return finish({ outcome: 'FAILED', reason: `the run stopped on an error: ${String(e?.message ?? e).slice(0, 300)}` })
}
