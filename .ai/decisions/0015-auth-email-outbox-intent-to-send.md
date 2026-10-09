# ADR-0015: Auth email outbox = intent to send

- **Status:** Accepted
- **Backlog:** #0-52, #0-83, #0-88
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

(#0-52): a request (`UserService`, `ResendInviteService`,
`ForgotPasswordService`, `MfaService`, `ApiKeyService`, `IntegrationService`) only INSERTs through `AuthEmailRequestService`; `AuthEmailScheduler` is
the only writer afterwards. Per attempt it closes entries no longer worth sending (SUPERSEDED: a newer request
of the type, an accepted invite, a missing user; PERMANENTLY_FAILED: deadline passed), otherwise, for the
token-carrying types (invite, reset), invalidates the user's earlier tokens of the type and creates the token
it sends — no raw token is stored anywhere, and the link is valid for its full lifetime from sending. The MFA
notices (MFA_ENABLED / MFA_DISABLED, #0-83; MFA_RESET, #0-88, V24) and API_KEY_CREATED (#0-89, V25) carry no token (`AuthEmailType.carriesToken()`). Failed sends are
retried on `AuthEmailRetryPolicy`'s backoff until the entry's deadline (invite 7 days, reset 15 minutes, MFA
notice and API key notice max(24 h, grace period)), in two lanes (`processPending`, `retryFailed`) with their own
batches and a processing budget validated against the ShedLock. State changes are conditional UPDATEs, not
`@Version`. Counters `auth.email.send`, `auth.email.permanently_failed`; alerts `AuthEmailDeliveryFailing`,
`AuthEmailPermanentlyFailed`; terminal rows are purged after `invite.email.retention`. V19 dropped and
recreated the table (not in production yet). From the first production release, every migration must stay
compatible with the previous release (expand/contract), since pods of both run during a rollout.
