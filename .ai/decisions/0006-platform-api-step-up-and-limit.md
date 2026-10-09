# ADR-0006: Platform API step-up and limit (#0-83)

- **Status:** Accepted
- **Backlog:** #0-83
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

`PlatformAccess` also requires that the access token's
session completed MFA within `platform.mfa.max-session-age` (12 h) and that the MFA_ENABLED notice of the
account's current factor was sent at least `platform.mfa.enrolment-grace` (24 h) ago.
- Why server-side, not an `amr` JWT claim: only auth-service needs it and it owns the sessions, so the fact
  lives on the session (`auth_tokens.mfa_verified_at`) and is checked per request against a live session,
  which also makes logout and disabling MFA effective at once, with no `shared` or token-format change. Add
  `amr` only if another service needs step-up.
- Why the grace period: enabling MFA needs only a password, so a password thief could enrol their own
  factor. Every MFA enable/disable emails the account (token-less auth email outbox types), the period
  counts from when that email was sent (kept on the user, as the outbox purges sent rows). The remedy for a
  stranger's factor is a password reset (ends sessions and unfinished logins, never touches MFA) and then
  an admin MFA reset (#0-88; #0-83's interim removal of a fresh factor by a password reset is gone, since it
  let a mailbox alone undo MFA). The grace period is only
  as strong as the owner's mailbox; binding operator enrolment to the invite, or a second operator's
  approval, is #0-87. Factors older than 24 h at deploy were taken over as established, except an operator
  admin's: operators re-enrol once.
- Why the limit fails closed, unlike ingestion's #67: it is a security control on a rare operation, where
  waiting costs nothing. Per operator and platform-wide, so several taken-over accounts do not multiply it;
  any failure opens its circuit breaker. Its alerts go by email, outside the platform, since an attacker
  acting as operator could resolve incidents in the operator tenant.
- The 403 names the failed condition, except that a factor too new and an undelivered notice share one
  message, so a password thief cannot tell whether the owner was warned.
