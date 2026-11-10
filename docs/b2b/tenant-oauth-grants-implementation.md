# TAS-498: tenant-bound OAuth grants

This page describes the `auth-service` and `oauth-server` part of `approveTenantOAuthGrant` and its activation boundary. The target contract remains [contracts/README.md](contracts/README.md) §2; the public REST route `POST /api/v1/auth/tenant-oauth-consents`, the consent page and connected-apps UI (TAS-414/509), and the gateway/MCP adapters that put the binding into `TrustedTenantContext` are separate work.

## Flow

1. The MCP client starts `/oauth2/authorize`. The authorization server keeps the pending request and its `state` for ten minutes, as before.
2. The consent screen selects one company. The tenant web session of that company approves it through `ApproveTenantOAuthGrant`. The request carries `client_id`, `tenant_id`, `read`/`write` scopes and the pending `state` as `authorization_request_id`. The tenant comes from the verified session; the payload may only repeat it.
3. auth-service returns `grant_id` and `redirect_path` (`/consent?state=…&tenant_grant=…`). The consent page posts `decision=allow` with `tenant_grant` to `/oauth2/authorize`.
4. The authorization server claims the grant for that pending request, copies the binding into the authorization and only then issues the code. Code exchange and every refresh re-validate the bound grant at its recorded generation.

## Implemented here

- auth-service migration `0016-tenant-oauth-grants.sql` adds `oauth_grant_approvals`. Each approval writes a `USER`/`ONE_TENANT` row in `integration_grants` (TAS-463), with `READ` or `MODIFY` from the scopes, no permissions and a 30-day expiry, the refresh-family limit. The approval row stores the public `client_id`, the SHA-256 of the authorization request, the idempotency key and request digest, and a ten-minute claim window. The raw `state` is not stored.
- `TenantOAuthGrantStore.approve` runs under the account row lock. It re-checks the ACTIVE account and the current tenant session of the selected tenant, with its web-session `auth_version` and membership generation. Before the transaction the RPC compares the live membership from tenant-service with the session generation. A stale, removed or missing membership is `MEMBERSHIP_INACTIVE`; an authority outage is `DEPENDENCY_UNAVAILABLE`/503. Approval requires the `write` scope of the session.
- Idempotency follows contracts §8. The same `Idempotency-Key` with the same request returns the same grant until it is claimed. A different request with that key gets `IDEMPOTENCY_CONFLICT`. A claimed, revoked or stale approval gets `INVALID_STATE`/409. One authorization request yields at most one grant per account: approving it again, even for another company, is `INVALID_STATE`, and concurrent approvals have one winner.
- `ClaimTenantOAuthGrant` and `ValidateTenantOAuthGrant` are added to the private `OAuthIdentityService` with the oauth-server service credential. Claim succeeds once, inside the window, for the same account, client and request. Validate requires a claimed grant, the same client, the recorded generation, an unrevoked and unexpired grant and an ACTIVE account. Foreign or unknown grants are concealed as `RESOURCE_NOT_FOUND`. A PAT grant is never accepted as an OAuth grant.
- `AuthenticateTenantOAuthToken` is the OAuth counterpart of `AuthenticateTenantPersonalToken`. It introspects the opaque token and validates its grant. It returns an immutable `USER_OAUTH` binding: user, tenant, authorization id as `credential_id`, grant and generation, `auth_version`, scopes no wider than the grant, and the earlier expiry. Only the allowlisted gateway/MCP identity (TAS-317) may call it.
- Legacy `ExchangeToken`, original-token validation and delegation validation reject tenant-bound grants. A bound grant can't authenticate as an unbound global credential, because a missing tenant must not mean "all companies".
- oauth-server migration `0002-tenant-oauth-grants.sql` adds `integration_grant_id`, `tenant_id` and `grant_version` to `oauth2_authorization`, with an all-or-none check and one authorization per grant. The binding is copied from the authorization attributes and can't be replaced. Access tokens carry `integration_grant_id`, `tenant_id` and `grant_version` claims, so introspection and `/internal/grants/validate` return them with the standard `client_id`.
- In tenant mode the consent customizer requires `tenant_grant` on every approval. A refusal never consumes a grant. Posted scopes can't exceed the approved grant, apart from the protocol scope `offline_access`. Consent is never remembered per client, so an approval for company A can't be reused for or overwritten by company B. The target primary key of `oauth2_authorization_consent` with `integration_grant_id` is reached at clean-start cutover. Until then that table is unused in tenant mode.
- A definitive refusal at code exchange or refresh revokes the authorization family. An identity outage returns `temporarily_unavailable` and keeps it. Authorizations without a binding are refused, never exchanged.
- `/oauth/consent` reports `tenant_required`. `/oauth/grants` and the connected-apps list group connections by app and company and return `tenant_id`. `RevokeConnectedApp` with an optional `tenant_id` revokes only that company's connection; without it every connection of the app is revoked, as before.

Per-operation checks for a presented token stay in each domain service through the capability policy: membership, project ACL, lifecycle, flags, `READ_ONLY`, MCP flags, and no `GLOBAL_ADMIN` bypass for `USER_OAUTH`. D221 already holds for `USER_OAUTH`: profile, avatar, password, email, account deletion and PAT issuance return `SESSION_REQUIRED`. An authorization-server session from social login cannot complete consent without a grant approved by a tenant web session, so social login never gives an integration global access.

## Activation

Both flags are disabled by default. Enabling them requires:

| Requirement | Value |
| --- | --- |
| `TASKA_B2B_TENANT_OAUTH_ENABLED` (auth-service) | `true` only together with the oauth-server flag, after the consent UI and gateway route are ready |
| `OAUTH_TENANT_GRANTS_ENABLED` (oauth-server) | `true` refuses every unbound authorization, so legacy MCP connections stop working until reconnected |
| B2B sessions | Approval comes from a tenant session, see [auth-sessions-implementation.md](auth-sessions-implementation.md) |
| RPC trust | `WebSessionStore.MembershipAuthority` and `RpcContextSecurity`; without the latter approval fails closed with `UNAVAILABLE` |
| OAuth bridge | `AUTH_OAUTH_ENABLED` and its introspection credentials; startup fails without them |

Not covered here: the public REST route and consent/connected-apps UI, the gateway/MCP binding of `TrustedTenantContext`, revoking a user's OAuth grants on membership removal (TAS-323), and administrative OAuth grants (TAS-499). Until TAS-323 lands, the per-operation membership check denies a removed member and a revoked grant is refused at the next refresh.

## Verification

Run `python3 scripts/harness.py check --module auth-service` and `python3 scripts/harness.py check --module oauth-server`. Required selectors:

- `TenantOAuthGrantStoreIntegrationTest`: PostgreSQL approval, session and membership generation, single claim, idempotency, concurrency, claim window, generation change, revocation, block, expiry, and separation from PAT grants.
- `TenantOAuthGrantBoundaryTest`: forged context, credential kinds, tenant mismatch, write scope, live membership and outage, D221 for `USER_OAUTH`, edge binding, legacy rejection, fail-closed interceptor.
- `TenantOAuthGrantProtocolIntegrationTest`: consent without a grant, binding into authorization and introspection, scope widening, request reuse, outage, exchange and refresh revocation, unbound authorizations, separate connections for A and B.

These checks do not prove an external gateway, UI or stage end-to-end flow.
