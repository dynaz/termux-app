<?php
/**
 * TermiNow DevOps — provider adapters (GitHub, GitLab.com, Bitbucket Cloud).
 *
 * Each provider is reached only server-side; the app never sees a client secret or an access token.
 * This file holds, per provider: the OAuth endpoints, the least-privilege read-only scopes, and
 * normalizers that map the provider's repositories / pipelines / logs onto ONE shape the app already
 * understands (fullName, private, defaultBranch, …). Where a provider does not have a concept GitHub
 * has (e.g. GitLab pipelines have no per-step list), the normalizer returns what is really there rather
 * than inventing it.
 *
 * SCOPES (honest, least-privilege; read-only for repos/pipelines/logs):
 *   - github:    read:user            (public repos + profile; see devops_lib.php for the private note)
 *   - gitlab:    read_api read_user   (read-only across the API: projects, pipelines, jobs, identity)
 *   - bitbucket: account repository pipeline   (all granted as READ in the consumer; Bitbucket scopes
 *                are configured on the OAuth consumer, the authorize URL does not request write)
 *
 * HTTP is done through {@see http_request()}, which tests override with {@see set_http_transport()} so
 * the adapters can be exercised without any network.
 */

namespace TermiNow\DevOps;

const PROVIDERS = ['github', 'gitlab', 'bitbucket'];

/** Static, non-secret definition per provider. Config (ids/secrets/redirect) comes from devops_config.php. */
function provider_def(string $provider): array
{
    switch ($provider) {
        case 'github':
            return [
                'authorize'  => 'https://github.com/login/oauth/authorize',
                'token'      => 'https://github.com/login/oauth/access_token',
                'api'        => 'https://api.github.com',
                'scopes'     => 'read:user',
                'token_expires' => false,   // OAuth App tokens do not expire by default
            ];
        case 'gitlab':
            return [
                'authorize'  => 'https://gitlab.com/oauth/authorize',
                'token'      => 'https://gitlab.com/oauth/token',
                'api'        => 'https://gitlab.com/api/v4',
                'scopes'     => 'read_api read_user',
                'token_expires' => true,
            ];
        case 'bitbucket':
            return [
                'authorize'  => 'https://bitbucket.org/site/oauth2/authorize',
                'token'      => 'https://bitbucket.org/site/oauth2/access_token',
                'api'        => 'https://api.bitbucket.org/2.0',
                'scopes'     => 'account repository pipeline',
                'token_expires' => true,
            ];
        default:
            throw new Failure(400, 'bad_provider');
    }
}

function is_provider(string $provider): bool
{
    return in_array($provider, PROVIDERS, true);
}

/**
 * Resolve a provider's OAuth config from devops_config.php. Keys are `<provider>_client_id`,
 * `<provider>_client_secret`, and optional `<provider>_redirect_uri` / `<provider>_scopes`. GitHub also
 * accepts the original `github_*` keys and the top-level `redirect_uri` (backward compatible). Returns
 * null when the provider is not configured — that is how a provider stays "Coming soon" in the app until
 * its OAuth app exists. A provider can be held back explicitly with `<provider>_enabled => false`.
 */
function provider_config(array $config, string $provider): ?array
{
    if (!is_provider($provider)) return null;
    $id     = (string)($config[$provider . '_client_id'] ?? '');
    $secret = (string)($config[$provider . '_client_secret'] ?? '');
    if ($id === '' || $secret === '') return null;
    if (array_key_exists($provider . '_enabled', $config) && !$config[$provider . '_enabled']) return null;
    $def = provider_def($provider);
    $redirect = (string)($config[$provider . '_redirect_uri'] ?? $config['redirect_uri'] ?? '');
    if ($redirect === '') return null;
    // Each provider gets its own callback marker so one redirect file can serve all providers.
    if ($provider !== 'github' && strpos($redirect, 'provider=') === false)
        $redirect .= (strpos($redirect, '?') === false ? '?' : '&') . 'provider=' . $provider;
    return [
        'provider'      => $provider,
        'client_id'     => $id,
        'client_secret' => $secret,
        'redirect_uri'  => $redirect,
        'scopes'        => (string)($config[$provider . '_scopes'] ?? $def['scopes']),
        'token_expires' => (bool)$def['token_expires'],
    ];
}

/** Which providers are configured and ready to connect (drives Connect vs. "Coming soon" in the app). */
function configured_providers(array $config): array
{
    $out = [];
    foreach (PROVIDERS as $p) $out[$p] = provider_config($config, $p) !== null;
    return $out;
}

// ---- HTTP (overridable for tests) ---------------------------------------------------------------

/** @var callable|null */
$GLOBALS['__terminow_devops_http'] = null;

/** Install a fake transport for tests: fn(method,url,headers[],body):array{status:int,body:string}. */
function set_http_transport(?callable $fn): void
{
    $GLOBALS['__terminow_devops_http'] = $fn;
}

/**
 * One HTTP request. Returns ['status'=>int, 'body'=>string]. Never logs the Authorization header.
 * $headers is a list of raw "Name: value" strings. $body is a string (already encoded) or null.
 */
function http_request(string $method, string $url, array $headers, ?string $body = null): array
{
    $fn = $GLOBALS['__terminow_devops_http'] ?? null;
    if ($fn !== null) return $fn($method, $url, $headers, $body);
    $curl = curl_init($url);
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => $headers,
    ]);
    if ($body !== null) curl_setopt($curl, CURLOPT_POSTFIELDS, $body);
    $resp = curl_exec($curl);
    $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    return ['status' => $status, 'body' => is_string($resp) ? $resp : ''];
}

/** GET JSON from a provider API with a bearer token. Returns [status, decoded|null]. */
function api_get(string $provider, string $token, string $path): array
{
    $def = provider_def($provider);
    $headers = ['Authorization: Bearer ' . $token, 'User-Agent: TermiNow-DevOps', 'Accept: application/json'];
    if ($provider === 'github') $headers[] = 'X-GitHub-Api-Version: 2022-11-28';
    $r = http_request('GET', $def['api'] . $path, $headers);
    $decoded = $r['body'] === '' ? null : json_decode($r['body'], true);
    return [$r['status'], $decoded];
}

/**
 * GET every page of a list endpoint. GitHub/GitLab use `?page=N` (stop on a short page); Bitbucket
 * returns a `values` array plus a `next` absolute URL (follow it). Capped so a huge account cannot make
 * the server loop forever. Returns [status, items[]]; on a page-1 error returns that error status.
 */
function api_get_all(string $provider, string $token, string $path, int $perPage = 100, int $maxPages = 10): array
{
    $def = provider_def($provider);
    $headers = ['Authorization: Bearer ' . $token, 'User-Agent: TermiNow-DevOps', 'Accept: application/json'];
    if ($provider === 'github') $headers[] = 'X-GitHub-Api-Version: 2022-11-28';

    if ($provider === 'bitbucket') {
        $items = [];
        $url = $def['api'] . $path . (strpos($path, '?') === false ? '?' : '&') . 'pagelen=' . $perPage;
        for ($i = 0; $i < $maxPages && $url !== ''; $i++) {
            $r = http_request('GET', $url, $headers);
            $json = $r['body'] === '' ? null : json_decode($r['body'], true);
            if ($r['status'] !== 200 || !is_array($json)) { if ($i === 0) return [$r['status'], null]; break; }
            foreach (($json['values'] ?? []) as $it) $items[] = $it;
            $url = (string)($json['next'] ?? '');
        }
        return [200, $items];
    }

    // GitHub / GitLab: page-number pagination.
    $sep = (strpos($path, '?') === false) ? '?' : '&';
    $items = [];
    for ($page = 1; $page <= $maxPages; $page++) {
        $r = http_request('GET', $def['api'] . $path . $sep . 'per_page=' . $perPage . '&page=' . $page, $headers);
        $json = $r['body'] === '' ? null : json_decode($r['body'], true);
        if ($r['status'] !== 200 || !is_array($json)) { if ($page === 1) return [$r['status'], null]; break; }
        if (count($json) === 0) break;
        foreach ($json as $it) $items[] = $it;
        if (count($json) < $perPage) break;
    }
    return [200, $items];
}

// ---- OAuth --------------------------------------------------------------------------------------

function oauth_authorize_url(array $pc, string $state): string
{
    $def = provider_def($pc['provider']);
    $params = [
        'client_id'     => $pc['client_id'],
        'redirect_uri'  => $pc['redirect_uri'],
        'response_type' => 'code',
        'state'         => $state,
    ];
    if ($pc['provider'] === 'github') {
        $params['scope'] = $pc['scopes'];
        $params['allow_signup'] = 'false';
    } elseif ($pc['provider'] === 'gitlab') {
        $params['scope'] = $pc['scopes'];
    } elseif ($pc['provider'] === 'bitbucket') {
        // Bitbucket takes its permissions from the consumer config, not the authorize URL.
    }
    return $def['authorize'] . '?' . http_build_query($params);
}

/** Exchange an authorization code for a token. Returns the decoded token array (access_token, …). */
function oauth_exchange(array $pc, string $code): array
{
    $def = provider_def($pc['provider']);
    $fields = [
        'grant_type'    => 'authorization_code',
        'client_id'     => $pc['client_id'],
        'client_secret' => $pc['client_secret'],
        'code'          => $code,
        'redirect_uri'  => $pc['redirect_uri'],
    ];
    $r = http_request('POST', $def['token'], ['Accept: application/json', 'Content-Type: application/x-www-form-urlencoded',
        'User-Agent: TermiNow-DevOps'], http_build_query($fields));
    $json = $r['body'] === '' ? null : json_decode($r['body'], true);
    if ($r['status'] !== 200 || !is_array($json) || empty($json['access_token'])) throw new Failure(502, 'provider_error');
    return $json;
}

/** Refresh an expiring token (GitLab/Bitbucket). Returns the new decoded token array. */
function oauth_refresh(array $pc, string $refreshToken): array
{
    $def = provider_def($pc['provider']);
    $r = http_request('POST', $def['token'], ['Accept: application/json', 'Content-Type: application/x-www-form-urlencoded',
        'User-Agent: TermiNow-DevOps'], http_build_query([
            'grant_type'    => 'refresh_token',
            'refresh_token' => $refreshToken,
            'client_id'     => $pc['client_id'],
            'client_secret' => $pc['client_secret'],
        ]));
    $json = $r['body'] === '' ? null : json_decode($r['body'], true);
    if ($r['status'] !== 200 || !is_array($json) || empty($json['access_token'])) throw new Failure(401, 'refresh_failed');
    return $json;
}

/** Best-effort token revocation. GitHub deletes the grant; GitLab/Bitbucket revoke the token. */
function oauth_revoke(array $pc, string $token): void
{
    $provider = $pc['provider'];
    try {
        if ($provider === 'github') {
            http_request('DELETE', 'https://api.github.com/applications/' . rawurlencode($pc['client_id']) . '/grant',
                ['Accept: application/vnd.github+json', 'User-Agent: TermiNow-DevOps',
                 'Authorization: Basic ' . base64_encode($pc['client_id'] . ':' . $pc['client_secret'])],
                json_encode(['access_token' => $token]));
        } elseif ($provider === 'gitlab') {
            http_request('POST', 'https://gitlab.com/oauth/revoke', ['Content-Type: application/x-www-form-urlencoded',
                'User-Agent: TermiNow-DevOps'], http_build_query([
                    'client_id' => $pc['client_id'], 'client_secret' => $pc['client_secret'], 'token' => $token]));
        } elseif ($provider === 'bitbucket') {
            // Bitbucket has no public token-revoke endpoint for OAuth2; disconnect just deletes our copy.
        }
    } catch (\Throwable $e) {
        // best effort
    }
}

/** The account login/handle for a token, for display ("@name"). Empty on failure (not fatal). */
function provider_login(string $provider, string $token): string
{
    if ($provider === 'github')    { [$s, $j] = api_get($provider, $token, '/user'); return $s === 200 && is_array($j) ? (string)($j['login'] ?? '') : ''; }
    if ($provider === 'gitlab')    { [$s, $j] = api_get($provider, $token, '/user'); return $s === 200 && is_array($j) ? (string)($j['username'] ?? '') : ''; }
    if ($provider === 'bitbucket') { [$s, $j] = api_get($provider, $token, '/user'); return $s === 200 && is_array($j) ? (string)($j['username'] ?? ($j['nickname'] ?? '')) : ''; }
    return '';
}

// ---- Repo reference validation (prevents path injection) ----------------------------------------

/**
 * Validate and return the repository reference for a provider, as it is used in the API path:
 *   - github / bitbucket: "owner/name" (or "workspace/repo")
 *   - gitlab:             a numeric project id
 * This is the "provider + repository id" reference, so ids never collide across providers.
 */
function provider_repo_ref(string $provider, array $req): string
{
    $repo = (string)($req['repo'] ?? '');
    if ($provider === 'gitlab') {
        if (!preg_match('/^[0-9]+$/', $repo)) throw new Failure(400, 'bad_request');
        return $repo;
    }
    if (!preg_match('~^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$~', $repo) || strpos($repo, '..') !== false)
        throw new Failure(400, 'bad_request');
    return $repo;
}

// ---- Normalized reads -----------------------------------------------------------------------------

/** Repositories the connection can read, across all pages, normalized. */
function list_repos(string $provider, string $token): array
{
    if ($provider === 'github') {
        [$st, $json] = api_get_all($provider, $token, '/user/repos?sort=pushed&affiliation=owner,collaborator,organization_member');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return dedupe_repos(array_map(fn($r) => [
            'provider'      => 'github',
            'id'            => (string)($r['full_name'] ?? ''),
            'fullName'      => (string)($r['full_name'] ?? ''),
            'private'       => (bool)($r['private'] ?? false),
            'defaultBranch' => (string)($r['default_branch'] ?? ''),
            'pushedAt'      => (string)($r['pushed_at'] ?? ''),
            'htmlUrl'       => (string)($r['html_url'] ?? ''),
        ], $json));
    }
    if ($provider === 'gitlab') {
        [$st, $json] = api_get_all($provider, $token, '/projects?membership=true&simple=true&order_by=last_activity_at');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return dedupe_repos(array_map(fn($r) => [
            'provider'      => 'gitlab',
            'id'            => (string)($r['id'] ?? ''),                      // numeric project id
            'fullName'      => (string)($r['path_with_namespace'] ?? ''),    // group/subgroup/project
            'private'       => ((string)($r['visibility'] ?? 'private')) !== 'public',
            'defaultBranch' => (string)($r['default_branch'] ?? ''),
            'pushedAt'      => (string)($r['last_activity_at'] ?? ''),
            'htmlUrl'       => (string)($r['web_url'] ?? ''),
        ], $json));
    }
    if ($provider === 'bitbucket') {
        [$st, $json] = api_get_all($provider, $token, '/repositories?role=member&sort=-updated_on');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return dedupe_repos(array_map(fn($r) => [
            'provider'      => 'bitbucket',
            'id'            => (string)($r['full_name'] ?? ''),              // workspace/repo
            'fullName'      => (string)($r['full_name'] ?? ''),
            'private'       => (bool)($r['is_private'] ?? true),
            'defaultBranch' => (string)(($r['mainbranch']['name'] ?? '')),
            'pushedAt'      => (string)($r['updated_on'] ?? ''),
            'htmlUrl'       => (string)(($r['links']['html']['href'] ?? '')),
        ], $json));
    }
    throw new Failure(400, 'bad_provider');
}

function dedupe_repos(array $repos): array
{
    $seen = [];
    $out = [];
    foreach ($repos as $r) {
        $key = $r['id'] !== '' ? $r['id'] : $r['fullName'];
        if ($key === '' || isset($seen[$key])) continue;
        $seen[$key] = true;
        $out[] = $r;
    }
    return $out;
}

/** Pipeline runs for a repo, normalized. */
function list_runs(string $provider, string $token, string $repo): array
{
    if ($provider === 'github') {
        [$st, $json] = api_get($provider, $token, "/repos/$repo/actions/runs?per_page=30");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($r) => [
            'id'         => (string)($r['id'] ?? ''),
            'name'       => (string)($r['name'] ?? ($r['display_title'] ?? '')),
            'status'     => (string)($r['status'] ?? ''),
            'conclusion' => (string)($r['conclusion'] ?? ''),
            'branch'     => (string)($r['head_branch'] ?? ''),
            'createdAt'  => (string)($r['created_at'] ?? ''),
            'htmlUrl'    => (string)($r['html_url'] ?? ''),
        ], $json['workflow_runs'] ?? []);
    }
    if ($provider === 'gitlab') {
        [$st, $json] = api_get($provider, $token, "/projects/$repo/pipelines?per_page=30");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($r) => [
            'id'         => (string)($r['id'] ?? ''),
            'name'       => (string)($r['ref'] ?? ''),
            'status'     => (string)($r['status'] ?? ''),
            'conclusion' => (string)($r['status'] ?? ''),   // GitLab has one state field
            'branch'     => (string)($r['ref'] ?? ''),
            'createdAt'  => (string)($r['created_at'] ?? ''),
            'htmlUrl'    => (string)($r['web_url'] ?? ''),
        ], is_array($json) ? $json : []);
    }
    if ($provider === 'bitbucket') {
        [$st, $json] = api_get($provider, $token, "/repositories/$repo/pipelines/?sort=-created_on&pagelen=30");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($r) => [
            'id'         => (string)($r['uuid'] ?? ($r['build_number'] ?? '')),
            'name'       => (string)(($r['target']['ref_name'] ?? '') ?: ('#' . ($r['build_number'] ?? ''))),
            'status'     => (string)(($r['state']['name'] ?? '')),
            'conclusion' => (string)(($r['state']['result']['name'] ?? ($r['state']['stage']['name'] ?? ''))),
            'branch'     => (string)(($r['target']['ref_name'] ?? '')),
            'createdAt'  => (string)($r['created_on'] ?? ''),
            'htmlUrl'    => '',
        ], $json['values'] ?? []);
    }
    throw new Failure(400, 'bad_provider');
}

/** Jobs/steps (logs view) for one run, normalized to jobs[] each with steps[]. */
function list_logs(string $provider, string $token, string $repo, string $run): array
{
    if ($provider === 'github') {
        [$st, $json] = api_get($provider, $token, "/repos/$repo/actions/runs/$run/jobs");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($j) => [
            'name'       => (string)($j['name'] ?? ''),
            'status'     => (string)($j['status'] ?? ''),
            'conclusion' => (string)($j['conclusion'] ?? ''),
            'htmlUrl'    => (string)($j['html_url'] ?? ''),
            'steps'      => array_map(fn($s) => [
                'name' => (string)($s['name'] ?? ''), 'status' => (string)($s['status'] ?? ''),
                'conclusion' => (string)($s['conclusion'] ?? ''),
            ], $j['steps'] ?? []),
        ], $json['jobs'] ?? []);
    }
    if ($provider === 'gitlab') {
        // GitLab jobs are flat (no per-step list): one normalized job per CI job, steps empty.
        [$st, $json] = api_get($provider, $token, "/projects/$repo/pipelines/$run/jobs");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($j) => [
            'name'       => (string)(($j['name'] ?? '') . (isset($j['stage']) ? ' (' . $j['stage'] . ')' : '')),
            'status'     => (string)($j['status'] ?? ''),
            'conclusion' => (string)($j['status'] ?? ''),
            'htmlUrl'    => (string)($j['web_url'] ?? ''),
            'steps'      => [],
        ], is_array($json) ? $json : []);
    }
    if ($provider === 'bitbucket') {
        [$st, $json] = api_get($provider, $token, "/repositories/$repo/pipelines/$run/steps/");
        if ($st === 404 || $st === 403) throw new Failure(403, 'no_access');
        if ($st !== 200 || !is_array($json)) throw new Failure(502, 'provider_error');
        return array_map(fn($s) => [
            'name'       => (string)($s['name'] ?? 'step'),
            'status'     => (string)(($s['state']['name'] ?? '')),
            'conclusion' => (string)(($s['state']['result']['name'] ?? '')),
            'htmlUrl'    => '',
            'steps'      => [],
        ], $json['values'] ?? []);
    }
    throw new Failure(400, 'bad_provider');
}
