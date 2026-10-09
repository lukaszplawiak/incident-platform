# ADR-0008: MFA recovery of a customer tenant's only admin (#0-90)

- **Status:** Accepted
- **Backlog:** #0-90
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

`MfaRecoveryService` (+ `MfaRecoveryScheduler`,
`PlatformMfaRecoveryController`, public `MfaRecoveryCancelController`, table `mfa_recovery_requests`, V29). The
one place the platform acts inside a tenant with an admin, so its limits are the design: only an active admin
with MFA and an accepted invite who is the tenant's only one (checked at request and again at execution),
never a reserved tenant (break-glass), never at once. Non-obvious points:
- The waiting period counts from `notice_sent_at`, set by `AuthEmailPersistenceService.recordSent` when the
  MFA_RECOVERY_REQUESTED email (row names the request, `mfa_recovery_request_id`; never superseded) is sent,
  not from the request: a notice that never goes out means no reset (expiry after the security-notice
  deadline + 1 h). The email's "not before" is computed at send time, so it can only be early, never late.
- The cancel token (`MFA_RECOVERY_CANCEL`) lives 14 days, more than the longest allowed waiting period (24 h to
  7 days, checked in `MfaRecoveryProperties`: the floor so a variable cannot shrink the defence to seconds); every
  close invalidates it, and the execution also invalidates earlier PASSWORD_RESET tokens. It can only stop a reset.
- The reset replaces the password with an encoded random value, not null: null means "invite pending" in
  `existsActiveAcceptedUserWithRole`, `ResendInviteService`, `TenantAdminReconciler` and the outbox's
  "invite already accepted", and would have reopened #0-80's reissue path for the tenant.
- Status changes are conditional UPDATEs from PENDING (`MfaRecoveryRequestRepository.close`, clear + flush):
  `execute` re-reads the user after its claim because the claim cleared the persistence context.
- Audit in both tenants; the operator's note goes only to the operator tenant's event (it may name people or
  numbers). Actor in the customer tenant: the constant `platform-operator` (no operator id, third review), in the
  operator tenant the operator's id; executions/expiries are system events. Counters are incremented after commit
  (`countAfterCommit`), so a rollback raises no critical alert. `execute` locks the target user row
  (`findByIdAndTenantIdForUpdate`, NOWAIT) before its checks; a busy row is a counted, retried failure.
- Accepted limit: the mailbox holder can cancel every request and the session holder can add a second admin
  (platform then keeps out). Both page the operator; resolving a takeover is a person's job, with suspension
  in full (#0-82) as the operator's lever.
- The execution rechecks the requester too (`operatorStillAdmin`: active operator admin with a password), so
  deactivating a compromised operator neutralises its open requests (`OPERATOR_NO_LONGER_ADMIN`); any cancel at
  that final check is alerted (`PlatformMfaRecoveryCancelledOnRecheck`), scheduler failures are counted
  (`platform.mfa_recovery.failures`, alert `PlatformMfaRecoveryJobFailing`). Expiry uses its own conditional
  UPDATE (`expireIfUndelivered`, also `notice_sent_at IS NULL`). `MfaRecoveryRequest` is `Persistable`
  (assigned UUID, no `@Version`: otherwise `save` merges).
- "Active admin" is one query pair now, `countActiveAcceptedUsersWithRole[Excluding]` (active + password), used
  by the last-admin guard too; tenant settings expose `activeAdmins` / `singleAdmin` as the prevention side.
