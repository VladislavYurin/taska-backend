# TAS-447: OAuth authorization-server social sign-in scenarios

These scenarios use a registered public PKCE client with an approved redirect URI,
S256 challenge, MCP resource and read scope. Run against disposable accounts and
browser profiles. For a provider stub, exercise its real HTTP token/user/email or
JWKS endpoints; repeat the happy path against each configured real provider when
its authorized test account is available. Record execution results outside Git.

## Existing account with no password

1. Create an ACTIVE Taska account linked to the provider, with no PASSWORD credential.
2. Open a valid `/oauth2/authorize` request in a fresh browser. Inspect
   `/login/context`: `pending_request=true`; `providers` matches enabled public
   `/api/v1/auth/methods` providers, without credentials or private configuration.
3. Select Google, GitHub or Apple. The AS redirects to the fixed API origin with
   `target=oauth` and a 43-character SHA-256 binding. The raw nonce stays in the AS
   session and is absent from URLs and context JSON.
4. Complete provider sign-in and return through the gateway callback to
   `/login/provider/callback?code=...` on the fixed AS issuer.
5. The browser session ID changes, the old CSRF token is discarded, and the browser
   resumes the original authorization request. Consent names the expected client.
6. Approve read scope, exchange the authorization code with the original PKCE
   verifier, and introspect the resulting opaque access token. Subject matches the
   existing Taska account. No Taska JWT session, refresh session, password credential
   or duplicate user was created by social sign-in.

## Foreign browser and grant replay

1. Prepare browser A's social callback code without visiting its AS callback.
2. In browser B, create a separate pending request and start social sign-in.
3. Deliver A's code to B's callback. Sign-in fails and B has no authenticated session.
4. Deliver the same code to A. It succeeds: the wrong binding did not consume it.
5. Replay the code in another pending browser flow. It fails without establishing
   a session. Concurrent legitimate claims have exactly one winner.
6. Start two providers in the same AS session before either callback. Both starts
   retain the original private browser nonce. The first callback can complete;
   the second cannot establish another session or consume a different grant.

## UI and authorization-server grant separation

1. Create a social grant using `target=oauth`. Try public UI `/oauth/exchange`.
   It rejects the code without consuming it; the original AS browser can still claim it.
2. Create a normal UI social grant. Try the private AS claim RPC with an AS nonce.
   It rejects the code without consuming it; normal UI exchange still works.
3. Existing UI login and signup callbacks keep their original UI destination and
   behavior. A changed routing-hint cookie cannot reroute a successful UI code.

## Unknown account

1. Use a verified provider identity whose email/subject has no Taska account.
2. Complete social sign-in. The AS login page explains that a Taska account must
   first be created; no authenticated session, user, password or refresh is created.
3. Repeat with Taska registration enabled and disabled: neither AS sign-in creates
   an account. Start again after creating the account in the normal Taska UI.

## Account and provider policy changes

1. Stop between provider callback and private AS claim. Block or lock the account.
2. Claim the code. The current policy is enforced and no browser authentication or
   API tokens are issued. Repeat after disabling the provider.
3. With an invited account, verify existing linking/activation and invite cleanup
   follow normal Taska account policy and still issue no JWT/refresh session.

## Cancellation, expiry and invalid input

1. Cancel provider approval. Return to the fixed AS login with a readable
   `PROVIDER_CANCELLED` refusal; the password form remains available.
2. Expire the provider state or one-time code; delete the AS session or API binding
   cookie. The flow fails with a readable refusal, never an authenticated session.
3. Try missing/malformed binding, unknown target/provider, a direct start without
   a saved authorization request, and a callback without a session nonce.
   Each fails without redeeming another browser's legitimate grant.
4. Exhaust the gateway IP quota during callback. The refusal returns to the fixed
   AS issuer; protected state/cookie remains available after the quota window.

## Private identity boundary

1. Call ClaimSignIn and ListSignInProviders with missing/wrong service credentials.
   Both reject the caller; a supplied node ID does not grant access.
2. Retry the legitimate claim with the configured service credential and correlation
   header. The prior unauthorized attempt did not consume the grant.
3. Deny identity-service connectivity. Providers fail closed; the password UI and
   generic service-unavailable message remain usable without leaking gRPC details.

## Browser and origin checks

1. Exercise desktop/mobile layouts and keyboard focus with multiple enabled
   providers, one enabled provider and no enabled providers.
2. Inspect CSP, no-store, Secure/HttpOnly session and per-flow API cookies. Neither
   inline scripts nor provider tokens are required in the page.
3. Supply an attacker Host header and alter target/cookie/query parameters. Every
   failure and success destination uses configured API/issuer/UI origins.
4. Complete a normal password login, denial, account-switch logout, and consent
   after social login; saved requests are removed after success and sessions remain
   isolated between users.
