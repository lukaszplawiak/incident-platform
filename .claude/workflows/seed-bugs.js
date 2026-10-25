export const meta = {
  name: 'seed-bugs',
  description: 'Measures the review panel against known answers: plants one defect pattern into a throwaway seed/ branch built on a merged change, runs the panel on it, records who caught it.',
  phases: ['Seed', 'Review', 'Record'],
}

// ============================================================================================
// Run in an autopilot session, from a clean main at origin/main (the last run's results.md committed), args
//   { pattern: 'P-01', commit: '<sha of a merged autopilot change>', runId: '<any unique id>' }
// The commit's code must still apply to main (seed-branch.sh refuses it otherwise): choose a recent one.
// The script cannot pick at random (relaunch must repeat the same calls): choose the pattern and the
// commit yourself, rotating through .ai/audit/benchmark/patterns.md. Never pushed, never merged; the
// ground truth stays out of the branch, so reviewers cannot see it.
// See backlog-autopilot.js for AGENT_TYPE_OPTION and runAll(): verify both before the first run.
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
const PANEL = ['general', 'architecture', 'security', 'performance', 'migration', 'docs']
const as = (type, prompt, opts = {}) => agent(prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })
const runAll = (thunks) => parallel(thunks)

const SAFE = /^[A-Za-z0-9._-]+$/
if (![args?.pattern, args?.commit, args?.runId].every((v) => typeof v === 'string' && SAFE.test(v))) return { outcome: 'NOT_STARTED', reason: 'pass args.pattern, args.commit and args.runId' }

// Every factory script runs through the factory-ops agent, as in backlog-autopilot.js; the arguments are SAFE-checked.
const ops = (script, argv, label) => as('factory-ops', `Run exactly: scripts/factory/${script}${argv.map((a) => ` '${a}'`).join('')}`,
  { schema: { type: 'object' }, label: label ?? script })
// The run ends on main whatever happens, so the session and the owner's tree go on with main's hooks and agents. When
// return-to-main.sh cannot (a dirty tree, left for the owner), the outcome says where the tree was left (review round 1
// of #0-173: a run that reported nothing of it left the next one refused).
const backToMain = () => ops('return-to-main.sh', [], 'back to main')
const notOnMain = (back) => back?.onMain === true ? null
  : { leftOn: String(back?.branch ?? 'unknown'), returnToMain: String(back?.reason ?? JSON.stringify(back)).slice(0, 300) }
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
const seed = await ops('seed-branch.sh', [args.pattern, args.runId, args.commit], 'seed branch')
if (!seed) return finish({ outcome: 'FAILED', reason: 'seed-branch.sh gave no answer' })
const SHA = /^[0-9a-f]{40}$/
if (seed.ok === true) made = `seed/${args.pattern}-${args.runId}`
if (seed.ok !== true || seed.branch !== `seed/${args.pattern}-${args.runId}` || !SHA.test(String(seed.base))
    || !SHA.test(String(seed.head)) || !Array.isArray(seed.files) || !seed.files.length) {
  return finish({ outcome: 'FAILED', reason: `no seed branch: ${String(seed.reason ?? JSON.stringify(seed)).slice(0, 300)}` })
}
const truth = await as('defect-seeder', [
  `Pattern ${args.pattern}. Run id ${args.runId}. The branch ${seed.branch} is checked out: it holds merged change ${args.commit}`,
  `as its last commit, on the base ${seed.base}. Plant the pattern in one of the change's files: ${seed.files.join(', ')}.`,
].join('\n'), {
  schema: { type: 'object', properties: { skipped: { type: 'boolean' }, blocked: { type: 'boolean' }, reason: { type: 'string' },
    branch: { type: 'string' }, pattern: { type: 'string' }, dimension: { type: 'string' }, rule: { type: 'string' }, file: { type: 'string' },
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
// (review round 1 of #0-170: without a line every catch read as a miss), and the file one of the change's.
if (truth.branch !== seed.branch || !seed.files.includes(truth.file) || typeof truth.line !== 'number' || !truth.rule
    || !PANEL.includes(truth.dimension)) {
  return finish({ outcome: 'FAILED', reason: `defect-seeder's ground truth is incomplete: ${JSON.stringify(truth).slice(0, 300)}` })
}
// The panel runs on the checked-out tree, not on the seeder's word (review round 1 of #0-173): the tree must be on the
// seed branch, clean, with the seeder's one commit on top of the change, touching the change's files only.
const check = await ops('seed-check.sh', [args.pattern, args.runId, seed.base, seed.head], 'seed check')
if (check?.ok !== true || !Array.isArray(check.files) || !check.files.includes(truth.file)) {
  return finish({ outcome: 'FAILED', reason: `the seed branch is not as the seeder says: ${String(check?.reason ?? JSON.stringify(check)).slice(0, 300)}` })
}

phase('Review')
// The reviewers see the merged change plus the planted defect, as one change: base = the seed branch's base commit.
const verdicts = await runAll(PANEL.map((dim) => () => as(`review-${dim}`, [
  `Autopilot review. Branch ${truth.branch}. mergeBase: ${seed.base}. round: 1.`,
  'The test gate was not run for this branch. Answer with the verdict JSON only.',
].join('\n'), { schema: { type: 'object', required: ['dimension', 'verdict', 'blocking'] }, label: `${dim}` })))

// The dimension of each verdict is the one we asked for (by position).
verdicts.forEach((v, i) => { if (v) v.dimension = PANEL[i] })

phase('Record')
const near = (f) => f.file === truth.file && (typeof f.line !== 'number' || Math.abs(f.line - (truth.line ?? 0)) <= 5)
const caughtBy = verdicts.filter(Boolean).filter((v) => [...(v.blocking ?? []), ...(v.nonBlocking ?? [])].some(near)).map((v) => v.dimension)
const result = {
  runId: args.runId, pattern: args.pattern, rule: truth.rule, expected: truth.dimension,
  caughtByExpected: caughtBy.includes(truth.dimension), caughtBy, blockingByExpected:
    verdicts.filter(Boolean).some((v) => v.dimension === truth.dimension && (v.blocking ?? []).some(near)),
  noAnswer: PANEL.filter((d, i) => !verdicts[i]),
}
// Back on main before the row is written: results.md then dirties main, not the seed branch, and the owner commits it
// before the next run (seed-branch.sh names it when it refuses), so no row ever rides onto a seed branch.
const left = notOnMain(await backToMain())
if (left) return { outcome: 'MEASURED', recorded: false, ...result, ...left, reason: 'not back on main: the row was not recorded' }
await as('audit-synthesis', [
  'Append one row to .ai/audit/benchmark/results.md (create it with this header if missing:',
  '`| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |` and its separator).',
  `Row data (JSON): ${JSON.stringify(result)}. Do not write anything else.`,
].join('\n'), { schema: { type: 'object' }, label: 'record result' })

return { outcome: 'MEASURED', recorded: true, ...result,
  cleanup: `git branch -D ${seed.branch}; commit .ai/audit/benchmark/results.md before the next run` }
