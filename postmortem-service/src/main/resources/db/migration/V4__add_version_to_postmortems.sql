-- Optimistic locking for postmortems (backlog #49).
--
-- Fixed: Postmortem had no @Version, allowing two independent writers to
-- silently overwrite each other with no conflict detection:
--   1. PostmortemService.updateContent — an engineer manually editing
--      content via the API, on the HTTP request thread.
--   2. PostmortemPersistenceService.markDraftAndPublish/markFailedAndPublish
--      — PostmortemRetryScheduler writing back the result of a Gemini call
--      (3-15s per GeminiClient's own documentation), on the scheduler thread.
--
-- The race window here is unusually wide compared to similar cases already
-- fixed elsewhere (e.g. EscalationTask, backlog #38) specifically because
-- of that 3-15s Gemini call duration: an engineer editing content while a
-- retry is in flight for the same record is a realistic scenario, not a
-- corner case. Without version-conflict detection, whichever write
-- committed last silently won — most concerningly, a scheduler's stale
-- AI-generated draft could overwrite an engineer's freshly hand-written
-- content with zero warning and no trace in the audit log beyond another
-- unremarkable "Postmortem draft generated" event.
ALTER TABLE postmortems
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN postmortems.version
    IS 'Optimistic lock version (backlog #49). Existing rows default to 0.';