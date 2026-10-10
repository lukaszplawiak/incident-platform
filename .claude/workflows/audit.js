export const meta = {
  name: 'audit',
  description: 'Periodic audit: the review panel (one analyst per dimension) and the whole pipeline (one analyst tracing each case through every stage), one report per target with at most 8 recommendations. Changes nothing but .ai/audit/.',
  phases: ['Collect', 'Analyse', 'Report'],
}

// ============================================================================================
// Run every N shipped items (the autopilot's preflight stops with "audit due"), in an autopilot session:
//   claude --settings .claude/settings.autopilot.json   then   /audit   with args
//   { since: 'YYYY-MM-DD', date: 'YYYY-MM-DD', target?: 'all' | 'reviewers' | 'pipeline', dimensions?: ['security', …] }
// `since` = the date of the previous report (or the first autopilot PR); `date` = today. Both are
// passed in because a workflow script cannot read the clock (relaunch must repeat the same calls).
// The owner then ticks decisions in the report and runs /apply-audit in a normal session.
// Targets: 'reviewers' (one analyst per review dimension) and 'pipeline' (backlog #0-121: one analyst that
// traces every case — an item that did not go through cleanly — from the stage that introduced the problem to
// the one that detected it, and says why each stage in between let it through). 'all' (the default) runs both,
// so the pipeline audit keeps the reviewers' cadence (every N shipped items) and writes its own report.
// See backlog-autopilot.js for AGENT_TYPE_OPTION and runAll(): verify both before the first run.
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
const ALL = ['general', 'architecture', 'security', 'performance', 'migration', 'k8s', 'docs']

const REPORT = { type: 'object', required: ['report'], properties: { report: { type: 'string' }, recommendations: { type: 'number' } } }
const as = (type, prompt, opts = {}) => agent(prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })
const runAll = (thunks) => parallel(thunks)

const DATE = /^\d{4}-\d{2}-\d{2}$/
if (!DATE.test(args?.since ?? '') || !DATE.test(args?.date ?? '')) return { outcome: 'NOT_STARTED', reason: 'pass args.since and args.date (YYYY-MM-DD)' }
const target = args.target ?? 'all'
if (!['all', 'reviewers', 'pipeline'].includes(target)) return { outcome: 'NOT_STARTED', reason: `no audit defined for target ${target}` }
const dimensions = Array.isArray(args.dimensions) && args.dimensions.length ? args.dimensions : ALL

phase('Collect')
const data = await as('factory-ops', `Run exactly: scripts/factory/audit-data.sh '${args.since}'`, {
  schema: { type: 'object', required: ['file', 'prs'], properties: { file: { type: 'string' }, prs: { type: 'number' }, prsWithHumanLabels: { type: 'number' }, unverifiedHumanLabels: { type: 'number' }, ownerIsAutopilot: { type: 'boolean' }, owner: { type: ['string', 'null'] }, readyPrs: { type: 'number' }, cases: { type: 'number' }, transcriptsDir: { type: 'string' } } },
  label: 'audit data',
})
if (!data) return { outcome: 'FAILED', reason: 'audit-data.sh gave no result' }
// A pipeline case can come from the run history or an escaped defect alone, with no autopilot PR in the period.
if (data.prs === 0 && !data.cases) return { outcome: 'NOTHING_TO_AUDIT', reason: `no autopilot PR and no pipeline case since ${args.since}` }
log(`${data.prs} autopilot PRs since ${args.since}, ${data.prsWithHumanLabels} with owner labels; ${data.cases ?? 0} pipeline cases, ${data.readyPrs ?? 0} /ready PRs`)
if (data.prsWithHumanLabels === 0) log('No PR carries a verified owner label (human:* added by the owner): confidence cannot exceed medium, and no security/architecture relaxation is possible this cycle.')
// Owner labels count only when the owner added them (backlog #0-126); what did not count goes into every report.
const labelWarnings = [
  data.owner == null ? 'The repository owner could not be read: no human:* label counts as owner confirmation this cycle.' : '',
  data.ownerIsAutopilot ? "Autopilot PRs are authored by the owner's own login, so the autopilot acts with the owner's token and who added a label proves nothing: no human:* label counts this cycle; the report must say so in its Summary." : '',
  data.unverifiedHumanLabels > 0 ? `${data.unverifiedHumanLabels} human:* label(s) were not added by the owner (unverifiedLabels in the data file): they count for nothing; list them in the report's Evidence.` : '',
].filter(Boolean)
for (const w of labelWarnings) log(w)
const labelNote = `Owner labels: ${labelWarnings.join(' ') || 'every human:* label in the data was added by the owner.'}`
const reports = []
const failures = []   // a target that failed: recorded, and the other target still runs (they are independent)

phase('Analyse')
if ((target === 'all' || target === 'reviewers') && data.prs === 0) log(`No autopilot PR since ${args.since}: no reviewers' report.`)
if ((target === 'all' || target === 'reviewers') && data.prs > 0) {
  const results = await runAll(dimensions.map((dim) => () => as('audit-reviewers', [
    `Audit the review dimension \`${dim}\` (agent review-${dim}) from ${args.since} to ${args.date}.`,
    `Audit data file: ${data.file}. Transcripts directory (may be unreadable from this session): ${data.transcriptsDir}.`,
    `Answer with the JSON your definition specifies, recommendations in the format of .ai/rules/audit.md, ids R-${args.date}-<dimension>-NN.`,
  ].join('\n'), { schema: { type: 'object', required: ['dimension', 'findings', 'recommendations'] }, label: `audit ${dim}` })))

  const ok = results.filter(Boolean)
  if (ok.length < results.length) log(`${results.length - ok.length} analyst(s) gave no answer; the report says which.`)

  phase('Report')
  const report = await as('audit-synthesis', [
    `Write the audit report for date ${args.date}, target reviewers, period ${args.since} to ${args.date}.`,
    `Dimensions without an analyst result: ${dimensions.filter((d, i) => !results[i]).join(', ') || 'none'}.`,
    labelNote,
    `Per-dimension results (JSON): ${JSON.stringify(ok)}`,
  ].join('\n'), { schema: REPORT })
  if (report) reports.push({ target: 'reviewers', ...report })
  else failures.push({ target: 'reviewers', reason: 'synthesis gave no answer', results: ok })
}

if (target === 'all' || target === 'pipeline') {
  if (!data.cases) {
    log(`No pipeline case since ${args.since}: every autopilot item went through cleanly; no pipeline report.`)
  } else {
    phase('Analyse')
    const traced = await as('audit-pipeline', [
      `Audit the whole pipeline from ${args.since} to ${args.date} (backlog #0-121).`,
      `Audit data file: ${data.file} (its "cases" are the items to trace). Transcripts directory (may be unreadable from this session): ${data.transcriptsDir}.`,
      `Answer with the JSON your definition specifies, recommendations in the format of .ai/rules/audit.md, ids R-${args.date}-pipeline-NN.`,
    ].join('\n'), { schema: { type: 'object', required: ['cases', 'findings', 'recommendations'] }, label: 'audit pipeline' })
    if (!traced) {
      failures.push({ target: 'pipeline', reason: 'the pipeline analyst gave no answer' })
    } else {
      phase('Report')
      const pipelineReport = await as('audit-synthesis', [
        `Write the audit report for date ${args.date}, target pipeline, period ${args.since} to ${args.date}.`,
        `Pipeline analyst result (JSON): ${JSON.stringify(traced)}`,
        labelNote,
      ].join('\n'), { schema: REPORT })
      if (pipelineReport) reports.push({ target: 'pipeline', ...pipelineReport })
      else failures.push({ target: 'pipeline', reason: 'synthesis gave no answer', traced })
    }
  }
}

if (!reports.length && failures.length) return { outcome: 'FAILED', failures }
if (!reports.length) return { outcome: 'NOTHING_TO_AUDIT', reason: `nothing to report for target ${target} since ${args.since}` }
await as('factory-ops', `Run exactly: scripts/factory/notify.sh 'audit-report' '${args.date}'`, { schema: { type: 'object' }, label: 'notify' })
return { outcome: failures.length ? 'PARTIAL' : 'REPORTED', reports, failures, next: `review the decision boxes, then run /apply-audit <report> for each of: ${reports.map((r) => r.report).join(', ')}` }
