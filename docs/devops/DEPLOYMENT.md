# TermiNow DevOps — deployment plan (awaiting approval; nothing deployed)

This deploys the DevOps backend to the **same** WHMCS host and folder as the existing endpoints
(`/home/accmovecom/domains/cp.accmove.com/public_html/terminow/`, per `whmcs/README.md`). It adds
new files only — it does **not** modify or overwrite `premium.php`, `lib.php`, `vault*.php`,
`play_sync*.php`, `whmcs_glue.php`, `init.php` or any existing config/secret.

**Status:** not deployed. Production deploy only after approval. Use staging first if one is authorized.

## Authorized access (to confirm, not used yet)
- Deploy per README = upload files into the `terminow/` folder next to `init.php`.
- An SSH key `~/.ssh/mobiclaude_accmove` exists and is the likely authorized SFTP/SSH channel to the
  accmove host. **Not used** — confirm it is the right channel and approve before any upload.

## Patch (new files only — review in this branch, `whmcs/terminow/`)
- `devops_lib.php`   — logic (Firebase verify via lib.php, entitlement via whmcs_glue, OAuth, storage)
- `devops.php`       — bearer POST actions (status/connect/repos/runs/logs/disconnect/revoke)
- `devops_callback.php` — GitHub OAuth callback (state-authenticated; prints a success page)
- `devops_config.sample.php` — template; the real `devops_config.php` is created on the server (below)
- (removed the earlier `docs/devops/reference/*.php` drafts — superseded by the real code)

## Config (create on the server; do NOT commit; do NOT send secrets in chat)
1. `cp devops_config.sample.php devops_config.php` in the `terminow/` folder.
2. Fill `github_client_id`, `github_client_secret` (from the OAuth App), `redirect_uri` =
   `https://cp.accmove.com/terminow/devops_callback.php`, and `token_key_b64` =
   `php -r "echo base64_encode(random_bytes(32)), PHP_EOL;"` (generate once on the server).
3. `devops_config.php` must be readable only by the web user and never world-readable; it holds the
   token encryption key, kept **out of the database**.

## Migration (automatic, additive)
- No manual SQL. `TerminowDevOps\migrate()` runs on the first request and creates two new tables if
  absent: `mod_terminow_devops_conn`, `mod_terminow_devops_state`. It touches no existing table.

## Smoke check after deploy
- `curl -X POST https://cp.accmove.com/terminow/devops.php` → `401 {"error":"Sign in first"}`.
- `curl https://cp.accmove.com/terminow/devops_callback.php` with no/invalid state → a safe error page
  (no token/secret echoed).
- With a real signed-in Premium account from the app: status → connect → authorize on GitHub → callback
  success page → repos/runs/logs.

## Rollback (clean, no effect on existing backend)
1. Delete `devops_lib.php`, `devops.php`, `devops_callback.php`, `devops_config.php` from the server.
2. Optionally `DROP TABLE mod_terminow_devops_state; DROP TABLE mod_terminow_devops_conn;` (these are
   used only by DevOps; dropping them affects nothing else).
3. In the app, the DevOps calls then return 404/503 and the UI shows the honest "service not available"
   state; saved projects and SSH hosts are untouched.

## Steps that touch the existing backend
- **None.** New files + two new tables only. The GitHub OAuth App, the `devops_config.php` secret and
  the callback route are all new and isolated.
