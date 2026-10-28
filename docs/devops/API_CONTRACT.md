# TermiNow DevOps backend — API contract

Status: **implemented, not deployed.** The real server code is in `whmcs/terminow/`:
`devops_lib.php` (logic + tests), `devops.php` (bearer POST actions), `devops_callback.php` (GitHub OAuth
callback), `devops_config.sample.php` (config template). Unit test: `whmcs/tests/test_devops.php`
(SQLite, no network) — run with `php whmcs/tests/test_devops.php`. It reuses the existing `lib.php`
(Firebase verify) and `whmcs_glue.php` (entitlement). It is **not deployed** and needs config +
deployment (see SETUP_CHECKLIST.md). This document describes the endpoints the MVP (GitHub first)
needs. It reuses TermiNow's existing Firebase auth and entitlement — it does **not** add a second
account or Premium system. Items marked **[VERIFY]** must be confirmed against the real backend source
on `cp.accmove.com` (the `terminow/ai.php` + `terminow/premium.php` code and its DB/Firestore layer)
before writing the final implementation. Do not deploy over the existing backend by guessing its shape.

## Where it lives
- Host: `https://cp.accmove.com/terminow/` (same host as `ai.php`, `premium.php`).
- Proposed path: `https://cp.accmove.com/terminow/devops/<action>` (single router `devops.php`, or one file
  per action — **[VERIFY]** match whatever `ai.php` uses so routing/bootstrap is consistent).
- The Android app calls these with the user's **Firebase ID token** in `Authorization: Bearer <idToken>`,
  exactly like the AI backend. **Exception:** the GitHub OAuth callback (`callback/github`) is hit by
  GitHub's redirect, which carries **no** Firebase header — it is authenticated by the `state` value
  (see below), not by a bearer token.

## Auth + entitlement (every endpoint except the GitHub callback)
1. Verify the Firebase ID token (same verification `ai.php` already does — reuse it, do not re-implement). **[VERIFY]** the exact helper/function name.
2. Resolve the `uid`.
3. Check Premium entitlement for that `uid` using the **existing** entitlement logic behind
   `premium.php`. **[VERIFY]** the function/table that `premium.php` reads so DevOps uses the same source
   of truth (no duplicate entitlement).
4. If not Premium → `403 {"error":"premium_required"}`. The app shows the existing upgrade flow.
5. Only then run the action.

When Premium has expired: **background sync is disabled** (the server stops serving `repos`/`runs`/`logs`
and stops any scheduled refresh), but `disconnect` and `revoke` remain allowed so the user can remove the
connection. Stored connections/config are **not** deleted on expiry.

## GitHub OAuth (server-side only)
- The app never performs OAuth and never sees the GitHub client secret or the access token.
- Use **[VERIFY: GitHub App vs OAuth App]** — recommended a **GitHub App** with fine-grained,
  least-privilege permissions; an OAuth App is acceptable for the MVP. Scopes/permissions below.

### `GET /devops/connect/github`  (Firebase auth + entitlement)
Returns an authorize URL the app opens in a browser.
- Server generates `state` = cryptographically random, **single-use, short-lived (≤10 min)**, stored
  server-side bound to the `uid` (and a PKCE/`nonce` if desired). Never put the `uid` itself in `state`.
- Response: `{"authorizeUrl":"https://github.com/login/oauth/authorize?client_id=...&redirect_uri=...&scope=...&state=..."}`
- `redirect_uri` = `https://cp.accmove.com/terminow/devops/callback/github` (must match the GitHub app config exactly).

### `GET /devops/callback/github?code=...&state=...`  (NO Firebase header)
Hit by GitHub's redirect.
1. Look up `state`; reject if unknown, expired, or already used (single-use → delete/mark immediately).
2. Recover the `uid` bound to that `state`.
3. **Re-check entitlement for that `uid`** before saving anything (a user could have lapsed between
   connect and callback).
4. Exchange `code` → access token **server-side** (client secret stays on the server).
5. Encrypt the token and store it (see Token storage); never log the token or the code.
6. Redirect to a simple success page (or an app deep link **[VERIFY]** the app's deep-link scheme).

### Scopes / permissions (from official GitHub docs — minimal, read-only)
Reading repositories + Actions workflow runs + logs does **not** need the `workflow` scope (that scope is
only for *adding/updating workflow files*). Use the least that works:
- **OAuth App:** `read:user` (profile) + for repos/Actions: `public_repo` (public only) or `repo` (if
  private repos must be listed). **Do not add `workflow`.**
- **GitHub App (preferred):** fine-grained repository permissions `Metadata: read`, `Contents: read`,
  `Actions: read`. No workflow write permission.
- Ref: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps

## Data endpoints (Firebase auth + entitlement + per-resource access)
All responses JSON. The server calls the GitHub API with the stored token; the token never leaves the server.

### `GET /devops/repos`
List the connected user's repositories (id, full_name, private, default_branch, pushed_at). Paginated.

### `GET /devops/runs?repo=<owner/name>&page=<n>`
List GitHub Actions workflow runs for a repo the user can access. Server **must verify the user actually
has access to that repo** via the token (never trust the `repo` param blindly). Returns run id, name,
status, conclusion, head_branch, created_at, html_url.

### `GET /devops/logs?repo=<owner/name>&run=<id>`
Return (or stream) the logs for one run the user can access. GitHub returns a redirect to a log archive;
the server fetches it with the token and returns text/stream — the token is not exposed.

### `POST /devops/disconnect`
Remove the stored connection for this `uid`. Allowed even when Premium is expired.

### `POST /devops/revoke`
Revoke the token at GitHub (OAuth App token revocation / GitHub App installation revocation) **then**
delete the stored record. Allowed when expired.

## Token storage (server)
- Encrypt provider tokens at rest. The encryption **key is stored separately from the database**
  (env/secret store/file outside the DB), **[VERIFY]** how `ai.php`/the host already manages secrets so
  the same mechanism is reused.
- Never return the token to the app; never write it (or the OAuth `code`) to logs.
- Store per `uid`: encrypted access token, encrypted refresh token (if GitHub App), token type, scopes
  granted, GitHub login/id, created/updated timestamps, connection status.
- **[VERIFY]** the existing storage: does the backend use Firestore (the app reads
  `firestore.googleapis.com`) or the WHMCS MySQL DB? Use the same store and the existing per-user record
  shape — do not create a parallel store.

## Error model
- `401 {"error":"unauthenticated"}` — bad/missing Firebase token.
- `403 {"error":"premium_required"}` — valid user, no entitlement.
- `403 {"error":"no_access"}` — the user cannot access the requested repo/run.
- `409 {"error":"not_connected"}` — no GitHub connection for this user.
- `400 {"error":"bad_state"}` — OAuth state unknown/expired/used.
- `502 {"error":"provider_error","detail":...}` — GitHub API failure (no secrets in detail).

## Explicitly NOT in this MVP
- Writing/triggering workflows or deployments (would need write scopes) — read-only first.
- Any client-side OAuth or client-held secret.
