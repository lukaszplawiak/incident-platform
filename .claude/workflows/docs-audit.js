export const meta = {
  name: 'docs-audit',
  description: 'Whole-repository documentation audit: review-docs in audit mode, drifts written to a report with draft backlog items for the owner.',
  phases: ['Audit', 'Report'],
}

// ============================================================================================
// Run occasionally (with the reviewer audit, every 10–15 items), in an autopilot session, args
// { date: 'YYYY-MM-DD' }. Drift that a single PR's review cannot see (a README naming a class removed
// three items ago in another PR) shows up here. The owner decides which drafts become backlog items;
// nothing is written to BACKLOG.md by this workflow.
// See backlog-autopilot.js for AGENT_TYPE_OPTION: verify it before the first run.
// ============================================================================================

const AGENT_TYPE_OPTION = 'agentType'
const as = (type, prompt, opts = {}) => agent(prompt, { ...opts, [AGENT_TYPE_OPTION]: type, label: opts.label ?? type })

if (!args?.date) return { outcome: 'NOT_STARTED', reason: 'pass args.date (YYYY-MM-DD)' }

phase('Audit')
const drift = await as('review-docs', [
  'Mode: AUDIT. The whole repository, not a diff: README.md, CLAUDE.md, AGENTS.md, .ai/ (context, decisions index, rules references), BACKLOG*.md references, and Javadoc of public APIs where a doc names a class or behaviour.',
  'Return JSON: {"drifts": [{"file", "line", "level": "A|B|C|D", "issue", "proposedFix", "backlogDraft"}]}.',
  'Level A drifts are for the owner only: propose text, never a task for an agent.',
].join('\n'), {
  schema: { type: 'object', required: ['drifts'], properties: { drifts: { type: 'array', items: { type: 'object', required: ['file', 'level', 'issue'] } } } },
  label: 'docs audit',
})
if (!drift) return { outcome: 'FAILED', reason: 'review-docs gave no answer' }

phase('Report')
const report = await as('audit-synthesis', [
  `Write .ai/audit/${args.date}-docs.md: a documentation audit, not an agent audit. Sections: Summary (counts by level),`,
  'Level A (owner text proposals), Level B and C (drafts of backlog items, Complexity: low, one per file group), Level D (a list).',
  `Drifts (JSON): ${JSON.stringify(drift.drifts)}`,
].join('\n'), { schema: { type: 'object', required: ['report'] } })
return { outcome: 'REPORTED', drifts: drift.drifts.length, report: report?.report ?? null }
