# TermiNow DevOps — adding GitLab.com and Bitbucket Cloud

GitHub is live. GitLab and Bitbucket are fully implemented (OAuth, token refresh, repo search +
pagination, pipelines, logs, disconnect/revoke) but stay **"Coming soon"** in the app until each one's
OAuth app is registered and its credentials are loaded on the server — and you have confirmed a live
sign-in. Nothing turns on just because code exists.

The app shows each provider's state from the backend:
- **not configured** on the server → "Coming soon"
- **configured** → "Connect <provider>"
- **connected** → "Connected: @you" + its projects, with Disconnect

## Scopes (least-privilege, read-only)

| Provider  | Scopes requested            | Notes |
|-----------|-----------------------------|-------|
| GitHub    | `read:user`                 | Public repos + profile. An OAuth App has no read-only-private scope; `repo` would add private **but with write**. Use a GitHub App for true read-only private. |
| GitLab    | `read_api` `read_user`      | Read-only across the API (projects, pipelines, jobs, identity). No write scope requested. |
| Bitbucket | Account/Repositories/Pipelines = **Read** | Bitbucket permissions are set on the consumer, not the authorize URL. Grant only the three Read permissions. |

## 1. Register the OAuth apps (you do this; secrets never go in chat)

### GitLab.com
1. GitLab → avatar → **Edit profile** → **Applications** → **Add new application**.
2. **Name:** TermiNow DevOps
3. **Redirect URI:** `https://cp.accmove.com/terminow/devops_callback.php?provider=gitlab`
4. **Confidential:** ✔ (checked)
5. **Scopes:** check **`read_api`** and **`read_user`** only.
6. Save → copy the **Application ID** and **Secret**.

### Bitbucket Cloud
1. Bitbucket → **Workspace settings** → **OAuth consumers** → **Add consumer**.
2. **Name:** TermiNow DevOps
3. **Callback URL:** `https://cp.accmove.com/terminow/devops_callback.php?provider=bitbucket`
4. **This is a private consumer:** leave default (it has a secret).
5. **Permissions:** check **Account: Read**, **Repositories: Read**, **Pipelines: Read**. Nothing else.
6. Save → expand the consumer to copy the **Key** (client id) and **Secret**.

## 2. Load the credentials on the server (secret entered hidden, not via chat)

In a terminal tab, run the helper once per provider and type the secret at the hidden prompt:

```
! bash /root/setup_devops_provider_config.sh gitlab
! bash /root/setup_devops_provider_config.sh bitbucket
```

It writes/updates only the provider keys in `devops_config.providers.php` on the server (separate from
the GitHub secret file, chmod 600, owner accmovecom), merging so the two providers coexist. The secret
travels over SSH on stdin — never in arguments, history, or this chat.

## 3. One-time DB migration before the first non-GitHub connection

The live `mod_terminow_devops_conn` table is still keyed by `uid` (GitHub-only era); multi-provider needs
`(uid, provider)`, plus `expires_at` on conn and `provider` on state. Do NOT paste raw `ALTER TABLE` —
the live DB is **MySQL**, which does not support `ADD COLUMN IF NOT EXISTS`, and the primary-key change
must be guarded. Use the reviewed, idempotent, data-preserving script instead:

```
! bash /root/migrate_devops_multiprovider.sh --dry-run   # shows the plan + guards, changes nothing
! bash /root/migrate_devops_multiprovider.sh             # backs up the table, then applies what is missing
```

What it does (source: `whmcs/terminow/migrate_devops_multiprovider.php`, streamed to the server over SSH,
never written to the web root):
- verifies the current schema and the GitHub row (no NULL/empty provider, no duplicate `(uid,provider)`),
  and aborts without any change if a guard fails;
- makes a full copy `mod_terminow_devops_conn_bak_<ts>` (rows + encrypted tokens) as the rollback point;
- adds `expires_at` / `state.provider` and changes the PK to `(uid, provider)` **only if missing** (safe
  to re-run); nothing is deleted;
- prints a verify line and the exact rollback command (`DROP TABLE … ; RENAME … _bak_<ts> …`).

Dry-run against the live DB on 2026-10-29 reported: PK `(uid)`, all three changes needed, **guards pass**.

## 4. Verify live, then rely on it

1. Reload DevOps in the app → the provider shows **Connect**.
2. Connect → authorize on the provider → return to the app → it should show **Connected: @you**.
3. New Project → pick the provider → search repositories → create a project → view its pipeline/logs.
4. Only after a real connect + repo list + pipeline works should the provider be considered live.

## What differs per provider (shown honestly, not faked)

- **GitLab** pipelines have no per-step list like GitHub Actions; the logs view shows each CI **job**
  (name + status), no nested steps.
- **Bitbucket** repo reference is `workspace/repo`; **GitLab** uses the numeric **project id** (so repo
  ids never collide across providers). GitHub uses `owner/name`.
- Deploy/Rollback remain **off** for every provider.
