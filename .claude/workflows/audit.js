export const meta = {
  name: 'audit',
  description: 'Periodic audit: the review panel (one analyst per dimension) and the whole pipeline (one analyst tracing each case through every stage), one report per target with at most 8 recommendations. Changes nothing but .ai/audit/.',
  phases: ['Collect', 'Analyse', 'Report'],
}

// ============================================================================================
// Run every N shipped items (the autopilot's preflight stops with "audit due"), in an autopilot session:
//   claude --settings .claude/settings.autopilot.json   then   /audit <since> <date> [target] [dimension…]
//   e.g. /audit 2026-10-01 2026-11-12   or   /audit 2026-10-01 2026-11-12 reviewers security docs
//   or args { since: 'YYYY-MM-DD', date: 'YYYY-MM-DD', target?: 'all' | 'reviewers' | 'pipeline', dimensions?: ['security', …] }
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
// A factory script's answer, typed as in backlog-autopilot.js (the same ERROR_FIELDS, answerOf and failureOf, which a test
// compares, as a workflow cannot import): its fields and nothing else, factory-ops' error, or _common.sh's failure. Fixed (backlog
// #0-175, PR 2): audit-data.sh's schema was typed by `required` keys only, and notify.sh's not at all.
const ERROR_FIELDS = { error: { type: 'string' }, exitCode: { type: 'number' }, ok: { type: 'boolean' },
  reasons: { type: 'array', items: { type: 'string' } } }
const answerOf = (fields) => ({ type: 'object', additionalProperties: false, properties: { ...fields, ...ERROR_FIELDS } })
const failureOf = (a) => a?.error ?? (Array.isArray(a?.reasons) && a.reasons.length ? a.reasons.join('; ') : null)
const AUDIT_DATA = answerOf({ file: { type: 'string' }, prs: { type: 'number' }, prsWithHumanLabels: { type: 'number' },
  unverifiedHumanLabels: { type: 'number' }, ownerIsAutopilot: { type: 'boolean' }, unverifiedEscapes: { type: 'number' },
  owner: { type: ['string', 'null'] }, readyPrs: { type: 'number' }, cases: { type: 'number' }, transcriptsDir: { type: 'string' } })
const NOTIFIED = answerOf({ sent: { type: 'boolean' }, reason: { type: 'string' } })
// Every factory script goes through here, so the test that no answer goes untyped sees each call.
const ops = (script, argv, schema, label) => as('factory-ops', `Run exactly: scripts/factory/${script}${argv.map((a) => ` '${a}'`).join('')}`,
  { schema, label })
const runAll = (thunks) => parallel(thunks)

const DATE = /^\d{4}-\d{2}-\d{2}$/
// Fixed (backlog #0-175, AC1): `/audit 2026-10-01 2026-11-12` hands the script its words as one string, and the run
// stopped NOT_STARTED, as `/seed-bugs`' did (#0-174, seed-bugs.js). The words are read in the order of the object's keys
// (since, date, target, then every dimension) and checked as the object's values are. A dimension is checked against
// ALL in both forms: unchecked, a mistyped one started an analyst for a dimension the panel does not have.
const USAGE = 'pass args { since, date, target?, dimensions? }, or the words "<since> <date> [all|reviewers|pipeline] [dimension…]" (dates YYYY-MM-DD)'
const words = typeof args === 'string' ? args.trim().split(/\s+/).filter(Boolean) : null
const params = words ? { since: words[0], date: words[1], target: words[2], dimensions: words.slice(3) } : (args ?? {})
if (![params.since, params.date].every((d) => typeof d === 'string' && DATE.test(d))) return { outcome: 'NOT_STARTED', reason: USAGE }
const target = params.target ?? 'all'
if (!['all', 'reviewers', 'pipeline'].includes(target)) return { outcome: 'NOT_STARTED', reason: `no audit defined for target ${String(target).slice(0, 40)}; ${USAGE}` }
const dimensions = Array.isArray(params.dimensions) && params.dimensions.length ? params.dimensions : ALL
const unknown = dimensions.filter((d) => !ALL.includes(d))
if (unknown.length) return { outcome: 'NOT_STARTED', reason: `no review dimension ${unknown.map((d) => String(d).slice(0, 40)).join(', ')} (one of ${ALL.join(', ')})` }

phase('Collect')
const data = await ops('audit-data.sh', [params.since], AUDIT_DATA, 'audit data')
if (!data) return { outcome: 'FAILED', reason: 'audit-data.sh gave no result' }
// No `required` keys: an answer that is not the script's own stops here, with its reason, not as an audit of nothing.
// Every field a decision below rests on is checked, not only the first two (review round 1 of #0-175 PR 2): a missing
// ownerIsAutopilot or label count would read as "nothing to warn of", so the owner's labels would count as confirmed,
// and a missing `cases` as "no pipeline case".
const NUMBERS = ['prs', 'prsWithHumanLabels', 'unverifiedHumanLabels', 'unverifiedEscapes', 'cases']
const missing = [...(typeof data.file === 'string' ? [] : ['file']), ...NUMBERS.filter((k) => typeof data[k] !== 'number'),
  ...(typeof data.ownerIsAutopilot === 'boolean' ? [] : ['ownerIsAutopilot'])]
if (missing.length) {
  return { outcome: 'FAILED', reason: `audit-data.sh gave no data: ${String(failureOf(data) ?? `no ${missing.join(', ')}`).slice(0, 300)}` }
}
// A pipeline case can come from the run history or an escaped defect alone, with no autopilot PR in the period.
if (data.prs === 0 && !data.cases) return { outcome: 'NOTHING_TO_AUDIT', reason: `no autopilot PR and no pipeline case since ${params.since}` }
log(`${data.prs} autopilot PRs since ${params.since}, ${data.prsWithHumanLabels} with owner labels; ${data.cases ?? 0} pipeline cases, ${data.readyPrs ?? 0} /ready PRs`)
if (data.prsWithHumanLabels === 0) log('No PR carries a verified owner label (human:* added by the owner): confidence cannot exceed medium, and no security/architecture relaxation is possible this cycle.')
// Owner labels count only when the owner added them (backlog #0-126), escape markers only when the owner's PR brought
// them to main (backlog #0-129); what did not count goes into every report.
const labelWarnings = [
  data.owner == null ? 'The repository owner could not be read: no human:* label and no escape marker counts as owner confirmation this cycle.' : '',
  data.ownerIsAutopilot ? "Autopilot PRs are authored by the owner's own login, so the autopilot acts with the owner's token and who added a label or opened a PR proves nothing: no human:* label and no escape marker counts this cycle; the report must say so in its Summary." : '',
  data.unverifiedHumanLabels > 0 ? `${data.unverifiedHumanLabels} human:* label(s) were not added by the owner (unverifiedLabels in the data file): they count for nothing; list them in the report's Evidence.` : '',
  data.unverifiedEscapes > 0 ? `${data.unverifiedEscapes} escape marker(s) did not reach main in a PR the owner opened (unverifiedEscapes in the data file): they are no case and no hard fact; list them in the report's Evidence.` : '',
].filter(Boolean)
for (const w of labelWarnings) log(w)
const labelNote = `Owner labels and escape markers: ${labelWarnings.join(' ') || 'every human:* label and escape marker in the data is vouched for by the owner.'}`
const reports = []
const failures = []   // a target that failed: recorded, and the other target still runs (they are independent)

phase('Analyse')
if ((target === 'all' || target === 'reviewers') && data.prs === 0) log(`No autopilot PR since ${params.since}: no reviewers' report.`)
if ((target === 'all' || target === 'reviewers') && data.prs > 0) {
  const results = await runAll(dimensions.map((dim) => () => as('audit-reviewers', [
    `Audit the review dimension \`${dim}\` (agent review-${dim}) from ${params.since} to ${params.date}.`,
    `Audit data file: ${data.file}. Transcripts directory (may be unreadable from this session): ${data.transcriptsDir}.`,
    `Answer with the JSON your definition specifies, recommendations in the format of .ai/rules/audit.md, ids R-${params.date}-<dimension>-NN.`,
  ].join('\n'), { schema: { type: 'object', required: ['dimension', 'findings', 'recommendations'] }, label: `audit ${dim}` })))

  const ok = results.filter(Boolean)
  if (ok.length < results.length) log(`${results.length - ok.length} analyst(s) gave no answer; the report says which.`)

  phase('Report')
  const report = await as('audit-synthesis', [
    `Write the audit report for date ${params.date}, target reviewers, period ${params.since} to ${params.date}.`,
    `Dimensions without an analyst result: ${dimensions.filter((d, i) => !results[i]).join(', ') || 'none'}.`,
    labelNote,
    `Per-dimension results (JSON): ${JSON.stringify(ok)}`,
  ].join('\n'), { schema: REPORT })
  if (report) reports.push({ target: 'reviewers', ...report })
  else failures.push({ target: 'reviewers', reason: 'synthesis gave no answer', results: ok })
}

if (target === 'all' || target === 'pipeline') {
  if (!data.cases) {
    log(`No pipeline case since ${params.since}: every autopilot item went through cleanly; no pipeline report.`)
  } else {
    phase('Analyse')
    const traced = await as('audit-pipeline', [
      `Audit the whole pipeline from ${params.since} to ${params.date} (backlog #0-121).`,
      `Audit data file: ${data.file} (its "cases" are the items to trace). Transcripts directory (may be unreadable from this session): ${data.transcriptsDir}.`,
      `Answer with the JSON your definition specifies, recommendations in the format of .ai/rules/audit.md, ids R-${params.date}-pipeline-NN.`,
    ].join('\n'), { schema: { type: 'object', required: ['cases', 'findings', 'recommendations'] }, label: 'audit pipeline' })
    if (!traced) {
      failures.push({ target: 'pipeline', reason: 'the pipeline analyst gave no answer' })
    } else {
      phase('Report')
      const pipelineReport = await as('audit-synthesis', [
        `Write the audit report for date ${params.date}, target pipeline, period ${params.since} to ${params.date}.`,
        `Pipeline analyst result (JSON): ${JSON.stringify(traced)}`,
        labelNote,
      ].join('\n'), { schema: REPORT })
      if (pipelineReport) reports.push({ target: 'pipeline', ...pipelineReport })
      else failures.push({ target: 'pipeline', reason: 'synthesis gave no answer', traced })
    }
  }
}

if (!reports.length && failures.length) return { outcome: 'FAILED', failures }
if (!reports.length) return { outcome: 'NOTHING_TO_AUDIT', reason: `nothing to report for target ${target} since ${params.since}` }
await ops('notify.sh', ['audit-report', params.date], NOTIFIED, 'notify')
return { outcome: failures.length ? 'PARTIAL' : 'REPORTED', reports, failures, next: `review the decision boxes, then run /apply-audit <report> for each of: ${reports.map((r) => r.report).join(', ')}` }
