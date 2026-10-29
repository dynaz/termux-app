<?php
/**
 * TermiNow DevOps (GitHub) backend logic, without WHMCS so it can be tested on its own. Mirrors the
 * patterns in lib.php / vault_lib.php: Firebase-verified users, entitlement from the existing sources,
 * mod_terminow_* tables, and nothing secret logged.
 *
 * The provider (GitHub) OAuth happens entirely server-side: the app never sees the client secret or the
 * access token. Tokens are encrypted at rest with a key held OUTSIDE the database (devops_config.php,
 * which is not in the repo). The OAuth callback carries no Firebase header, so it is authenticated by a
 * random, short-lived, single-use `state` bound to the uid, and entitlement is re-checked before the
 * connection is saved.
 */

namespace TermiNow\DevOps;

const GITHUB_AUTHORIZE = 'https://github.com/login/oauth/authorize';
const GITHUB_TOKEN     = 'https://github.com/login/oauth/access_token';
const GITHUB_API       = 'https://api.github.com';
// The app only READS repos + Actions runs/logs, and never requests the `workflow` scope (that is for
// writing workflow files). HONEST SCOPE NOTE: an OAuth App has no read-only-private scope — `repo`
// grants read AND WRITE to private repos, and `public_repo` grants write to public repos. So this token
// is NOT read-only even though the app only reads. For truly least-privilege read-only access (incl.
// private repos: Contents:read + Actions:read + Metadata:read) use a GitHub App instead. The scope is
// configurable so the deployment can choose: default here is public-only + profile (no private, no repo
// write granted); set 'scopes' in devops_config.php to 'read:user repo' to include private repos
// (accepting that the token then also has write access).
const GITHUB_SCOPES    = 'read:user';
const STATE_TTL        = 600;   // 10 minutes
const CONFIG_FILE      = __DIR__ . '/devops_config.php';

class Failure extends \Exception
{
    public int $status;
    public array $extra;
    public function __construct(int $status, string $message, array $extra = [])
    {
        parent::__construct($message);
        $this->status = $status;
        $this->extra = $extra;
    }
}

/**
 * Load the DevOps config (GitHub client id/secret, token encryption key, redirect uri) from a file kept
 * outside the repo and the database. Fails closed. See docs/devops/SETUP_CHECKLIST.md.
 * Expected shape: ['github_client_id'=>..., 'github_client_secret'=>..., 'token_key'=>32 raw bytes
 * (or base64 in token_key_b64), 'redirect_uri'=>...].
 */
function config(): array
{
    if (!is_file(CONFIG_FILE)) throw new Failure(503, 'DevOps not configured');
    $config = require CONFIG_FILE;
    if (!is_array($config)) throw new Failure(503, 'DevOps not configured');
    foreach (['github_client_id', 'github_client_secret', 'redirect_uri'] as $key)
        if (empty($config[$key]) || !is_string($config[$key])) throw new Failure(503, 'DevOps not configured');
    if (!empty($config['token_key_b64'])) $config['token_key'] = base64_decode((string)$config['token_key_b64'], true);
    if (empty($config['token_key']) || strlen((string)$config['token_key']) !== 32) throw new Failure(503, 'DevOps not configured');
    return $config;
}

/** Create the tables if they do not exist. Works with MySQL and SQLite, like vault_lib::migrate(). */
function migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_devops_conn (
        uid VARCHAR(128) NOT NULL PRIMARY KEY,
        email VARCHAR(191) NOT NULL,
        provider VARCHAR(16) NOT NULL,
        enc_token TEXT NOT NULL,
        enc_refresh TEXT NULL,
        scopes VARCHAR(255) NOT NULL,
        login VARCHAR(100) NOT NULL,
        status VARCHAR(16) NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_devops_state (
        state VARCHAR(64) NOT NULL PRIMARY KEY,
        uid VARCHAR(128) NOT NULL,
        email VARCHAR(191) NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used INTEGER NOT NULL DEFAULT 0)');
}

// ---- Token encryption (server-side, key outside the DB) -----------------------------------------

function encrypt(string $plaintext, string $key): string
{
    $iv = random_bytes(12);
    $tag = '';
    $ct = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    if ($ct === false) throw new Failure(503, 'Encrypt failed');
    return base64_encode($iv . $tag . $ct);
}

function decrypt(string $stored, string $key): string
{
    $raw = base64_decode($stored, true);
    if ($raw === false || strlen($raw) < 29) throw new Failure(503, 'Decrypt failed');
    $iv = substr($raw, 0, 12);
    $tag = substr($raw, 12, 16);
    $ct = substr($raw, 28);
    $pt = openssl_decrypt($ct, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    if ($pt === false) throw new Failure(503, 'Decrypt failed');
    return $pt;
}

// ---- OAuth state (single-use, short-lived, uid-bound) -------------------------------------------

function state_create(\PDO $db, string $uid, string $email, int $now): string
{
    $state = bin2hex(random_bytes(32));
    $stmt = $db->prepare('INSERT INTO mod_terminow_devops_state (state, uid, email, created_at, expires_at, used)
        VALUES (?, ?, ?, ?, ?, 0)');
    $stmt->execute([$state, $uid, $email, $now, $now + STATE_TTL]);
    return $state;
}

/** Consume a state exactly once; returns [uid, email] or throws. Also sweeps expired rows. */
function state_consume(\PDO $db, string $state, int $now): array
{
    if (!preg_match('/^[0-9a-f]{64}$/', $state)) throw new Failure(400, 'bad_state');
    $db->prepare('DELETE FROM mod_terminow_devops_state WHERE expires_at < ?')->execute([$now]);
    $db->beginTransaction();
    try {
        $stmt = $db->prepare('SELECT uid, email, expires_at FROM mod_terminow_devops_state WHERE state = ?');
        $stmt->execute([$state]);
        $row = $stmt->fetch(\PDO::FETCH_ASSOC);
        if (!$row || (int)$row['expires_at'] < $now) throw new Failure(400, 'bad_state');
        // The DELETE is the single-use gate: exactly one concurrent consumer can remove the row, so a
        // second (racing) callback with the same state deletes 0 rows and is rejected.
        $del = $db->prepare('DELETE FROM mod_terminow_devops_state WHERE state = ?');
        $del->execute([$state]);
        if ($del->rowCount() !== 1) throw new Failure(400, 'bad_state');
        $db->commit();
        return [(string)$row['uid'], (string)$row['email']];
    } catch (\Throwable $e) {
        $db->rollBack();
        if ($e instanceof Failure) throw $e;
        throw new Failure(400, 'bad_state');
    }
}

// ---- GitHub ------------------------------------------------------------------------------------

function authorize_url(array $config, string $state): string
{
    return GITHUB_AUTHORIZE . '?' . http_build_query([
        'client_id'    => $config['github_client_id'],
        'redirect_uri' => $config['redirect_uri'],
        // Deployment may widen this (e.g. to include private repos) via config, knowing the trade-off.
        'scope'        => (string)($config['scopes'] ?? GITHUB_SCOPES),
        'state'        => $state,
        'allow_signup' => 'false',
    ]);
}

/** Exchange an OAuth code for an access token, server-side. Returns the decoded token response. */
function exchange_code(array $config, string $code): array
{
    $curl = curl_init(GITHUB_TOKEN);
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_POST => true,
        CURLOPT_HTTPHEADER => ['Accept: application/json'],
        CURLOPT_POSTFIELDS => http_build_query([
            'client_id'     => $config['github_client_id'],
            'client_secret' => $config['github_client_secret'],
            'code'          => $code,
            'redirect_uri'  => $config['redirect_uri'],
        ]),
    ]);
    $body = curl_exec($curl);
    $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    $json = is_string($body) ? json_decode($body, true) : null;
    if ($status !== 200 || !is_array($json) || empty($json['access_token'])) throw new Failure(502, 'provider_error');
    return $json;
}

/** A GitHub REST GET with the user's token. Returns [status, decoded]. Never logs the token. */
function github_get(string $token, string $path): array
{
    $curl = curl_init(GITHUB_API . $path);
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 12,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $token,
            'Accept: application/vnd.github+json',
            'X-GitHub-Api-Version: 2022-11-28',
            'User-Agent: TermiNow-DevOps',
        ],
    ]);
    $body = curl_exec($curl);
    $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    return [$status, is_string($body) ? json_decode($body, true) : null];
}

/**
 * Fetch every page of a paginated GitHub list endpoint with the user's token, following the page
 * number until a short page is returned (GitHub returns the full `per_page` while more remain) or a
 * safety cap is hit. `$path` must already carry its query string WITHOUT a `page` param. Returns
 * [status, items]: status 200 with the accumulated items, or the first page's error status. This is
 * why repo search can find repositories beyond the first page — never just page one.
 */
function github_get_all(string $token, string $path, int $perPage = 100, int $maxPages = 10): array
{
    $sep = (strpos($path, '?') === false) ? '?' : '&';
    $items = [];
    for ($page = 1; $page <= $maxPages; $page++) {
        [$st, $json] = github_get($token, $path . $sep . 'per_page=' . $perPage . '&page=' . $page);
        if ($st !== 200 || !is_array($json)) {
            // Page 1 failing is a real error; a later page failing keeps what we already have.
            if ($page === 1) return [$st, null];
            break;
        }
        if (count($json) === 0) break;
        foreach ($json as $it) $items[] = $it;
        if (count($json) < $perPage) break; // last page
    }
    return [200, $items];
}

/** Revoke an OAuth token at GitHub (app token grant deletion). Best-effort. */
function revoke_token(array $config, string $token): void
{
    $curl = curl_init(GITHUB_API . '/applications/' . rawurlencode($config['github_client_id']) . '/grant');
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_CUSTOMREQUEST => 'DELETE',
        CURLOPT_USERPWD => $config['github_client_id'] . ':' . $config['github_client_secret'],
        CURLOPT_HTTPHEADER => ['Accept: application/vnd.github+json', 'User-Agent: TermiNow-DevOps'],
        CURLOPT_POSTFIELDS => json_encode(['access_token' => $token]),
    ]);
    curl_exec($curl);
    curl_close($curl);
}

// ---- Storage -----------------------------------------------------------------------------------

function load_connection(\PDO $db, string $uid): ?array
{
    $stmt = $db->prepare('SELECT * FROM mod_terminow_devops_conn WHERE uid = ?');
    $stmt->execute([$uid]);
    $row = $stmt->fetch(\PDO::FETCH_ASSOC);
    return $row ?: null;
}

function save_connection(\PDO $db, string $uid, string $email, array $token, string $key, int $now): void
{
    $enc = encrypt((string)$token['access_token'], $key);
    $encRefresh = empty($token['refresh_token']) ? null : encrypt((string)$token['refresh_token'], $key);
    $login = '';
    [$st, $me] = github_get((string)$token['access_token'], '/user');
    if ($st === 200 && is_array($me)) $login = (string)($me['login'] ?? '');
    $db->prepare('REPLACE INTO mod_terminow_devops_conn
        (uid, email, provider, enc_token, enc_refresh, scopes, login, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
        ->execute([$uid, $email, 'github', $enc, $encRefresh, (string)($token['scope'] ?? GITHUB_SCOPES), $login, 'connected', $now, $now]);
}

function delete_connection(\PDO $db, string $uid): void
{
    $db->prepare('DELETE FROM mod_terminow_devops_conn WHERE uid = ?')->execute([$uid]);
}

/** A repo reference `owner/name`, validated so it cannot be an injection into the API path. */
function repo_param(array $request): string
{
    $repo = (string)($request['repo'] ?? '');
    // No "..": a dot-run segment would be path traversal into the GitHub API path.
    if (!preg_match('~^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$~', $repo) || strpos($repo, '..') !== false)
        throw new Failure(400, 'bad_request');
    return $repo;
}

/**
 * Handle a bearer-authenticated POST action from the app (never the GitHub callback, which is state-
 * authenticated in devops_callback.php). $isPremiumNow is a closure returning the current entitlement.
 * Data actions require Premium now; disconnect/revoke are allowed even when it has lapsed, so a user can
 * always remove a connection. Returns the JSON body to send.
 */
function handle(\PDO $db, array $claims, string $request, callable $isPremiumNow, int $now): array
{
    $req = json_decode($request, true);
    if (!is_array($req)) throw new Failure(400, 'bad_request');
    $uid = (string)($claims['sub'] ?? '');
    $email = (string)($claims['email'] ?? '');
    $action = (string)($req['action'] ?? '');

    // Actions allowed regardless of entitlement, so an expired user can still remove the connection.
    if ($action === 'disconnect') {
        delete_connection($db, $uid);
        return ['status' => 'disconnected'];
    }
    if ($action === 'revoke') {
        $conn = load_connection($db, $uid);
        if ($conn) {
            try { revoke_token(config(), decrypt((string)$conn['enc_token'], config()['token_key'])); }
            catch (\Throwable $e) { /* best effort; still delete locally */ }
        }
        delete_connection($db, $uid);
        return ['status' => 'revoked'];
    }

    // Everything else needs Premium now (sync is disabled when it has lapsed).
    if (!$isPremiumNow()) throw new Failure(403, 'premium_required');

    if ($action === 'connect') {
        return ['authorizeUrl' => authorize_url(config(), state_create($db, $uid, $email, $now))];
    }
    if ($action === 'status') {
        $conn = load_connection($db, $uid);
        return ['connected' => (bool)$conn, 'provider' => 'github', 'login' => $conn['login'] ?? ''];
    }

    // Data actions need a stored connection.
    $conn = load_connection($db, $uid);
    if (!$conn) throw new Failure(409, 'not_connected');
    $token = decrypt((string)$conn['enc_token'], config()['token_key']);

    if ($action === 'repos') {
        // Every page the connection can read (owner, collaborator and org member), so client-side
        // search covers the whole list — not just the first page.
        [$st, $json] = github_get_all($token, '/user/repos?sort=pushed&affiliation=owner,collaborator,organization_member');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        $repos = [];
        $seen = [];
        foreach ($json as $r) {
            $full = (string)($r['full_name'] ?? '');
            if ($full === '' || isset($seen[$full])) continue; // de-dupe across pages
            $seen[$full] = true;
            $repos[] = [
                'fullName'      => $full,
                'private'       => (bool)($r['private'] ?? false),
                'defaultBranch' => (string)($r['default_branch'] ?? ''),
                'pushedAt'      => (string)($r['pushed_at'] ?? ''),
                'htmlUrl'       => (string)($r['html_url'] ?? ''),
            ];
        }
        return ['repos' => $repos];
    }
    if ($action === 'runs') {
        $repo = repo_param($req);
        [$st, $json] = github_get($token, "/repos/$repo/actions/runs?per_page=30");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        $runs = [];
        foreach (($json['workflow_runs'] ?? []) as $r) $runs[] = [
            'id'         => (int)($r['id'] ?? 0),
            'name'       => (string)($r['name'] ?? ($r['display_title'] ?? '')),
            'status'     => (string)($r['status'] ?? ''),
            'conclusion' => (string)($r['conclusion'] ?? ''),
            'branch'     => (string)($r['head_branch'] ?? ''),
            'createdAt'  => (string)($r['created_at'] ?? ''),
            'htmlUrl'    => (string)($r['html_url'] ?? ''),
        ];
        return ['runs' => $runs];
    }
    if ($action === 'logs') {
        // MVP: the jobs of a run with their steps/conclusions (JSON). Full raw log archive is a follow-up.
        $repo = repo_param($req);
        $run = (int)($req['run'] ?? 0);
        if ($run <= 0) throw new Failure(400, 'bad_request');
        [$st, $json] = github_get($token, "/repos/$repo/actions/runs/$run/jobs");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        $jobs = [];
        foreach (($json['jobs'] ?? []) as $j) {
            $steps = [];
            foreach (($j['steps'] ?? []) as $s) $steps[] = [
                'name'       => (string)($s['name'] ?? ''),
                'status'     => (string)($s['status'] ?? ''),
                'conclusion' => (string)($s['conclusion'] ?? ''),
            ];
            $jobs[] = [
                'name'       => (string)($j['name'] ?? ''),
                'status'     => (string)($j['status'] ?? ''),
                'conclusion' => (string)($j['conclusion'] ?? ''),
                'htmlUrl'    => (string)($j['html_url'] ?? ''),
                'steps'      => $steps,
            ];
        }
        return ['jobs' => $jobs];
    }

    throw new Failure(400, 'bad_request');
}

/**
 * Handle the GitHub OAuth callback (GET, no Firebase header). Authenticated by the single-use state;
 * entitlement is re-checked by email before the connection is saved. Returns a redirect target URL.
 * $premiumByEmail is a closure(email)->bool using the existing WHMCS entitlement.
 */
function handle_callback(\PDO $db, array $query, callable $premiumByEmail, int $now): void
{
    $code = (string)($query['code'] ?? '');
    $state = (string)($query['state'] ?? '');
    if ($code === '') throw new Failure(400, 'bad_state');
    [$uid, $email] = state_consume($db, $state, $now);
    if (!$premiumByEmail($email)) throw new Failure(403, 'premium_required');
    $config = config();
    $token = exchange_code($config, $code);
    save_connection($db, $uid, $email, $token, $config['token_key'], $now);
    // No redirect to a separate page (which would need a rewrite + an extra file); the endpoint prints
    // a success page itself. The token never leaves the server and is never put in the URL.
}
