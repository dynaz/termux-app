<?php
/**
 * TermiNow DevOps — GitHub fine-grained Personal Access Token (PAT) support, kept entirely separate
 * from the public OAuth App connection and from the GitHub App installation. It is a THIRD GitHub
 * credential source the user can add for private repositories, chosen explicitly per repo.
 *
 * Why a PAT, next to OAuth and the GitHub App:
 *   - OAuth App tokens cannot be read-only for private repos (the `repo` scope grants write too).
 *   - The GitHub App needs server-side config (app id + private key) the deployment may not have.
 *   - A fine-grained PAT the user mints themselves can be truly least-privilege and read-only:
 *       Contents: Read-only, Actions: Read-only, Metadata: Read-only (auto) — nothing else.
 *
 * What this module does and does NOT do:
 *   - It takes the token AS-IS from the user (entered in the app, never in chat). We NEVER request,
 *     widen, or auto-add a scope — in particular we never add the classic `repo` scope. The token's
 *     permissions are exactly what the user granted on github.com. The app only ever READS.
 *   - A PAT has NO OAuth refresh flow. When it expires or is revoked, GitHub returns 401 and the app
 *     surfaces "Replace token" (there is nothing to refresh). Expiry is detected reactively on 401, so
 *     a transient network error never shows a false "expired".
 *   - The token is encrypted at rest with the same key held OUTSIDE the DB (devops_config.php) and is
 *     stored under its own connection row (provider key 'github_pat'), so adding/replacing/removing it
 *     never touches the OAuth connection, the GitHub App binding, or the device-side project/host maps.
 *   - Replacing the token overwrites that single row (REPLACE), so the previous token — and any state
 *     derived from it — is gone. There is no shared/global cache; each credential is keyed by uid and
 *     by its own connection row, and the app re-queries status fresh (no stale state).
 *
 * The token is recognised by its FORMAT alone before any network call (fine-grained `github_pat_…`,
 * or a classic `ghp_…`/`gho_…`), so an obviously wrong paste is rejected without hitting GitHub.
 */

namespace TermiNow\DevOps;

require_once __DIR__ . '/devops_providers.php';

/** The storage provider key for the PAT connection row. Not a real provider (never in PROVIDERS). */
const PAT_PROVIDER = 'github_pat';

/**
 * Is this a plausible GitHub token by format alone? Fine-grained PATs are `github_pat_` + a long body;
 * classic tokens are `gh[pousr]_` + a body. We accept both (the user chooses which to mint) but the
 * app's guidance recommends a fine-grained, read-only token. No scope is ever inferred or added here.
 */
function github_pat_valid_format(string $token): bool
{
    if (preg_match('/^github_pat_[A-Za-z0-9_]{22,255}$/', $token)) return true;   // fine-grained
    if (preg_match('/^gh[pousr]_[A-Za-z0-9]{20,255}$/', $token)) return true;      // classic (ghp_, gho_, …)
    return false;
}

/** 'fine_grained' for a github_pat_ token, else 'classic'. Display only; behaviour is identical. */
function github_pat_type(string $token): string
{
    return strncmp($token, 'github_pat_', 11) === 0 ? 'fine_grained' : 'classic';
}

// ---- Storage: one PAT connection row per uid (separate from OAuth) ------------------------------

function load_pat(\PDO $db, string $uid): ?array
{
    $stmt = $db->prepare('SELECT * FROM mod_terminow_devops_conn WHERE uid = ? AND provider = ?');
    $stmt->execute([$uid, PAT_PROVIDER]);
    $row = $stmt->fetch(\PDO::FETCH_ASSOC);
    return $row ?: null;
}

/** Save (or REPLACE, i.e. clear-then-store) the PAT. Overwriting is how "Replace token" clears the old. */
function save_pat(\PDO $db, string $uid, string $email, string $token, string $login, string $key, int $now): void
{
    $enc = encrypt($token, $key);
    $db->prepare('REPLACE INTO mod_terminow_devops_conn
        (uid, provider, email, enc_token, enc_refresh, scopes, login, status, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)')
        ->execute([$uid, PAT_PROVIDER, $email, $enc, github_pat_type($token), $login, 'connected', 0, $now, $now]);
}

function delete_pat(\PDO $db, string $uid): void
{
    $db->prepare('DELETE FROM mod_terminow_devops_conn WHERE uid = ? AND provider = ?')->execute([$uid, PAT_PROVIDER]);
}

/** The decrypted PAT to use now, or throw pat_not_connected when there is none. No refresh (PATs have none). */
function pat_token(\PDO $db, string $uid, string $key): string
{
    $row = load_pat($db, $uid);
    if (!$row) throw new Failure(409, 'pat_not_connected');
    return decrypt((string)$row['enc_token'], $key);
}

// ---- Identity / validation ----------------------------------------------------------------------

/**
 * Validate a token with GitHub by reading the account. Returns [status, login]. A 401 means the token
 * is bad/expired/revoked; the caller turns that into "bad_token" (connect) or "token_invalid" (later).
 */
function github_pat_identify(string $token): array
{
    [$st, $json] = api_get('github', $token, '/user');
    $login = ($st === 200 && is_array($json)) ? (string)($json['login'] ?? '') : '';
    return [$st, $login];
}

/**
 * Connect (or replace) a PAT: reject a malformed token by format first, then verify it at GitHub and
 * store it. Returns ['login'=>…, 'tokenType'=>…]. Throws 400 bad_token on a wrong/expired/revoked token
 * so the app can show the entry form again. Never logs or echoes the token.
 */
function github_pat_connect(\PDO $db, string $uid, string $email, string $token, string $key, int $now): array
{
    $token = trim($token);
    if (!github_pat_valid_format($token)) throw new Failure(400, 'bad_token');
    [$st, $login] = github_pat_identify($token);
    if ($st === 401 || $st === 403) throw new Failure(400, 'bad_token');
    if ($st !== 200) throw new Failure(502, 'provider_error');
    save_pat($db, $uid, $email, $token, $login, $key, $now);
    return ['login' => $login, 'tokenType' => github_pat_type($token)];
}

/**
 * The PAT sub-status for the GitHub provider: connected + login + whether the stored token still works.
 * `valid` is false only on a definitive 401/403 from /user; any other status or a network error leaves
 * the last-known state so a blip never shows a false "expired" (→ the app would wrongly prompt Replace).
 */
function github_pat_status(\PDO $db, string $uid, string $key, int $now): array
{
    $row = load_pat($db, $uid);
    if (!$row) return ['available' => true, 'connected' => false, 'login' => '', 'valid' => false, 'tokenType' => ''];
    $login = (string)$row['login'];
    $valid = true;
    try {
        $tok = decrypt((string)$row['enc_token'], $key);
        [$st, $live] = github_pat_identify($tok);
        if ($st === 401 || $st === 403) $valid = false;
        elseif ($st === 200 && $live !== '') $login = $live;
    } catch (\Throwable $e) {
        // Decrypt failure (key change) counts as unusable → Replace token.
        $valid = false;
    }
    return ['available' => true, 'connected' => true, 'login' => $login, 'valid' => $valid,
            'tokenType' => (string)($row['scopes'] ?? '')];
}

// ---- Repositories the PAT can read (tagged source='pat') ----------------------------------------

/**
 * The repositories the PAT can access, normalized and tagged source='pat'. For a fine-grained token
 * this is exactly the repos the user granted it (public + the chosen private ones). Throws 401
 * token_invalid on a dead token (the caller turns it into a non-hiding warning), 502 on other errors.
 */
function github_pat_list_repos(string $token): array
{
    [$st, $json] = api_get_all('github', $token, '/user/repos?sort=pushed&affiliation=owner,collaborator,organization_member');
    if ($st === 401 || $st === 403) throw new Failure(401, 'token_invalid');
    if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
    return array_map(fn($r) => [
        'provider'      => 'github',
        'source'        => 'pat',
        'id'            => (string)($r['full_name'] ?? ''),
        'fullName'      => (string)($r['full_name'] ?? ''),
        'private'       => (bool)($r['private'] ?? false),
        'defaultBranch' => (string)($r['default_branch'] ?? ''),
        'pushedAt'      => (string)($r['pushed_at'] ?? ''),
        'htmlUrl'       => (string)($r['html_url'] ?? ''),
    ], $json);
}

/**
 * Merge the PAT's repos into the GitHub repo list (PAT entries win on name — the explicit, private-capable
 * credential). A dead PAT does NOT drop the whole list to public-only in silence: we keep the OAuth list and
 * push a 'pat_invalid' warning so the home screen can say "token expired — Replace", never hiding the error.
 */
function github_merge_pat_repos(\PDO $db, string $uid, string $key, array $repos, array &$warnings): array
{
    $row = load_pat($db, $uid);
    if (!$row) return $repos;
    try {
        $patRepos = github_pat_list_repos(decrypt((string)$row['enc_token'], $key));
    } catch (\Throwable $e) {
        $warnings[] = ($e instanceof Failure && $e->getMessage() === 'token_invalid') ? 'pat_invalid' : 'pat_error';
        return $repos;
    }
    $byName = [];
    foreach ($repos as $r) $byName[$r['fullName']] = $r;
    foreach ($patRepos as $r) $byName[$r['fullName']] = $r;   // PAT entry wins (private-capable, explicit)
    return array_values($byName);
}
