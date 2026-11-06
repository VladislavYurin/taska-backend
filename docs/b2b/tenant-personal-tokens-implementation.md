# TAS-463: tenant-bound personal access tokens

This page describes the `auth-service` part of `createTenantPersonalToken` and its activation boundary. The target contract remains [contracts/README.md](contracts/README.md) §2; the public REST route `POST /api/v1/account/tenant-tokens`, gateway context and MCP adapters are separate work (TAS-316/317).

## Implemented here

- Liquibase migration `0014-tenant-personal-tokens.sql` adds `integration_grants` with the target checks, links `personal_access_tokens.integration_grant_id` through a composite `(grant, user)` foreign key, and adds 60-second encrypted issuance receipts. Legacy PAT rows stay unbound until cutover.
- `TenantPersonalTokenStore.issue` runs under the account row lock. It re-checks the ACTIVE account, the current tenant session of the requested tenant and its web-session `auth_version`. It also enforces the shared active-token limit, which counts legacy and tenant tokens together. In one transaction it writes a `USER`/`ONE_TENANT` grant, the token hash, the sealed receipt and the existing `PersonalAccessTokenCreated` outbox event with `tenantId`. The secret and its hash never leave the response.
- The same `Idempotency-Key` with the same request returns the same secret within 60 seconds. A different request with that key gets `IDEMPOTENCY_CONFLICT`. Replay after the window, or of a revoked or expired token, gets `INVALID_STATE`/409. The existing session receipt cleanup erases expired payloads and keeps dedupe keys.
- Before the transaction, `TenantPersonalTokenAdmission` evaluates `pat.create` through the shared `CapabilityEvaluator` (TAS-360). That requires a `WEB_TENANT` session, an ACTIVE membership with the current generation, `write` scope, `PERSONAL_API_TOKENS`, and `FREE_PERSONAL_API_TOKENS` when this tenant is on FREE. A paid company elsewhere never opens a FREE one. An authority outage is `DEPENDENCY_UNAVAILABLE`/503.
- `TenantPersonalTokenStore.authenticate` resolves a secret to an immutable binding: user, tenant, token, grant and its generation, scopes, expiry, account `auth_version`. Revoked, expired or blocked-account tokens return `TOKEN_REVOKED`. Unknown and legacy unbound tokens return `AUTHENTICATION_REQUIRED`, because a missing tenant never means "all companies". Usage is touched at most every 5 minutes.
- Legacy `validate` rejects bound tokens, so a tenant token cannot authenticate as an unbound credential. Legacy metadata and revoke still list and revoke them. Revoking a token, one at a time or account-wide, also revokes its grant and increments the grant version.
- Additive gRPC `TenantPersonalTokenService`. `CreateTenantPersonalToken` requires the verified gateway context; the tenant comes from the session, and the payload may only repeat it. A PAT, OAuth, admin or global web credential cannot issue a token. `AuthenticateTenantPersonalToken` is the edge authentication step, so it has no `RequestContext`. Only the allowlisted gateway/MCP service identity (TAS-317) may call it.

Per-operation checks for a presented token stay in each domain service through the capability policy: membership, project ACL, lifecycle, flags, `READ_ONLY`, MCP flags, and no `GLOBAL_ADMIN` bypass for `USER_PAT`. The edge copies the binding into `TrustedTenantContext` (`credential_kind=USER_PAT`, `credential_id`, `grant_id`, `grant_generation`, `actor_generation`, `scopes`); it never takes the tenant from URL, header or body.

## Activation

The service is disabled by default through `TASKA_B2B_TENANT_TOKENS_ENABLED=false`. Enabling it requires:

| Requirement | Value |
| --- | --- |
| `TASKA_B2B_TENANT_TOKENS_ENABLED` | `true` only after the gateway adapter and trust provider are ready |
| B2B sessions | Tokens are issued from a tenant session, see [auth-sessions-implementation.md](auth-sessions-implementation.md) |
| `TASKA_B2B_REFRESH_RECEIPT_KEY` | Reused for the issuance receipts; the AAD differs from session receipts |
| `CapabilityEvaluator.Source` bean | Current-facts adapter for identity, membership, flags and entitlement; absent from this checkout |
| `RpcContextSecurity` bean | Without it issuance fails closed with `UNAVAILABLE` |

Not covered here: the public REST route and the legacy `/users/me/access-tokens` cutover (explicit tenant or stop issuing), the gateway/MCP binding of `TrustedTenantContext`, and revoking a user's tenant tokens on membership removal (TAS-323). Until TAS-323 lands, the per-operation membership check denies a removed member.

## Verification

Run `python3 scripts/harness.py check --module auth-service`. Required selectors are `TenantPersonalTokenStoreIntegrationTest` (PostgreSQL issuance, binding, concurrent replay, receipt expiry, shared limit, revocation of token and grant, account block) and `TenantPersonalTokenBoundaryTest` (forged context, credential kinds, tenant mismatch, the real `pat.create` policy with PAT/FREE flags, membership, scope and outage, fail-closed interceptor). These checks do not prove an external gateway or stage end-to-end flow.
