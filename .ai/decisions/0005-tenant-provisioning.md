# ADR-0005: Tenant provisioning (#0-80)

- **Status:** Accepted
- **Backlog:** #0-80
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

customer tenants are rows in auth-service's `tenants` table (V21,
backfilled from existing users; since #0-92 every service's `tenant_id` has the slug `CHECK`, auth V28
reversing V21's keep-as-is backfill).
An admin of `platform-operator`, with a JWT (never an API key, service or purpose token: `PlatformAccess`,
checked in the filter chain and by `@PreAuthorize` on every method), calls `/api/v1/platform/tenants`:
create (tenant row via `insertIfAbsent`, a native `ON CONFLICT DO NOTHING`, since `save()` would merge
over an assigned id, #0-47; plus the first admin through `UserService.createUser` in the new tenant's
`TenantContext`, one transaction; refused if the id has users, archived ones included), reissue the
first invite (`TenantAdminReconciler`, shared with `OperatorTenantBootstrap`; never creates a user, so an
archived first admin is not revived; a tenant's end of life is #0-101), show and list metadata. This is the one cross-tenant capability, a narrow reversal
of #0-16; audit types `TENANT_PROVISIONED` / `TENANT_ADMIN_REINVITED` in the operator tenant, and `TENANT_SUSPENDED`
/ `TENANT_RESUMED` (#0-82) in both the operator and the customer tenant (the operator's note only in the former).
Suspension: #0-82 (below); offboarding: #0-101. Guide: docs/tenant-provisioning.md.
