-- Optimistic locking for escalation_tasks (backlog #38).
--
-- Fixed: EscalationScheduler.checkAndEscalate() (runs on its own
-- @Scheduled thread) and EscalationService.cancelEscalation() (runs on
-- the Kafka listener thread, triggered by an incoming
-- IncidentAcknowledgedEvent) can both read and write the SAME
-- EscalationTask row concurrently — ShedLock only prevents
-- checkAndEscalate() from running on multiple *instances* simultaneously;
-- it does nothing to serialize it against the Kafka consumer thread
-- running in the SAME instance. Without a version check, both writes
-- could proceed with no conflict detected: whichever commits last
-- silently wins. Concretely, an engineer acknowledging an incident at
-- the exact moment its escalation timeout fires could see
-- cancelEscalation() log "Escalation cancelled" while the scheduler's
-- ESCALATED write still overwrites it afterward — paging the next
-- on-call engineer for an incident that was just acknowledged, with no
-- error anywhere to indicate anything went wrong.
--
-- version enables Hibernate's standard optimistic locking: an UPDATE now
-- includes "AND version = :expectedVersion" — if another transaction
-- already changed the row, zero rows match, and Hibernate raises
-- OptimisticLockingFailureException instead of silently applying a
-- stale write. See EscalationScheduler.escalate()'s updated handling for
-- how this is now used to skip (not silently overwrite) a task that was
-- concurrently cancelled.
ALTER TABLE escalation_tasks
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN escalation_tasks.version
    IS 'Optimistic locking version (backlog #38) — detects concurrent '
       'modification between EscalationScheduler and '
       'EscalationService.cancelEscalation (triggered independently by '
       'IncidentEventConsumer), which ShedLock does not protect against.';