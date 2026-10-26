# OAuth login and registration (TAS-424)

The gateway owns only public HTTP routes and the browser cookie; auth-service owns provider credentials, token exchange/verification and account decisions. Google/GitHub use authorization-code + S256 PKCE. Apple uses `form_post` and a server-generated ES256 client-secret JWT with a five-minute lifetime. Provider access/refresh/id tokens are never persisted or included in outbox/audit payloads.

Protocol sources: [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect), [Google PKCE](https://developers.google.com/identity/protocols/oauth2/native-app), [GitHub OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps), [GitHub verified primary email](https://docs.github.com/en/rest/users/emails), [Apple Sign in REST API](https://developer.apple.com/documentation/signinwithapplerestapi).

## Configuration and provider setup

All providers are disabled unless every required credential is present and their individual enabled flag permits them. `AuthProviderCatalog` exports only GOOGLE/GITHUB/APPLE names to TAS-423 methods; keys and configuration objects never leave auth-service. `TASKA_SIGNUP_ENABLED` controls new-account creation and defaults false. Existing accounts can still sign in when registration is disabled.

For Docker Compose, copy `.env.oauth.example` to ignored `.env.oauth`; only auth-service loads it. Set each provider enabled flag to true only after its credentials are configured. Do not put provider secrets in the shared `.env`. For Dokploy/standalone deployment inject them only into auth-service. Keep public `TASKA_OAUTH_API_BASE_URL` and `TASKA_OAUTH_UI_BASE_URL` consistent with browser origins; redirects do not trust incoming Host headers. Deploy the migration/auth-service before the gateway.

- Google: Web application OAuth client; callback `https://api.taska.ozero.dev/api/v1/auth/oauth/google/callback`. External consent screen must permit the intended users (production status for unrestricted use). Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET with TASKA_OAUTH_ prefix.
- GitHub: OAuth App (a separate app for each callback origin); callback `https://api.taska.ozero.dev/api/v1/auth/oauth/github/callback`. Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET with TASKA_OAUTH_ prefix. The numeric user id is the subject, never mutable login.
- Apple: Services ID, Team ID, Key ID, and a Sign in with Apple .p8 PKCS#8 key; callback `https://api.taska.ozero.dev/api/v1/auth/oauth/apple/callback`. Set APPLE_SERVICE_ID/TEAM_ID/KEY_ID/PRIVATE_KEY with TASKA_OAUTH_ prefix. Literal escaped newlines in the PEM are accepted. The signing secret is generated on the server, never configured as a static JWT. Private relay addresses are accepted as normal verified emails. The sender domain must be authorized with Apple for real delivery to private relay addresses.

No real provider apps, keys, paid account or stage browser/email delivery were created or verified by local automated tests.

## State, code and account lifecycle

Each start creates a 10-minute server-side state with nonce/PKCE verifier and a SHA-256 browser binding. The per-flow `__Host-taska_oauth_<state>` cookie is HttpOnly, Secure, SameSite=None, Path=/ with no Domain. This supports Apple's cross-site POST and parallel browser flows. Missing/foreign cookies do not consume a legitimate state. A valid callback atomically claims it before contacting the provider; retries must restart the OAuth flow.

The UI redirect contains only a fresh 60-second exchange code or a fixed error reason. Code hashes are stored, and an atomic DELETE RETURNING claims each exchange/signup grant exactly once. Grants are claimed before account transactions: a backend failure after a claim requires a new OAuth flow; no consumed grant is retried as an account action. Expired rows are periodically deleted. Responses are no-store and redirects set no-referrer. Keep reverse-proxy/access logging from recording callback query strings or OAuth request/response bodies.

Unknown verified identities yield SIGNUP_REQUIRED without creating an account. The 15-minute signup grant persists the first Apple name as a suggestion; the user chooses the final name. Invalid names are checked before consuming the grant. Account create/link uses the same canonical-email advisory transaction lock as invitations, plus a provider/subject lock; email lookup locks the user row. Concurrent email/provider signup therefore converges on one account. New users are ACTIVE, USER, created_via OAUTH, with no password or project membership. Existing names/passwords/origin are preserved. INVITED users activate and unused invitation tokens are revoked transactionally with credential/session/outbox writes. BLOCKED and unexpired LOCKED accounts never receive a session; expired locks resolve through the existing account-lock service.

Provider, identity-token and account errors expose only PROVIDER_CANCELLED, STATE_INVALID, EMAIL_NOT_VERIFIED, ACCOUNT_BLOCKED, ACCOUNT_LOCKED, SIGNUP_DISABLED or PROVIDER_UNAVAILABLE. Display-name errors retain DISPLAY_NAME_INVALID. No provider exception body is exposed.

## Local verification

`OAuthIntegrationTest` uses real PostgreSQL16 and a localhost HTTP provider/JWKS server. It verifies all three providers, S256 and Apple ES256, signed id-token issuer/audience/expiry/nonce/email rules, replay/expiry/browser binding, account linking/activation, registration flag, and canonical-email concurrency. `OAuthControllerTest` verifies public routing, cookie attributes and Apple form parsing. These tests do not imply real provider/stage E2E success.
