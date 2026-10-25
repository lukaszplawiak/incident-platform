export const meta = {
  name: 'seed-bugs',
  description: 'Measures the review panel against known answers: plants one defect pattern into a throwaway seed/ branch built on a merged change, runs the panel on it, records who caught it.',
  phases: ['Seed', 'Review', 'Record'],
}

// ============================================================================================
// Run in an autopilot session, from a clean main, args
//   { pattern: 'P-01', commit: '<sha of a merged autopilot change>', runId: '<any unique id>' }
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

phase('Seed')
const truth = await as('defect-seeder', `Pattern ${args.pattern}. Merged change: commit ${args.commit}. Run id ${args.runId}.`, {
  schema: { type: 'object', properties: { skipped: { type: 'boolean' }, blocked: { type: 'boolean' }, reason: { type: 'string' },
    branch: { type: 'string' }, pattern: { type: 'string' }, dimension: { type: 'string' }, rule: { type: 'string' }, file: { type: 'string' },
    line: { type: 'number' }, description: { type: 'string' }, baseCommit: { type: 'string' } } },
})
// Fixed (backlog #0-170): the first run on record came back SKIPPED with no reason, measuring nothing: the seeder's
// commands had been refused (`git -C …` matches none of the session's allow rules), and a blocked tool and "this
// pattern does not fit" were one answer. Now they are two, a skip without its reason counts as a failure, and so does
// a ground truth the Record phase could not use.
if (!truth) return { outcome: 'FAILED', reason: 'defect-seeder gave no answer' }
const why = (r) => String(r ?? 'no reason given').slice(0, 300)
if (truth.blocked) return { outcome: 'FAILED', reason: `defect-seeder was blocked: ${why(truth.reason)}` }
if (truth.skipped) {
  return truth.reason ? { outcome: 'SKIPPED', reason: why(truth.reason) } : { outcome: 'FAILED', reason: 'defect-seeder skipped without a reason (blocked?)' }
}
// The Record phase matches findings to the file and line, and the panel reviews the branch: both must be this run's
// (review round 1: without a line every catch read as a miss; another seed/ branch would be a stale one).
if (truth.branch !== `seed/${args.pattern}-${args.runId}` || !truth.file || typeof truth.line !== 'number' || !truth.rule
    || !PANEL.includes(truth.dimension) || !String(truth.baseCommit ?? '').startsWith(args.commit)) {
  return { outcome: 'FAILED', reason: `defect-seeder's ground truth is incomplete: ${JSON.stringify(truth).slice(0, 300)}` }
}

phase('Review')
// The reviewers see the merged change plus the planted defect, as one change: base = the merged commit's parent.
const verdicts = await runAll(PANEL.map((dim) => () => as(`review-${dim}`, [
  `Autopilot review. Branch ${truth.branch}. mergeBase: ${args.commit}^. round: 1.`,
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
await as('audit-synthesis', [
  'Append one row to .ai/audit/benchmark/results.md (create it with this header if missing:',
  '`| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |` and its separator).',
  `Row data (JSON): ${JSON.stringify(result)}. Do not write anything else.`,
].join('\n'), { schema: { type: 'object' }, label: 'record result' })

return { outcome: 'MEASURED', ...result, cleanup: `git switch main && git branch -D ${truth.branch}` }
