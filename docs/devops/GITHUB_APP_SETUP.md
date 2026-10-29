# TermiNow DevOps — GitHub **private** repositories via a GitHub App

Private repos are added with a **GitHub App** (installation-based), NOT by widening the OAuth scope to
`repo` (which would grant write). The public OAuth App connection is kept exactly as-is; the GitHub App
is a separate, clearly-labelled connection that the user installs on **only the repositories they pick**.

The feature stays **off** (no "Connect private repositories" option appears) until the App is created and
its App ID + private key are on the server. Nothing here needs the credentials in chat.

## Permissions (least privilege — derived from the endpoints actually used)
| Endpoint used | Permission |
|---|---|
| `GET /installation/repositories` | **Repository metadata: Read-only** |
| `GET /repos/{o}/{r}/actions/runs` and `…/runs/{id}/jobs` | **Actions: Read-only** |
| (file contents are never read) | **Contents: not requested** |

No write permissions, no administration, no Contents, no org/members scopes.

## 1. Create the GitHub App (you do this)
GitHub → **Settings → Developer settings → GitHub Apps → New GitHub App**:
- **GitHub App name:** e.g. `TermiNow DevOps` (its slug becomes the install URL; tell me the slug).
- **Homepage URL:** `https://cp.accmove.com/terminow`
- **Callback URL:** `https://cp.accmove.com/terminow/devops_callback.php`  *(bare — same as the others)*
- **Setup URL (after installation):** `https://cp.accmove.com/terminow/devops_callback.php`  *(bare)*
  and tick **"Redirect on update"**. The install flow returns here with `installation_id` + `state`.
- **Request user authorization (OAuth) during installation:** **unchecked** (we bind via `state` + verify).
- **Webhook:** **uncheck "Active"** (not used).
- **Permissions → Repository:** **Metadata: Read-only**, **Actions: Read-only**. Nothing else.
- **Where can this app be installed:** "Only on this account" is fine for now (personal account).
- Create the app, then **Generate a private key** (downloads a `.pem`) and note the **App ID** and the
  app **slug** (from its public page URL `github.com/apps/<slug>`).

> Org installations: for now only a **personal-account** installation binds (we verify the installation
> account equals your GitHub login). Org installs need `read:org` to verify membership — a documented
> follow-up. Also, on orgs the install may require **owner approval**; that's shown as-is by GitHub.

## 2. Load the App ID + slug + private key on the server (no secrets in chat)
The App ID and slug are not secret, but the **private key is** — it must not go through chat. Load all
three with the hidden-input helper (run it yourself in a terminal):

```
! bash /root/setup_devops_github_app.sh
```
It prompts for **App ID** and **slug** (visible) and reads the **private-key .pem** from a file path you
give (never pasted), writing `github_app_id`, `github_app_slug`, `github_app_private_key` into
`devops_config.providers.php` on the server (chmod 600) — the GitHub/GitLab/Bitbucket configs and the
token key are untouched. (Helper script added alongside `setup_devops_provider_config.sh`.)

## 3. In the app
Connect GitHub (public OAuth) as before, then **"Connect private repositories"** appears → it opens the
GitHub App install page → pick repositories → return to the app → the repo list now includes your
private repos, with an **All / Public / Private** filter. Pipelines/logs for a private repo use a
short-lived installation token (minted per call from the App key); the OAuth token still serves public
repos. Projects and linked hosts are unchanged.

## 4. Verify (after setup)
- `configured`/status shows `privateRepos.available = true`; after install, `installed = true` with your
  account.
- A **selected private repo** appears and its pipeline/logs load; a repo you did **not** select is not
  listed and not accessible. Public repos keep working unchanged.
- If the installation is removed on GitHub, private repos drop out and the app offers reconnect; the
  OAuth (public) connection is unaffected.
