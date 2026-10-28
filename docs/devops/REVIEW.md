# TermiNow DevOps backend — security review

Review of `whmcs/terminow/devops_lib.php`, `devops.php`, `devops_callback.php` against the required
checks. Two issues were found and fixed in this pass; the rest hold.

## Findings & fixes
1. **OAuth state single-use race (FIXED).** `state_consume()` selected the row, checked it, then deleted
   it — two concurrent callbacks with the same state could both pass the check before either deleted.
   Now the DELETE is the atomic gate: the row is deleted and the consumer proceeds only if
   `rowCount() === 1`, so a racing second callback deletes 0 rows and is rejected (400 `bad_state`).
2. **Callback routing / missing page (FIXED).** The callback returned a redirect to `connected.html`
   derived from `redirect_uri`, which needed URL rewriting and an extra file. Now `devops_callback.php`
   prints a success page itself, and `redirect_uri` is the real file path
   `https://cp.accmove.com/terminow/devops_callback.php` (served directly, like `premium.php`), so routing
   matches the implementation with no rewrite.

## Verified
- **Firebase verification + entitlement on every endpoint.** `devops.php` requires a valid bearer token
  (`verify_id_token` via `lib.php`); data actions require Premium now (`terminow_premium`); `connect`
  requires Premium; `disconnect`/`revoke` are intentionally allowed when expired (so a lapsed user can
  still remove the connection). The callback has no bearer (GitHub redirect) and is authenticated by the
  single-use state, with entitlement re-checked by email before the connection is saved.
- **No cross-account access.** Every connection and token is keyed by the token's own `uid`
  (`claims['sub']`); a user can only load their own connection. Repository/run access is enforced by
  GitHub itself via the user's own token; `404/403` from GitHub → `no_access`. `repo` is validated
  (`owner/name`, no `..`), so it cannot traverse the API path (a test caught and fixed this).
- **Token encryption.** AES-256-GCM with a fresh 12-byte IV (nonce) per encryption and the 16-byte GCM
  authentication tag stored and verified on decrypt (a tampered or wrong-key blob fails closed). The key
  is 32 bytes from `devops_config.php`, held outside the database.
- **No secret/token leakage.** Tokens and OAuth codes are never logged and never returned to the app;
  responses carry only repo/run/job metadata, `status`, or an `authorizeUrl`. Error bodies carry only
  machine codes (`premium_required`, `no_access`, `provider_error`, …); the catch-all returns a generic
  503 without the exception message.
- **Scopes.** Read-only: `read:user repo` (drop to `public_repo` for public-only). The `workflow` scope
  (write) is **not** requested — confirmed against GitHub's official scope docs.

## Tests
`php whmcs/tests/test_devops.php` (SQLite, no network) — PASS: encryption round-trip + fresh IV +
wrong-key failure, state single-use + expiry + unknown-state rejection, `repo` validation incl.
path-traversal, and the authorize URL (read-only scope, no `workflow`).

## Not tested here (needs deploy + a real account)
Live Firebase token verification, live WHMCS/Firestore entitlement, the real GitHub OAuth exchange and
API calls, and the DB under MySQL (tested on SQLite). These require the backend deployed with
`devops_config.php` and a signed-in Premium account.
