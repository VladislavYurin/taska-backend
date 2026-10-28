# Optional OAuth deployment on Taska stage (TAS-448)

The default deployment keeps OAuth disabled. `docker-compose.oauth.yml` supplies
OAuth services and bridge credentials; `docker-compose.oauth.stage.yml` connects
the AS and auth-service to the existing Dokploy proxy network. Base service stubs
let Dokploy attach domain labels before Compose merges the overlays. **The base
file with `--profile oauth` alone is unsupported:** it lacks the OAuth database
credentials, volume and AS configuration. Always supply both overlays for startup;
the bare profile is used only to identify services during rollback stop.

## Provisioning and deployment

Use a protected shell with `DOKPLOY_URL`, `DOKPLOY_API_KEY` and
`DOKPLOY_COMPOSE_ID` set for the existing Taska deployment. Never commit or log its
`.env`, API responses, credentials, authorization URLs or token stores.

```sh
python3 infra/oauth-stage/configure.py plan
python3 infra/oauth-stage/configure.py apply
```

Review the plan before applying. The provisioner preserves unrelated environment
entries and domains, creates missing independent credentials, and configures the
optional Compose command. It does not deploy. The develop workflow calls
`sync-command` before its existing serialized deployment. Dokploy prefixes custom
commands with `docker`, so the stored command starts with `compose`.

`TASKA_STAGE_OAUTH_ENABLED=true` selects the base file, OAuth overlay and stage
overlay with `--profile oauth`. An absent marker leaves an existing command alone.
Set the marker to `false` for rollback and run `sync-command` followed by the normal
deployment. The stored rollback command first stops only `oauth-server` and
`oauth-db`, then starts the base deployment without OAuth overlays. Merely
disabling a Compose profile does not stop existing containers. Database volumes
and domain records remain available for recovery. Existing grants become usable again on a
subsequent enablement until revoked or expired. Do not delete the OAuth volume as
part of a configuration rollback.

The canonical issuer is `https://oauth.taska.ozero.dev`, with MCP resource
`https://mcp.taska.ozero.dev/mcp`. Inspector's observed callback is exactly
`http://127.0.0.1:6276/oauth/callback`. Restricted DCR is enabled for this public
PKCE client, while the CIMD host allowlist remains empty. Other clients require
separate verified metadata and redirect approval; do not guess their callback
URLs or broaden the allowlist to wildcards.

## Proxy and internal TLS boundary

`public-paths.json` lists public PathPrefix domain records. Each record targets
`oauth-server:8080`, preserves its path, enables Let's Encrypt HTTPS and applies
no compression or rewriting middleware. Do not replace them with a host-wide
`/` record: introspection, grant validation and actuator are private.

A separate Compose-labelled router admits `/oauth2/introspect` and `/internal/`
only from the verified proxy network CIDR. ClientIP uses the socket peer, not
X-Forwarded-For. Auth-service joins that network and resolves the canonical OAuth
hostname directly to the verified static Traefik IP. Thus private calls use HTTPS,
canonical SNI and the normal JVM certificate trust store. Basic service
credentials are still required. The trust boundary includes containers on this
network; private endpoints must never authorize solely by source address.

The provisioned stage values are static Traefik address `10.0.1.10`, private
network `10.0.1.0/24`, and trusted forwarded-header peer regex `10\.0\.1\.10`.
Verify these against the current container inspection before provisioning a
replacement proxy. Never trust the Docker host gateway, arbitrary private ranges
or forwarded headers supplied directly by a browser. Do not edit global Traefik
configuration for this service.

The AS session uses one instance. Keep secure cookies, fixed issuer redirects,
CSP and no response compression. MCP explicitly calls `http://api-gateway:8080`
on the service network, avoiding its localhost health recursion.

## Acceptance scenarios

Run all checks after the final PR review on the final source revision. Store
execution results outside Git; the repository contains reusable scenarios only.

1. Render the default Compose configuration: no OAuth services or enabled bridge.
   Render both overlays: distinct required secrets, persistent OAuth DB, exact
   redirect, internal HTTPS URLs, private network router and loopback-only port.
2. Deploy and verify service health and source revision. Fetch AS and protected
   resource metadata over HTTPS; issuer, token endpoints and resource stay fixed.
3. From the public network, private paths return 400/404 even with spoofed forwarded
   headers and valid Basic credentials. From auth-service, a valid Basic unknown
   token introspection returns 200 with `active:false`; invalid Basic is rejected.
4. Send raw paths without URL normalization, including
   `/login/..;/internal/grants/validate`,
   `/login/%2E%2E%2Finternal/grants/validate`,
   `/login%2f..%2finternal/grants/validate` and
   `/login%5c..%5cinternal/grants/validate`. Expect 400/404, never private handler
   success. Prefix rules alone do not prove safety across proxy/backend decoding.
5. Verify forged Host/proto/port headers cannot change canonical HTTPS redirect
   destinations. Request gzip/br and check that OAuth pages are not compressed.
6. With a real Inspector CLI, discover metadata, register the exact callback,
   request read/offline_access with S256/resource, log in, consent, exchange the
   code, list tools and reconnect using stored credentials. Revoke the owned grant
   and confirm stored credentials fail. Remove temporary token stores and revoke
   all tokens created by the run. Retain no browser credentials in reports.
7. Repeat password/PAT compatibility and provider-stub login scenarios. Record
   external-provider or unavailable-client acceptance gaps separately.

Local checks:

```sh
node --test infra/tests/*.test.js
python3 -m unittest discover -s infra/oauth-stage -p 'test_*.py'
```

Platform references: [Dokploy Compose](https://docs.dokploy.com/docs/core/docker-compose),
[domain API](https://docs.dokploy.com/docs/api/reference-domain),
[Traefik ClientIP](https://doc.traefik.io/traefik/reference/routing-configuration/http/routing/rules-and-priority/#clientip),
[Inspector CLI](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md).
