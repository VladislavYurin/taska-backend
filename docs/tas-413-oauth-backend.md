# TAS-413: MCP OAuth backend

`oauth-server` is a separate servlet application with its own PostgreSQL schema for
clients, consents, grants and refresh-family replay history. It uses Spring
Authorization Server from Spring Security **7.0.8**. This module overrides the
Security BOM locally; the existing WebFlux services retain their dependency set.
7.0.8 includes the fix for [CVE-2026-22752](https://spring.io/security/cve-2026-22752).

## Protocol and trust boundaries

* `/.well-known/oauth-authorization-server` advertises a fixed configured issuer,
  CIMD support, S256, RFC 9207 response `iss`, read/write/offline_access scopes and,
  only when enabled, the restricted DCR endpoint.
* MCP serves `/.well-known/oauth-protected-resource` (also `/mcp` suffix), and 401
  challenges link this canonical metadata URI. `Host` cannot choose the resource
  or issuer. Write step-up asks for the union `read write`.
* Authorization Code requires PKCE S256 and an exact approved redirect URI. The
  canonical MCP `resource` must match in both authorization and token requests.
  Token requests accept form parameters, never URL query credentials. Code redemption is serialized under a PostgreSQL row lock; reuse revokes the complete issued family before returning invalid_grant. Access
  tokens are opaque `tsk_oat_` values, last ten minutes and have exactly the MCP
  audience. Session JWTs, delegation JWTs and external IdP tokens cannot enter MCP.
* Optional offline_access issues opaque `tsk_ort_` refresh tokens with an absolute
  maximum family lifetime of 30 days. Every use rotates the token under a database
  row lock. Reuse revokes the entire family in a committed transaction, including
  the token returned by a concurrent winner. The client must reconcile a lost
  refresh response by reauthorizing; automatic retries are unsafe.
* auth-service authenticates AS credentials using the existing password/lock
  policy without creating a JWT session. Private identity and MCP exchange RPCs
  require distinct service credentials in gRPC metadata. Caller `node_id`, Bearer
  headers and supplied user identifiers cannot grant this authority.
* MCP exchanges the verified opaque token using its server credential. auth-service
  derives the user, scopes and parent grant/token from protected AS introspection,
  then returns a separate delegation JWT, at most 60 seconds, audience
  `urn:taska:gateway`, signed with a separate key. The original opaque token is not
  passed to gateway. Gateway rejects original OAuth tokens, validates delegation
  signature/audience/current user and rechecks the parent grant/token on **every**
  request. Revocation has no positive-cache delay. The same restricted endpoint
  scope/admin/session policy as PAT applies. Public action channel remains MCP;
  internal OAuth token identifiers do not become public history/comment fields.

## Registration and consent contracts

CIMD is primary. Only exact configured HTTPS metadata hosts and exact globally
approved redirect URIs may register. Fetching has a 64 KiB response bound, five
second deadline, two concurrent fetches and twenty attempts/minute per instance.
DNS queries use a dedicated asynchronous resolver with a two second query bound.
The resolver validates **all actual DNS answers before connection**, including
already resolved socket addresses, and rejects private/link-local/multicast,
IPv4-mapped private and special-use addresses. Redirects are refused. Cached
metadata expires after the smaller of HTTP max-age and five minutes; no-store or
no-cache forces refetch. Registration is bounded by a database-locked client cap.
Client-supplied credentials/JWKS/token authentication cannot override server policy.

Restricted DCR (`POST /oauth/register`) is disabled by default. When enabled it
accepts only public PKCE clients with the same redirect policy and client cap,
limits registration attempts to twenty/minute and JSON to 64 KiB, and issues no
registration-management secret. It does not implement the default OIDC DCR flow.

The following AS-origin contracts support TAS-414:

* `GET /oauth/consent?state=...`: authenticated owner only; returns client display
  metadata, requested scopes, server state, submit URI and session CSRF token.
* `POST /oauth2/authorize`: standard consent submission (`client_id`, `state`,
  selected repeated `scope`, `_csrf`). The AS validates persisted state, principal,
  registered scopes and CSRF; client claims cannot approve another user's grant.
* `GET /oauth/grants`: connected grants belonging to the current session user with an
  active access or refresh token. Expired online grants are omitted; an offline grant
  stays visible while its refresh token is active, even after access expiry.
* `DELETE /oauth/grants/{id}`: owner only, CSRF required; removes the complete
  grant and consent so the next authorization asks again. Unknown/foreign IDs 404.
* `POST /oauth2/revoke`: RFC 7009 public-client token possession plus registered
  client ownership removes the complete grant/family, including code-only clients.
* `POST /internal/grants/validate`: separate Basic server credential only; validates
  parent grant and token ID without receiving the original opaque Bearer.

Public metadata and public-client token/DCR endpoints support browser CORS without
cookies. Login, consent, connected grants and private validation do not grant
cross-origin access. Sessions use Secure/HttpOnly/SameSite=Lax cookies, fifteen minute expiry, CSRF,
`frame-ancestors 'none'` and X-Frame-Options DENY. User status is checked through
private auth-service RPCs before browser authorization, consent and grant actions.
Login is the framework's AS-origin form; **TAS-414 consent/connected-app UI is not
implemented here**. Returning the consent contract is not a completed human consent
screen. Live claude.ai/ChatGPT/Inspector authorization remains pending that UI,
canonical HTTPS ingress and approved real client metadata/redirect registration.
No live hosted-client compatibility is claimed by the local protocol tests.

## Opt-in deployment

PAT behavior remains the default (`AUTH_OAUTH_ENABLED=false`,
`MCP_OAUTH_ENABLED=false`). The optional overlay is
`docker-compose.oauth.yml`; it adds a separate OAuth database and AS service and
wires the bridge, without changing the default Compose environment.

Provision **distinct**, random, deployment-managed secrets of at least 32 characters:
`OAUTH_INTROSPECTION_SECRET`, `OAUTH_IDENTITY_SERVICE_CREDENTIAL`,
`MCP_OAUTH_EXCHANGE_CREDENTIAL`, `OAUTH_DELEGATION_SIGNING_SECRET`, plus the OAuth DB
password. Examples deliberately contain no usable credentials. Set
`OAUTH_ISSUER`, `MCP_OAUTH_RESOURCE`, `OAUTH_CIMD_HOSTS`, `OAUTH_REDIRECT_URIS`, and the
HTTPS `OAUTH_INTROSPECTION_URL` / `OAUTH_GRANT_VALIDATION_URL`. Introspection must
reach the AS through trusted HTTPS; loopback HTTP is accepted only for local tests.
Private gRPC runs on the existing trusted service network; use authenticated TLS
transport if that network trust boundary does not hold. Do not expose private
identity RPCs to clients. The overlay maps AS HTTP only on loopback 8088 for an
explicit HTTPS reverse proxy; it does not provision DNS, certificates or secrets.

```
docker compose -f docker-compose.yml -f docker-compose.oauth.yml --profile oauth up --build
```

The implementation uses durable PostgreSQL registrations/grants and refresh locks.
Browser login sessions and per-instance registration/fetch limits are local; use
one AS instance or ingress session affinity until shared session storage is added.
Database and backup access must be restricted: the standard SAS JDBC model stores
current opaque token values; replay history stores only SHA-256 refresh hashes.

## Local verification

`OAuthProtocolIntegrationTest` starts PostgreSQL 16 and exercises real migrations
and Spring Security HTTP dispatch: metadata, S256 and resource constraints,
consent/owner/CSRF, code exchange, protected introspection, refresh rotation/replay,
concurrent refresh, absolute expiry and revocation. Additional tests cover socket
DNS policy, delegation signature/audience/lifetime/scope/parent revocation, separate
service credentials, MCP no-passthrough/discovery, and PAT/JWT compatibility.

```
JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m' ./mvnw -B -ntp \
  -pl auth-service,api-gateway,mcp-server,oauth-server -am verify
```
