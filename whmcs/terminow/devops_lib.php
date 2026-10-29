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

// Provider adapters (GitHub / GitLab / Bitbucket). GitHub keeps the original code paths below; the
// other providers go through the registry in this file.
require_once __DIR__ . '/devops_providers.php';

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
/** Tests inject a config here so handle()/handle_callback can run without the real devops_config.php. */
function set_config_override(?array $config): void
{
    $GLOBALS['__terminow_devops_config'] = $config;
}

function config(): array
{
    if (isset($GLOBALS['__terminow_devops_config']) && is_array($GLOBALS['__terminow_devops_config'])) {
        $c = $GLOBALS['__terminow_devops_config'];
        if (!empty($c['token_key_b64']) && empty($c['token_key'])) $c['token_key'] = base64_decode((string)$c['token_key_b64'], true);
        return $c;
    }
    if (!is_file(CONFIG_FILE)) throw new Failure(503, 'DevOps not configured');
    $config = require CONFIG_FILE;
    if (!is_array($config)) throw new Failure(503, 'DevOps not configured');
    foreach (['github_client_id', 'github_client_secret', 'redirect_uri'] as $key)
        if (empty($config[$key]) || !is_string($config[$key])) throw new Failure(503, 'DevOps not configured');
    if (!empty($config['token_key_b64'])) $config['token_key'] = base64_decode((string)$config['token_key_b64'], true);
    if (empty($config['token_key']) || strlen((string)$config['token_key']) !== 32) throw new Failure(503, 'DevOps not configured');
    // Optional, separate file for the extra providers' OAuth apps (GitLab/Bitbucket), so adding them
    // never touches the working GitHub secret file. Its keys (gitlab_client_id, …) merge in if present.
    $extra = __DIR__ . '/devops_config.providers.php';
    if (is_file($extra)) {
        $more = require $extra;
        if (is_array($more)) foreach ($more as $k => $v) $config[$k] = $v;
    }
    return $config;
}

/**
 * Create the tables if they do not exist, and add the multi-provider columns on an older table.
 * Works with MySQL and SQLite. A fresh connection table is keyed by (uid, provider) so one user can
 * connect several providers. On an existing GitHub-only table (keyed by uid) the CREATE is a no-op and
 * the key is migrated separately when the extra providers go live (see docs/devops/DEPLOYMENT.md);
 * the queries here work on both shapes because they always filter by uid AND provider.
 */
function migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_devops_conn (
        uid VARCHAR(128) NOT NULL,
        provider VARCHAR(16) NOT NULL,
        email VARCHAR(191) NOT NULL,
        enc_token TEXT NOT NULL,
        enc_refresh TEXT NULL,
        scopes VARCHAR(255) NOT NULL,
        login VARCHAR(100) NOT NULL,
        status VARCHAR(16) NOT NULL,
        expires_at INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (uid, provider))');
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_devops_state (
        state VARCHAR(64) NOT NULL PRIMARY KEY,
        uid VARCHAR(128) NOT NULL,
        email VARCHAR(191) NOT NULL,
        provider VARCHAR(16) NOT NULL DEFAULT \'github\',
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used INTEGER NOT NULL DEFAULT 0)');
    // Idempotent column adds for a table created by an earlier version (ignored if already present).
    add_column_if_missing($db, 'mod_terminow_devops_conn', 'expires_at', "INTEGER NOT NULL DEFAULT 0");
    add_column_if_missing($db, 'mod_terminow_devops_state', 'provider', "VARCHAR(16) NOT NULL DEFAULT 'github'");
}

/** Add a column if the table does not already have it (MySQL + SQLite); never throws on "exists". */
function add_column_if_missing(\PDO $db, string $table, string $column, string $decl): void
{
    try {
        $has = false;
        foreach ($db->query("PRAGMA table_info($table)") ?: [] as $col)
            if (($col['name'] ?? '') === $column) { $has = true; break; }
        // PRAGMA returns nothing on MySQL; fall back to information_schema there.
        if (!$has) {
            try { $db->exec("ALTER TABLE $table ADD COLUMN $column $decl"); }
            catch (\Throwable $e) { /* already exists or unsupported — safe to ignore */ }
        }
    } catch (\Throwable $e) {
        try { $db->exec("ALTER TABLE $table ADD COLUMN $column $decl"); } catch (\Throwable $e2) {}
    }
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

function state_create(\PDO $db, string $uid, string $email, int $now, string $provider = 'github'): string
{
    $state = bin2hex(random_bytes(32));
    $stmt = $db->prepare('INSERT INTO mod_terminow_devops_state (state, uid, email, provider, created_at, expires_at, used)
        VALUES (?, ?, ?, ?, ?, ?, 0)');
    $stmt->execute([$state, $uid, $email, $provider, $now, $now + STATE_TTL]);
    return $state;
}

/** Consume a state exactly once; returns [uid, email, provider] or throws. Also sweeps expired rows. */
function state_consume(\PDO $db, string $state, int $now): array
{
    if (!preg_match('/^[0-9a-f]{64}$/', $state)) throw new Failure(400, 'bad_state');
    $db->prepare('DELETE FROM mod_terminow_devops_state WHERE expires_at < ?')->execute([$now]);
    $db->beginTransaction();
    try {
        $stmt = $db->prepare('SELECT uid, email, provider, expires_at FROM mod_terminow_devops_state WHERE state = ?');
        $stmt->execute([$state]);
        $row = $stmt->fetch(\PDO::FETCH_ASSOC);
        if (!$row || (int)$row['expires_at'] < $now) throw new Failure(400, 'bad_state');
        // The DELETE is the single-use gate: exactly one concurrent consumer can remove the row, so a
        // second (racing) callback with the same state deletes 0 rows and is rejected.
        $del = $db->prepare('DELETE FROM mod_terminow_devops_state WHERE state = ?');
        $del->execute([$state]);
        if ($del->rowCount() !== 1) throw new Failure(400, 'bad_state');
        $db->commit();
        return [(string)$row['uid'], (string)$row['email'], (string)($row['provider'] ?? 'github')];
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

function load_connection(\PDO $db, string $uid, string $provider = 'github'): ?array
{
    $stmt = $db->prepare('SELECT * FROM mod_terminow_devops_conn WHERE uid = ? AND provider = ?');
    $stmt->execute([$uid, $provider]);
    $row = $stmt->fetch(\PDO::FETCH_ASSOC);
    return $row ?: null;
}

/** Every provider connection for a user, as provider => row (for the home screen's status). */
function list_connections(\PDO $db, string $uid): array
{
    $stmt = $db->prepare('SELECT * FROM mod_terminow_devops_conn WHERE uid = ?');
    $stmt->execute([$uid]);
    $out = [];
    foreach ($stmt->fetchAll(\PDO::FETCH_ASSOC) as $row) $out[(string)$row['provider']] = $row;
    return $out;
}

/**
 * Save (or replace) a provider connection, computing the token expiry and resolving the account login.
 * The login is fetched from the provider when not supplied (on refresh we keep the known one).
 */
function save_connection(\PDO $db, string $uid, string $email, array $token, string $key, int $now,
                         string $provider = 'github', ?string $login = null): void
{
    $access = (string)$token['access_token'];
    $enc = encrypt($access, $key);
    $encRefresh = empty($token['refresh_token']) ? null : encrypt((string)$token['refresh_token'], $key);
    $expiresAt = !empty($token['expires_in']) ? $now + (int)$token['expires_in'] : 0;
    if ($login === null) $login = provider_login($provider, $access);
    $db->prepare('REPLACE INTO mod_terminow_devops_conn
        (uid, provider, email, enc_token, enc_refresh, scopes, login, status, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
        ->execute([$uid, $provider, $email, $enc, $encRefresh,
            (string)($token['scope'] ?? provider_def($provider)['scopes']), (string)$login, 'connected', $expiresAt, $now, $now]);
}

function delete_connection(\PDO $db, string $uid, string $provider = 'github'): void
{
    $db->prepare('DELETE FROM mod_terminow_devops_conn WHERE uid = ? AND provider = ?')->execute([$uid, $provider]);
}

/**
 * The access token to use now for (uid, provider): decrypts the stored token and, for a provider whose
 * tokens expire (GitLab/Bitbucket), refreshes it with the stored refresh token when it is within a
 * minute of expiry, persisting the new token. Throws not_connected when there is no usable token.
 */
function active_token(\PDO $db, string $uid, string $provider, array $config, int $now): string
{
    $conn = load_connection($db, $uid, $provider);
    if (!$conn) throw new Failure(409, 'not_connected');
    $access = decrypt((string)$conn['enc_token'], $config['token_key']);
    $expiresAt = (int)($conn['expires_at'] ?? 0);
    if (provider_def($provider)['token_expires'] && $expiresAt > 0 && $expiresAt <= $now + 60) {
        if (empty($conn['enc_refresh'])) throw new Failure(409, 'not_connected');
        $pc = provider_config($config, $provider);
        if (!$pc) throw new Failure(503, 'provider_not_configured');
        $new = oauth_refresh($pc, decrypt((string)$conn['enc_refresh'], $config['token_key']));
        // Keep the known login; a refresh response does not carry it.
        save_connection($db, $uid, (string)$conn['email'], $new, $config['token_key'], $now, $provider, (string)$conn['login']);
        return (string)$new['access_token'];
    }
    return $access;
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
    $provider = (string)($req['provider'] ?? 'github');
    if (!is_provider($provider)) throw new Failure(400, 'bad_provider');

    // Actions allowed regardless of entitlement, so an expired user can still remove a connection.
    if ($action === 'disconnect') {
        delete_connection($db, $uid, $provider);
        return ['status' => 'disconnected'];
    }
    if ($action === 'revoke') {
        $conn = load_connection($db, $uid, $provider);
        if ($conn) {
            try {
                $pc = provider_config(config(), $provider);
                if ($pc) oauth_revoke($pc, decrypt((string)$conn['enc_token'], config()['token_key']));
            } catch (\Throwable $e) { /* best effort; still delete locally */ }
        }
        delete_connection($db, $uid, $provider);
        return ['status' => 'revoked'];
    }

    // Everything else needs Premium now (sync is disabled when it has lapsed).
    if (!$isPremiumNow()) throw new Failure(403, 'premium_required');

    $config = config();

    if ($action === 'connect') {
        $pc = provider_config($config, $provider);
        if (!$pc) throw new Failure(503, 'provider_not_configured');
        $state = state_create($db, $uid, $email, $now, $provider);
        // GitHub keeps its original URL builder (unchanged behavior); others use the provider registry.
        $url = $provider === 'github' ? authorize_url($config, $state) : oauth_authorize_url($pc, $state);
        return ['authorizeUrl' => $url];
    }
    if ($action === 'status') {
        // Per-provider status for the home screen: configured (Connect vs "Coming soon") and connected.
        $conns = list_connections($db, $uid);
        $configured = configured_providers($config);
        $providers = [];
        foreach (PROVIDERS as $p) $providers[$p] = [
            'configured' => (bool)$configured[$p],
            'connected'  => isset($conns[$p]),
            'login'      => isset($conns[$p]) ? (string)$conns[$p]['login'] : '',
        ];
        $cur = $conns[$provider] ?? null;
        return [
            'connected' => (bool)$cur,
            'provider'  => $provider,
            'login'     => $cur ? (string)$cur['login'] : '',
            'providers' => $providers,
        ];
    }

    // Data actions need a stored connection; active_token refreshes an expiring GitLab/Bitbucket token.
    $token = active_token($db, $uid, $provider, $config, $now);

    if ($action === 'repos') {
        return ['repos' => list_repos($provider, $token)];
    }
    if ($action === 'runs') {
        return ['runs' => list_runs($provider, $token, provider_repo_ref($provider, $req))];
    }
    if ($action === 'logs') {
        $run = (string)($req['run'] ?? '');
        // Numeric run id (GitHub/GitLab) or a Bitbucket pipeline uuid like {….}.
        if ($run === '' || !preg_match('/^[A-Za-z0-9_.{}-]+$/', $run)) throw new Failure(400, 'bad_request');
        return ['jobs' => list_logs($provider, $token, provider_repo_ref($provider, $req), $run)];
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
    [$uid, $email, $stateProvider] = state_consume($db, $state, $now);
    if (!$premiumByEmail($email)) throw new Failure(403, 'premium_required');
    // The provider is taken from the state (authoritative), falling back to the query for older states.
    $provider = $stateProvider !== '' ? $stateProvider : (string)($query['provider'] ?? 'github');
    if (!is_provider($provider)) throw new Failure(400, 'bad_provider');
    $config = config();
    if ($provider === 'github') {
        $token = exchange_code($config, $code);                    // unchanged GitHub path
    } else {
        $pc = provider_config($config, $provider);
        if (!$pc) throw new Failure(503, 'provider_not_configured');
        $token = oauth_exchange($pc, $code);
    }
    save_connection($db, $uid, $email, $token, $config['token_key'], $now, $provider);
    // No redirect to a separate page (which would need a rewrite + an extra file); the endpoint prints
    // a success page itself. The token never leaves the server and is never put in the URL.
}
