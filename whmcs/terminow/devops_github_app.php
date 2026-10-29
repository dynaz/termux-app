<?php
/**
 * TermiNow DevOps — GitHub App support for PRIVATE repositories, kept entirely separate from the public
 * OAuth App connection. We do NOT widen the OAuth scope to `repo` (that would grant write). Instead the
 * user installs a GitHub App on exactly the repositories they choose; the server authenticates as the
 * App (RS256 JWT signed with the App private key) and mints short-lived INSTALLATION tokens on demand.
 *
 * Least-privilege App permissions (from the endpoints actually used):
 *   - Metadata: Read   → GET /installation/repositories
 *   - Actions:  Read   → GET /repos/{owner}/{repo}/actions/runs[/{id}/jobs]
 *   - Contents: Read   → NOT requested (we never read file contents); no write, no administration.
 *
 * Security model:
 *   - The installation is bound to a TermiNow uid ONCE, during the App "Setup URL" return, authenticated
 *     by the same single-use uid-bound `state` used for OAuth (provider marker "github_app").
 *   - At bind time we verify the installation's account actually belongs to the OAuth-identified user
 *     (account login == the user's GitHub login). The installation_id from the client is NEVER trusted on
 *     its own; after binding it is read from the server, and an installation token only ever grants the
 *     installation's own repositories, so per-repo access is enforced by GitHub itself on every call.
 *   - Org installations need read:org to verify membership, which the public OAuth token does not have,
 *     so for now only personal-account installations bind (org support is a documented follow-up).
 *
 * Tokens: installation tokens are short-lived and minted per use from the App JWT; nothing long-lived is
 * stored for the App connection (only installation_id + verified account login). The App private key is
 * held in devops_config.providers.php (outside the repo), never in the client or chat.
 */

namespace TermiNow\DevOps;

require_once __DIR__ . '/devops_providers.php';

/** App config from devops_config.providers.php, or null when the GitHub App is not set up (stays off). */
function github_app_config(array $config): ?array
{
    $id   = (string)($config['github_app_id'] ?? '');
    $key  = (string)($config['github_app_private_key'] ?? '');
    $slug = (string)($config['github_app_slug'] ?? '');
    if ($id === '' || $key === '' || $slug === '') return null;
    if (array_key_exists('github_app_enabled', $config) && !$config['github_app_enabled']) return null;
    return ['app_id' => $id, 'private_key' => $key, 'slug' => $slug];
}

function github_app_available(array $config): bool { return github_app_config($config) !== null; }

/** base64url without padding. */
function b64url(string $s): string { return rtrim(strtr(base64_encode($s), '+/', '-_'), '='); }

/** A short-lived RS256 App JWT (max 10 min per GitHub). Throws if the private key cannot sign. */
function github_app_jwt(array $appcfg, int $now): string
{
    $header  = b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $payload = b64url(json_encode(['iat' => $now - 60, 'exp' => $now + 540, 'iss' => $appcfg['app_id']]));
    $signing = $header . '.' . $payload;
    $sig = '';
    if (!openssl_sign($signing, $sig, $appcfg['private_key'], OPENSSL_ALGO_SHA256))
        throw new Failure(503, 'app_jwt_failed');
    return $signing . '.' . b64url($sig);
}

/** A GitHub REST call authenticated by a bearer (App JWT or installation token). Returns [status, decoded]. */
function github_app_api(string $bearer, string $method, string $path, ?string $body = null): array
{
    $r = http_request($method, 'https://api.github.com' . $path, [
        'Authorization: Bearer ' . $bearer,
        'Accept: application/vnd.github+json',
        'X-GitHub-Api-Version: 2022-11-28',
        'User-Agent: TermiNow-DevOps',
    ], $body);
    return [$r['status'], $r['body'] === '' ? null : json_decode($r['body'], true)];
}

/** The URL to install/choose repositories for the App, carrying the uid-bound single-use state. */
function github_app_install_url(array $appcfg, string $state): string
{
    return 'https://github.com/apps/' . rawurlencode($appcfg['slug']) . '/installations/new?state=' . rawurlencode($state);
}

/** The account login an installation belongs to (for ownership verification). '' on failure. */
function github_app_installation_account(array $appcfg, string $installationId, int $now): string
{
    [$st, $j] = github_app_api(github_app_jwt($appcfg, $now), 'GET', '/app/installations/' . rawurlencode($installationId));
    return ($st === 200 && is_array($j)) ? (string)($j['account']['login'] ?? '') : '';
}

/** Mint a short-lived installation access token. Throws on failure (e.g. installation revoked). */
function github_app_installation_token(array $appcfg, string $installationId, int $now): string
{
    [$st, $j] = github_app_api(github_app_jwt($appcfg, $now), 'POST',
        '/app/installations/' . rawurlencode($installationId) . '/access_tokens');
    if ($st !== 201 || !is_array($j) || empty($j['token'])) throw new Failure(502, 'app_token_failed');
    return (string)$j['token'];
}

/** The repositories the installation can access (the ones the user selected), normalized + tagged app/private. */
function github_app_list_repos(string $instToken): array
{
    $out = [];
    $seen = [];
    for ($page = 1; $page <= 10; $page++) {
        [$st, $j] = github_app_api($instToken, 'GET', '/installation/repositories?per_page=100&page=' . $page);
        if ($st !== 200 || !is_array($j)) { if ($page === 1) throw new Failure(502, 'provider_error'); break; }
        $list = $j['repositories'] ?? [];
        if (count($list) === 0) break;
        foreach ($list as $r) {
            $full = (string)($r['full_name'] ?? '');
            if ($full === '' || isset($seen[$full])) continue;
            $seen[$full] = true;
            $out[] = [
                'provider'      => 'github',
                'source'        => 'app',               // distinguishes App (private-capable) from OAuth repos
                'id'            => $full,
                'fullName'      => $full,
                'private'       => (bool)($r['private'] ?? false),
                'defaultBranch' => (string)($r['default_branch'] ?? ''),
                'pushedAt'      => (string)($r['pushed_at'] ?? ''),
                'htmlUrl'       => (string)($r['html_url'] ?? ''),
            ];
        }
        if (count($list) < 100) break;
    }
    return $out;
}

// ---- Storage: one App installation binding per uid (no long-lived token) ------------------------

function github_app_migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_devops_ghapp (
        uid VARCHAR(128) NOT NULL PRIMARY KEY,
        installation_id VARCHAR(32) NOT NULL,
        account_login VARCHAR(100) NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
}

function github_app_save(\PDO $db, string $uid, string $installationId, string $account, int $now): void
{
    $db->prepare('REPLACE INTO mod_terminow_devops_ghapp (uid, installation_id, account_login, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)')->execute([$uid, $installationId, $account, $now, $now]);
}

function github_app_load(\PDO $db, string $uid): ?array
{
    $s = $db->prepare('SELECT * FROM mod_terminow_devops_ghapp WHERE uid = ?');
    $s->execute([$uid]);
    $row = $s->fetch(\PDO::FETCH_ASSOC);
    return $row ?: null;
}

function github_app_delete(\PDO $db, string $uid): void
{
    $db->prepare('DELETE FROM mod_terminow_devops_ghapp WHERE uid = ?')->execute([$uid]);
}

/**
 * Verify and bind an installation to the uid at setup time. $oauthLogin is the user's GitHub login from
 * their existing OAuth connection (identifies who they are). Binds only when the installation's account
 * matches that login (personal account). Returns the account login on success; throws Failure otherwise.
 */
function github_app_bind(\PDO $db, array $appcfg, string $uid, string $installationId, string $oauthLogin, int $now): string
{
    if (!preg_match('/^[0-9]+$/', $installationId)) throw new Failure(400, 'bad_installation');
    if ($oauthLogin === '') throw new Failure(409, 'connect_github_first');
    $account = github_app_installation_account($appcfg, $installationId, $now);
    if ($account === '') throw new Failure(502, 'app_install_failed');
    if (strcasecmp($account, $oauthLogin) !== 0) throw new Failure(403, 'installation_not_yours'); // org installs: follow-up
    github_app_save($db, $uid, $installationId, $account, $now);
    return $account;
}
