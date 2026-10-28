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
3. A GitHub **OAuth App** (the implemented flow — do not mix with GitHub-App installations):
   - Create at GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App.
   - Non-secret values to set:
     - Application name: e.g. "TermiNow DevOps"
     - Homepage URL: `https://terminow.online` (or your site)
     - **Authorization callback URL** (must match exactly): `https://cp.accmove.com/terminow/devops_callback.php`
   - Scopes are requested by the code at authorize time (`read:user repo`, or `public_repo` for public
     only) — **no `workflow`**, read-only for the MVP.
   - Put the generated **Client ID** and a **Client secret** into `devops_config.php` on the server
     (not the DB, not the repo, not chat).
4. An **encryption key** for provider tokens at rest, stored **separately from the database** (reuse the
   host's existing secret mechanism). Don't send it to me.
5. Confirm the **app deep-link scheme** (if the OAuth success page should bounce back into the app).

## What is implemented (in this repo, matching the real backend patterns)
- Backend: `whmcs/terminow/devops_lib.php` (Firebase verify via `lib.php`, entitlement via `whmcs_glue.php`,
  `mod_terminow_devops_*` tables, AES-256-GCM token encryption with the key from `devops_config.php`,
  single-use short-lived uid-bound OAuth state with an entitlement re-check in the callback, GitHub read
  endpoints, disconnect/revoke), `devops.php`, `devops_callback.php`, `devops_config.sample.php`.
- Backend test: `whmcs/tests/test_devops.php` — **passes** (`php whmcs/tests/test_devops.php`).
- App: `TerminowDevOps` client + `TerminowDevOpsUi` flow + `TerminowStore` project store, Premium-gated.
- Still required before it works end to end: deploy these files to cp.accmove.com, create
  `devops_config.php` (items 3–4 below), route `…/devops/callback/github` to `devops_callback.php`.

## Security invariants (must hold in the final backend)
- App never holds the GitHub client secret or access token; no client-side OAuth.
- OAuth `state`: random, ≤10 min, single-use, bound to `uid` server-side; callback re-checks entitlement
  before saving.
- Provider tokens encrypted at rest, key separate from DB; tokens/codes never logged or returned to the app.
- Every data endpoint: Firebase token + entitlement + per-repo/run access check.
- Premium expired: data sync disabled; `disconnect`/`revoke` still work; stored config not deleted.
- No second account/entitlement system — reuse the existing one.
