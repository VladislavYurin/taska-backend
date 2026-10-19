# TAS-204: invitations and user directory

## Product rules

Taska currently has one invite-only workspace per deployment. The directory is shared by its authenticated users; it is not a cross-tenant API. Search does not grant access to any project. Project membership and roles still require their existing, independently authorized operations.

Only a `GLOBAL_ADMIN` may invite a new account or resend an invitation. Project administrators can find existing users and add eligible accounts to their projects, but cannot create workspace accounts. The gateway checks the global role, and auth-service independently rechecks the actor's existence, status and role. Missing authentication is 401; authenticated lack of permission is 403. `LOCKED` is a temporary login lockout and retains already authorized access; `BLOCKED` and `INVITED` actors cannot access the directory.

`POST /api/v1/auth/invitations` accepts `email`, optional `displayName` and optional `resend` (false by default). The server trims email and compares it case-insensitively. It does not merge provider-specific aliases, remove dots, or strip `+tags`. A new account receives an opaque login and the USER role. Missing display name defaults to the local part of email.

| Existing state | Result |
| --- | --- |
| No account | 201, INVITED, `deliveryQueued=true` |
| INVITED with a valid unused token, no resend | 200, same userId and expiry, `deliveryQueued=false`; no additional email or token |
| INVITED with explicit resend | 200 with a new queued email; all previous unused tokens are revoked |
| INVITED with a missing, used or expired token | A fresh invitation is queued without recreating the account |
| Resend/renewal inside cooldown | 429, `Retry-After` and `details.retryAfterSeconds`; current token is preserved |
| ACTIVE, LOCKED or BLOCKED account | 409; no password, status, profile or membership changes |

The default cooldown is one minute (`AUTH_INVITATION_RESEND_COOLDOWN`). The token lifetime is `AUTH_JWT_INVITE_TTL` (default seven days, full Duration precision). Email, display-name, query and pagination limits are validated before writes/search. CR/LF and control characters are not accepted as display names. Email validation occurs after canonicalization in auth-service.

## Activation and concurrency

`POST /api/v1/auth/invitations/accept` stays public and returns 204 without session tokens. The client must then use `/api/v1/auth/login`; it must not assume that a pre-existing session belongs to the invitee. Frontend flow is tracked by TAS-208.

Tokens are random 256-bit values. `invite_tokens` contains only their SHA-256 hashes. Raw tokens travel through the restricted auth outbox/Kafka invitation payload to the private email queue, never through a REST response or inbox notification body. Read-only admin masking hides them in both the auth outbox and the delivery table. Treat access to these queues, database backups and Kafka as credential-bearing access; their storage retention and transport controls are deployment responsibilities.

Invitations are serialized by a database transaction-scoped advisory lock on canonical email. The case-insensitive unique index also protects creation through other code paths. Resend and activation lock the user before touching tokens. Activation atomically verifies/consumes the token, writes credentials, activates the account and saves its activation event. Any failure rolls back all of these. Two activations have one winner. A concurrent resend either loses to activation with 409, or invalidates the old link before activation can consume it. Re-inviting an active account is never a password-reset mechanism.

Invalid, expired, revoked, already used or no-longer-INVITED activation attempts share the existing generic invalid-token response. Weak passwords do not consume the link. Passwords are also limited to 72 UTF-8 bytes for BCrypt; long multibyte inputs return a validation error rather than an encoder failure. Password hashing runs off the reactive event loop.

## Directory

`GET /api/v1/users?query=...&page=0&pageSize=20` returns `items` with only `id`, `displayName`, `email`, nullable `avatarUrl`, and `hasMore` for the page. It includes ACTIVE, INVITED and temporarily LOCKED accounts and omits BLOCKED accounts. This enables both member selection and assignment; the operation applying that choice must still enforce its own role and eligibility rules.

Query is trimmed, case-insensitive, 3–100 Unicode code points, and searched literally in name/email: `%`, `_` and `!` are escaped. Page is 0–1000, page size 1–50. Ordering is lower(displayName), lower(email), id. This is offset pagination for autocomplete, not a stable snapshot across concurrent profile edits. Search reads one extra row to compute `hasMore`; it does not count all matches. Trigram indexes support substring matching. Empty results are 200 with `items=[]`. Avatar failure or a slow storage response affects only that row's optional URL. Avatar lookup has a 250ms timeout (AUTH_DIRECTORY_AVATAR_TIMEOUT) and bounded concurrency, so storage degradation cannot indefinitely delay directory results. Responses use `Cache-Control: no-store`.

## Delivery and operations

The invitation event and account/token are committed together. Notification processing commits an invitation delivery row together with event deduplication and the generic inbox notification. It does not depend on user notification preferences: an invitee has not activated an account yet.

The delivery worker claims up to ten rows with `FOR UPDATE SKIP LOCKED`, sends at most four concurrently and uses two-minute leases. An abandoned lease is recoverable. There are at most eight attempts, with exponential retry delay from one minute, capped at one hour. An expired invitation is not claimed. A successful send, expiry or exhausted delivery removes the raw token from this queue. FAILED delivery requires an explicit fresh resend after the transport problem is fixed.

A row holds the latest event per user: older/out-of-order events and duplicates cannot restore an older token or reset successful delivery. Completion is guarded by event id and lease id so that an older SMTP attempt cannot erase a new invitation. SMTP is at-least-once: a crash after SMTP accepts a message but before recording success can produce a duplicate email. The same one-time token makes duplicate delivery safe. An older message already in flight during resend may arrive later; only the latest link remains usable.

Configure `NOTIFICATION_INVITATION_ACCEPT_URL` to the real frontend invitation form. It must use HTTPS outside localhost; userinfo, fragments and an existing `token` parameter are rejected. The server appends `?token=...` (or `&token=...`). The local default is `http://localhost:3001/login`; the frontend must implement the token query handling described in TAS-208. Configure `NOTIFICATION_MAIL_HOST`, `NOTIFICATION_MAIL_PORT`, `NOTIFICATION_MAIL_USERNAME`, and `NOTIFICATION_MAIL_PASSWORD` using deployment secrets. There is no embedded SMTP password fallback. Socket timeouts are ten seconds. `NOTIFICATION_INVITATION_EMAIL_ENABLED=false` pauses the worker while keeping the queue durable. Poll interval defaults to five seconds.

`deliveryQueued=true` means durable acceptance, not successful SMTP delivery. Monitor `invitation_email_deliveries.status`, attempts and next_attempt_at, and alert on RETRY/FAILED. Logs record event ids and failure classes without bodies or tokens. There is no invitation cancellation API or active-account password reset in this change; existing administrative blocking can stop an unactivated account from being used.

Invalid delivery credentials (including malformed timestamps, missing email and non-string tokens) are permanent event errors: preserve the generic inbox entry and deduplication, skip SMTP, and increment `taska.invitation.delivery.rejected` with bounded `reason=invalid_payload` or `missing_credentials`. Alert on this counter and use the logged event id to investigate; the token/body is never logged. Database failures still propagate and roll back the entire event transaction for retry.

## Rollout

1. Reconcile any accounts with email addresses equal after trim/case folding before applying the unique email index; the migration deliberately fails rather than merging identities or deleting accounts. PostgreSQL migration privileges must allow `pg_trgm`.
2. Deploy notification-service with its new queue migration and SMTP/frontend configuration first, so that new invitation events are durably captured.
3. Deploy auth-service and then api-gateway. The previously internal InviteUser RPC now requires actor_user_id; all repository clients/tests use the verified actor. A new gateway needs the updated auth RPCs. Do not switch traffic to the new REST routes against an old auth-service.
4. Legacy invitation events without delivery credentials still create their generic inbox entry but cannot reconstruct a lost raw token; issue a fresh resend for those users.

Tests cover real HTTP authorization/statuses, gRPC actor propagation, real gRPC/PostgreSQL invitation and activation races, rollback on outbox failure, case folding, TTL precision, wildcard escaping, pagination, row-level avatar failure, duplicate/out-of-order delivery, SMTP failures/retries, stale completion, lease recovery and expiry/secret erasure. SMTP itself is mocked in tests; no real recipient is emailed.
