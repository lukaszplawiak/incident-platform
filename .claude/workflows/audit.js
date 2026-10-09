export const meta = {
  name: 'audit',
  description: 'Periodic audit of the review panel: one analyst per dimension in parallel, one report for the owner with at most 8 recommendations. Changes nothing but .ai/audit/.',
  phases: ['Collect', 'Analyse', 'Report'],
}

// ============================================================================================
// Run every N shipped items (the autopilot's preflight stops with "audit due"), in an autopilot session:
//   claude --settings .claude/settings.autopilot.json   then   /audit   with args
//   { since: 'YYYY-MM-DD', date: 'YYYY-MM-DD', target: 'reviewers', dimensions?: ['security', …] }
// `since` = the date of the previous report (or the first autopilot PR); `date` = today. Both are
// passed in because a workflow script cannot read the clock (relaunch must repeat the same calls).
// The owner then ticks decisions in the report and runs /apply-audit in a normal session.
// Only target 'reviewers' exists today; audits of the implementer and the other agents follow the
// same shape once this one has run a few cycles (.ai/rules/audit.md).
// See backlog-autopilot.js for AGENT_TYPE_OPTION and runAll(): verify both before the first run.
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
const ALL = ['general', 'architecture', 'security', 'performance', 'migration', 'k8s', 'docs']

const as = (type, prompt, opts = {}) => agent(prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })
const runAll = (thunks) => parallel(thunks)

const DATE = /^\d{4}-\d{2}-\d{2}$/
if (!DATE.test(args?.since ?? '') || !DATE.test(args?.date ?? '')) return { outcome: 'NOT_STARTED', reason: 'pass args.since and args.date (YYYY-MM-DD)' }
const target = args.target ?? 'reviewers'
if (target !== 'reviewers') return { outcome: 'NOT_STARTED', reason: `no audit defined yet for target ${target}` }
const dimensions = Array.isArray(args.dimensions) && args.dimensions.length ? args.dimensions : ALL

phase('Collect')
const data = await as('factory-ops', `Run exactly: scripts/factory/audit-data.sh '${args.since}'`, {
  schema: { type: 'object', required: ['file', 'prs'], properties: { file: { type: 'string' }, prs: { type: 'number' }, prsWithHumanLabels: { type: 'number' }, transcriptsDir: { type: 'string' } } },
  label: 'audit data',
})
if (!data) return { outcome: 'FAILED', reason: 'audit-data.sh gave no result' }
if (data.prs === 0) return { outcome: 'NOTHING_TO_AUDIT', reason: `no autopilot PR since ${args.since}` }
log(`${data.prs} autopilot PRs since ${args.since}, ${data.prsWithHumanLabels} with owner labels`)
if (data.prsWithHumanLabels === 0) log('No PR carries a human:* label: confidence cannot exceed medium, and no security/architecture relaxation is possible this cycle.')

phase('Analyse')
const results = await runAll(dimensions.map((dim) => () => as('audit-reviewers', [
  `Audit the review dimension \`${dim}\` (agent review-${dim}) from ${args.since} to ${args.date}.`,
  `Audit data file: ${data.file}. Transcripts directory (may be unreadable from this session): ${data.transcriptsDir}.`,
  `Answer with the JSON your definition specifies, recommendations in the format of .ai/rules/audit.md, ids R-${args.date}-<dimension>-NN.`,
].join('\n'), { schema: { type: 'object', required: ['dimension', 'findings', 'recommendations'] }, label: `audit ${dim}` })))

const ok = results.filter(Boolean)
if (ok.length < results.length) log(`${results.length - ok.length} analyst(s) gave no answer; the report says which.`)

phase('Report')
const report = await as('audit-synthesis', [
  `Write the audit report for date ${args.date}, target ${target}, period ${args.since} to ${args.date}.`,
  `Dimensions without an analyst result: ${dimensions.filter((d, i) => !results[i]).join(', ') || 'none'}.`,
  `Per-dimension results (JSON): ${JSON.stringify(ok)}`,
].join('\n'), { schema: { type: 'object', required: ['report'], properties: { report: { type: 'string' }, recommendations: { type: 'number' } } } })
if (!report) return { outcome: 'FAILED', reason: 'synthesis gave no answer', results: ok }

await as('factory-ops', `Run exactly: scripts/factory/notify.sh 'audit-report' '${args.date}'`, { schema: { type: 'object' }, label: 'notify' })
return { outcome: 'REPORTED', report: report.report, recommendations: report.recommendations, next: `review the decision boxes, then run /apply-audit ${report.report}` }
