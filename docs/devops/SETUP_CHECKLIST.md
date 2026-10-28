# DevOps backend — setup checklist & what's blocking me

Status: **draft.** These are the things that must be in place before the DevOps MVP can connect for real.
I cannot do the server-side parts from this environment (the backend lives on `cp.accmove.com`, not in this
Android repo). I am **not** asking for any secret value in chat — only for access and confirmation.

## What I need access to (blocking the backend implementation)
1. **The backend source** for `terminow/ai.php` and `terminow/premium.php` on `cp.accmove.com` (read access
   to the repo or the files). I need to see, not guess:
   - the Firebase ID-token verification helper (name/signature) so DevOps reuses it;
   - the entitlement check behind `premium.php` (function + table/field) so DevOps uses the same source;
   - the data store (Firestore vs WHMCS MySQL) and the per-user record shape;
   - how secrets/keys are currently stored on the host (env, secret file, WHMCS config).
2. **Deploy path / method** for adding `terminow/devops/*` to that host (how `ai.php` was deployed).

## What you need to create (I can't, and don't send me the secret values)
3. A **GitHub App** (preferred) or OAuth App:
   - Callback/redirect URL: `https://cp.accmove.com/terminow/devops/callback/github`
   - Permissions (read-only, least privilege): GitHub App → `Metadata: read`, `Contents: read`,
     `Actions: read`. OAuth App → `read:user` + (`public_repo` or `repo`). **No `workflow`.**
   - Put the client id/secret in the host's existing secret store (not the DB, not the repo, not chat).
4. An **encryption key** for provider tokens at rest, stored **separately from the database** (reuse the
   host's existing secret mechanism). Don't send it to me.
5. Confirm the **app deep-link scheme** (if the OAuth success page should bounce back into the app).

## What I can prepare now without backend access (in progress)
- `API_CONTRACT.md` — endpoints, auth, entitlement, OAuth state flow, scopes, token handling, errors. ✅
- Reference server stubs under `reference/` showing the required checks (Firebase verify → entitlement →
  single-use state → entitlement re-check → encrypt → never log/return token). These are **reference only**,
  marked `[VERIFY]`, to be reconciled with the real backend source — **not** to be deployed as-is.
- The Android-side DevOps client calls (on `devops-mvp`) once the contract is agreed.

## Security invariants (must hold in the final backend)
- App never holds the GitHub client secret or access token; no client-side OAuth.
- OAuth `state`: random, ≤10 min, single-use, bound to `uid` server-side; callback re-checks entitlement
  before saving.
- Provider tokens encrypted at rest, key separate from DB; tokens/codes never logged or returned to the app.
- Every data endpoint: Firebase token + entitlement + per-repo/run access check.
- Premium expired: data sync disabled; `disconnect`/`revoke` still work; stored config not deleted.
- No second account/entitlement system — reuse the existing one.
