# TAS-315: backend web sessions

This page describes the implementation in `auth-service` and its activation boundary. The target contract remains [contracts/README.md](contracts/README.md); the public B2B REST gateway and deployment are separate work.

## Implemented here

- Liquibase migration `0013-b2b-web-sessions.sql` adds global `web_sessions`, independent `tenant_sessions`, binds new refresh rows to a single family, and keeps legacy refresh rows intact for a later cutover.
- `WebSessionStore` creates a global selection session after password authentication and a separate tenant session after an ACTIVE membership check. A tenant refresh resolves its tenant from the stored refresh family. The client cannot change that binding on refresh.
- Refresh tokens use 32 random bytes; refresh rows store only SHA-256 digests. Rotation takes a row lock; a retry with the same UUID key returns the same secret from a 60-second AES-GCM receipt. Reuse with another key revokes that family. Login and tenant-selection receipts provide the same 60-second response replay. A scheduled cleanup erases expired encrypted payloads but retains dedupe keys; an expired same-key replay returns `INVALID_STATE`/409 without revoking the valid successor.
- An account auth version and membership version invalidate older refresh sessions. Administrative block/unblock increments the account auth version in the same transaction as the status change; failed changes roll it back. A single tenant-session revoke leaves sibling tenant sessions active; `revokeAll` invalidates every web session for that user. Account status is checked during issuance and refresh.
- `TenantAccessIssuer` signs short Ed25519 JWTs with distinct `WEB_GLOBAL` and `WEB_TENANT` claims. Global sessions carry selection, tenant creation and account scopes; tenant sessions carry one tenant ID and membership generation. `kid` is mandatory.
- Additive gRPC `AuthSessionService` exposes password login, refresh, tenant selection, tenant-session revoke and current web-session logout. The latter three commands require a verified gateway context and an online membership authority.

## Activation

The new gRPC service is disabled by default through `TASKA_B2B_SESSIONS_ENABLED=false`. Enabling it requires a `WebSessionStore.MembershipAuthority` bean that queries the current tenant membership, a configured `RpcContextSecurity` trust provider for tenant commands, and private key material outside Git:

| Environment variable | Value |
| --- | --- |
| `TASKA_B2B_SESSIONS_ENABLED` | `true` only after the trust and gateway adapters are ready |
| `TASKA_B2B_REFRESH_RECEIPT_KEY` | Base64 of a 32-byte AES key; keep stable across replicas and restarts while receipts live |
| `TASKA_B2B_ED25519_PRIVATE_KEY` | Base64 PKCS#8 Ed25519 private key |
| `TASKA_B2B_ED25519_KID` | Key identifier published with the matching public key |
| `TASKA_B2B_REFRESH_TTL` | Refresh lifetime, default `7d` |
| `TASKA_B2B_ACCESS_TTL` | Access lifetime, at most `15m` |

The provider and gateway adapters are absent from this checkout. No public REST route points at this service, and legacy login, social login, PAT, OAuth, or MFA flows are not switched to it. In particular, the stage is **not** evidence of B2B session cutover. The new JWT must be verified with the Ed25519 public key and current account/membership authority before it can authorize business data. The `GLOBAL_ADMIN` login returns `MFA_REQUIRED` until the separate MFA flow is connected.

## Verification

Run `python3 scripts/harness.py check --module auth-service` for PostgreSQL rotation, concurrent same-key replay, restart persistence, independent logouts, current membership binding and signed access lifetime. Required selectors include `WebSessionStoreIntegrationTest`, `TenantAccessIssuerTest` and `AuthSessionBoundaryTest`; the last uses handler authority fixtures to check credential kinds, forged contexts and fail-closed interception. Explicit `InvitedUnblockIT` remains selected according to [verification.md](../agents/verification.md). These checks do not prove an external gateway or stage end-to-end flow.

## Session binding and adapter contracts

Membership generation is checked before rotation receipt replay; an old family cannot inherit a new role generation. `expires_in` reports remaining signed access lifetime on retries. Missing/foreign tenant selection and missing session revoke return 404; a known inactive membership must be rejected by MembershipAuthority with 403/MEMBERSHIP_INACTIVE. Authority failures propagate, and ACTIVE members may select a company while it is PROVISIONING/AWAITING_PAYMENT; content/financial guards remain separate.

`RevokeWebSession` derives the parent session from the verified WEB_GLOBAL context and atomically revokes its children and refresh families. Other login sessions remain active; this differs from account-wide `revokeAll`. Gateway TAS-316 must map the current-session logout route to this additive RPC and preserve these error codes. The public REST route is not implemented by this PR. Social login exchange and GA MFA remain separate adapters. Successful self-leave receipt replay must use a narrowly scoped receipt path in TAS-316/317; ordinary membership validation must never be disabled for this purpose.
