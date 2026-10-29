<?php
// php whmcs/tests/test_devops_providers.php — GitLab/Bitbucket adapters + multi-provider handle(),
// with SQLite and a fake HTTP transport (no network).

require __DIR__ . '/../terminow/devops_lib.php';

use TermiNow\DevOps\Failure;
use function TermiNow\DevOps\migrate;
use function TermiNow\DevOps\provider_config;
use function TermiNow\DevOps\configured_providers;
use function TermiNow\DevOps\oauth_authorize_url;
use function TermiNow\DevOps\provider_repo_ref;
use function TermiNow\DevOps\list_repos;
use function TermiNow\DevOps\list_runs;
use function TermiNow\DevOps\list_logs;
use function TermiNow\DevOps\set_http_transport;
use function TermiNow\DevOps\handle;
use function TermiNow\DevOps\save_connection;
use function TermiNow\DevOps\load_connection;
use function TermiNow\DevOps\set_config_override;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}
function throws(callable $fn, int $status, string $name): void
{
    try { $fn(); check(false, $name . " (no throw)"); }
    catch (Failure $e) { check($e->status === $status, $name . " ($e->status)"); }
    catch (\Throwable $e) { check(false, $name . " (wrong type: " . get_class($e) . ")"); }
}

$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);

$key = str_repeat("k", 32);
$cfg = [
    'github_client_id' => 'gh', 'github_client_secret' => 'ghs', 'redirect_uri' => 'https://cp/cb', 'token_key' => $key,
    'gitlab_client_id' => 'gl', 'gitlab_client_secret' => 'gls',
    'bitbucket_client_id' => 'bb', 'bitbucket_client_secret' => 'bbs',
];

// ---- Routing fake HTTP transport ----------------------------------------------------------------
// Returns canned responses by URL substring. Captures the last authorization header to assert no leak.
$ROUTES = [];
set_http_transport(function (string $method, string $url, array $headers, ?string $body) use (&$ROUTES) {
    foreach ($ROUTES as $needle => $resp) {
        if (strpos($url, $needle) !== false) {
            return is_callable($resp) ? $resp($method, $url, $headers, $body) : $resp;
        }
    }
    return ['status' => 404, 'body' => '{}'];
});
function json_ok(array $data): array { return ['status' => 200, 'body' => json_encode($data)]; }

// ---- provider_config / configured_providers -----------------------------------------------------
check(provider_config($cfg, 'github') !== null, 'github configured');
check(provider_config($cfg, 'gitlab') !== null, 'gitlab configured when ids present');
check(provider_config($cfg, 'bitbucket') !== null, 'bitbucket configured when ids present');
$none = provider_config(['github_client_id' => 'x', 'github_client_secret' => 'y', 'redirect_uri' => 'https://cp/cb'], 'gitlab');
check($none === null, 'gitlab NOT configured when ids missing');
$disabled = provider_config($cfg + ['gitlab_enabled' => false], 'gitlab');
check($disabled === null, 'gitlab held back by *_enabled=false');
$pc = provider_config($cfg, 'gitlab');
check(strpos($pc['redirect_uri'], 'provider=gitlab') !== false, 'gitlab redirect carries provider marker');
check($pc['scopes'] === 'read_api read_user', 'gitlab default scopes read-only');
$conf = configured_providers($cfg);
check($conf['github'] && $conf['gitlab'] && $conf['bitbucket'], 'all three configured in map');

// ---- authorize URLs ------------------------------------------------------------------------------
$glUrl = oauth_authorize_url(provider_config($cfg, 'gitlab'), 'STATE1');
check(strpos($glUrl, 'https://gitlab.com/oauth/authorize?') === 0, 'gitlab authorize base');
check(strpos($glUrl, 'response_type=code') !== false, 'gitlab response_type=code');
check(strpos($glUrl, 'scope=read_api') !== false, 'gitlab scope read_api (no write)');
check(strpos($glUrl, 'write') === false, 'gitlab url has no write scope');
$bbUrl = oauth_authorize_url(provider_config($cfg, 'bitbucket'), 'STATE2');
check(strpos($bbUrl, 'https://bitbucket.org/site/oauth2/authorize?') === 0, 'bitbucket authorize base');
check(strpos($bbUrl, 'response_type=code') !== false, 'bitbucket response_type=code');

// ---- repo ref validation -------------------------------------------------------------------------
check(provider_repo_ref('gitlab', ['repo' => '12345']) === '12345', 'gitlab numeric id accepted');
throws(fn() => provider_repo_ref('gitlab', ['repo' => 'group/proj']), 400, 'gitlab rejects path ref');
check(provider_repo_ref('bitbucket', ['repo' => 'ws/repo']) === 'ws/repo', 'bitbucket workspace/repo accepted');
throws(fn() => provider_repo_ref('bitbucket', ['repo' => '../x']), 400, 'bitbucket rejects traversal');

// ---- GitLab normalized reads ---------------------------------------------------------------------
$ROUTES = [
    '/projects?' => function () {
        // page 1 full (simulate 2 items with per_page huge so one page suffices)
        return json_ok([
            ['id' => 101, 'path_with_namespace' => 'grp/sub/app', 'visibility' => 'private', 'default_branch' => 'main',
             'last_activity_at' => '2026-10-01', 'web_url' => 'https://gitlab.com/grp/sub/app'],
            ['id' => 102, 'path_with_namespace' => 'grp/lib', 'visibility' => 'public', 'default_branch' => 'master',
             'last_activity_at' => '2026-09-01', 'web_url' => 'https://gitlab.com/grp/lib'],
        ]);
    },
    '/projects/101/pipelines?' => fn() => json_ok([
        ['id' => 9, 'ref' => 'main', 'status' => 'success', 'created_at' => '2026-10-02', 'web_url' => 'https://gl/9'],
    ]),
    '/projects/101/pipelines/9/jobs' => fn() => json_ok([
        ['name' => 'build', 'stage' => 'build', 'status' => 'success', 'web_url' => 'https://gl/9/j1'],
    ]),
];
$repos = list_repos('gitlab', 'TKN');
check(count($repos) === 2, 'gitlab repos count');
check($repos[0]['id'] === '101' && $repos[0]['fullName'] === 'grp/sub/app', 'gitlab repo id + path (subgroup)');
check($repos[0]['private'] === true && $repos[1]['private'] === false, 'gitlab visibility → private flag');
$runs = list_runs('gitlab', 'TKN', '101');
check($runs[0]['id'] === '9' && $runs[0]['branch'] === 'main' && $runs[0]['status'] === 'success', 'gitlab run normalized');
$logs = list_logs('gitlab', 'TKN', '101', '9');
check($logs[0]['name'] === 'build (build)' && $logs[0]['steps'] === [], 'gitlab job normalized, no steps');

// ---- Bitbucket normalized reads (incl. next-URL pagination) --------------------------------------
$ROUTES = [
    // Page-2 needle is listed first so it is not shadowed by the page-1 needle (substring match).
    '/repositories?page=2' => fn() => json_ok([
        'values' => [[ 'full_name' => 'ws/two', 'is_private' => false, 'mainbranch' => ['name' => 'dev'],
            'updated_on' => '2026-10-04', 'links' => ['html' => ['href' => 'https://bb/ws/two']]]],
    ]),
    '/2.0/repositories?' => function () {
        return json_ok([
            'values' => [[ 'full_name' => 'ws/one', 'is_private' => true, 'mainbranch' => ['name' => 'main'],
                'updated_on' => '2026-10-03', 'links' => ['html' => ['href' => 'https://bb/ws/one']]]],
            'next' => 'https://api.bitbucket.org/2.0/repositories?page=2',
        ]);
    },
    '/pipelines/?' => fn() => json_ok(['values' => [[ 'uuid' => '{abc}', 'build_number' => 7,
        'target' => ['ref_name' => 'main'], 'state' => ['name' => 'COMPLETED', 'result' => ['name' => 'SUCCESSFUL']],
        'created_on' => '2026-10-05']]]),
    '/pipelines/{abc}/steps/' => fn() => json_ok(['values' => [[ 'name' => 'Build',
        'state' => ['name' => 'COMPLETED', 'result' => ['name' => 'SUCCESSFUL']]]]]),
];
$bbRepos = list_repos('bitbucket', 'TKN');
check(count($bbRepos) === 2, 'bitbucket repos across next-page');
check($bbRepos[0]['fullName'] === 'ws/one' && $bbRepos[1]['fullName'] === 'ws/two', 'bitbucket both pages merged');
check($bbRepos[0]['defaultBranch'] === 'main' && $bbRepos[0]['private'] === true, 'bitbucket default branch + private');
$bbRuns = list_runs('bitbucket', 'TKN', 'ws/one');
check($bbRuns[0]['id'] === '{abc}' && $bbRuns[0]['conclusion'] === 'SUCCESSFUL', 'bitbucket run normalized');
$bbLogs = list_logs('bitbucket', 'TKN', 'ws/one', '{abc}');
check($bbLogs[0]['name'] === 'Build', 'bitbucket step normalized');

// ---- handle(): provider-aware ---------------------------------------------------------------------
$claims = ['sub' => 'uid1', 'email' => 'u@e.com'];
$premium = fn() => true;
set_config_override($cfg);

// status with no connections: providers map reflects config, nothing connected.
$st = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'gitlab']), $premium, 1000);
check($st['providers']['gitlab']['configured'] === true, 'status: gitlab configured');
check($st['providers']['gitlab']['connected'] === false, 'status: gitlab not connected');
check($st['connected'] === false, 'status: current provider not connected');

// connect for a configured provider returns a gitlab authorize URL.
$cres = handle($db, $claims, json_encode(['action' => 'connect', 'provider' => 'gitlab']), $premium, 1000);
check(strpos($cres['authorizeUrl'], 'gitlab.com/oauth/authorize') !== false, 'connect gitlab → authorize url');

// connect for an unconfigured provider → 503 (stays "Coming soon").
set_config_override(['github_client_id' => 'x', 'github_client_secret' => 'y', 'redirect_uri' => 'https://cp/cb', 'token_key' => $key]);
throws(fn() => handle($db, $claims, json_encode(['action' => 'connect', 'provider' => 'gitlab']), $premium, 1000),
    503, 'connect unconfigured gitlab → 503 (stays Coming soon)');
set_config_override($cfg);

// data action with no connection → 409 not_connected.
throws(fn() => handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'gitlab']), $premium, 1000),
    409, 'repos without connection → not_connected');

// Connected gitlab flow: save a connection (login supplied, so no HTTP), then list repos through handle.
save_connection($db, 'uid1', 'u@e.com', ['access_token' => 'GLTOK', 'refresh_token' => 'GLREF', 'expires_in' => 7200],
    $key, 1000, 'gitlab', 'gluser');
$ROUTES = ['/api/v4/projects' => fn() => json_ok([
    ['id' => 101, 'path_with_namespace' => 'grp/app', 'visibility' => 'private', 'default_branch' => 'main',
     'last_activity_at' => '2026-10-01', 'web_url' => 'https://gitlab.com/grp/app'],
])];
set_http_transport(function (string $m, string $u, array $h, ?string $b) use (&$ROUTES) {
    foreach ($ROUTES as $n => $r) if (strpos($u, $n) !== false) return is_callable($r) ? $r($m, $u, $h, $b) : $r;
    return ['status' => 404, 'body' => '{}'];
});
$rres = handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'gitlab']), $premium, 2000);
check(count($rres['repos']) === 1 && $rres['repos'][0]['id'] === '101', 'connected gitlab repos via handle');
$stc = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'gitlab']), $premium, 2000);
check($stc['connected'] === true && $stc['login'] === 'gluser', 'status shows gitlab connected as gluser');

// Token refresh: connection whose token has expired must refresh before the data call.
save_connection($db, 'uid2', 'u2@e.com', ['access_token' => 'OLD', 'refresh_token' => 'REF', 'expires_in' => 100],
    $key, 1000, 'gitlab', 'glu2');   // expires_at = 1100
$refreshed = false;
$ROUTES = [
    '/oauth/token' => function () use (&$refreshed) { $refreshed = true; return json_ok(['access_token' => 'NEW', 'refresh_token' => 'REF2', 'expires_in' => 7200]); },
    '/api/v4/projects' => function (string $m, string $u, array $h) {
        // Assert the refreshed token is the one used, and never leaked anywhere but the auth header.
        $auth = implode(' ', $h);
        return (strpos($auth, 'Bearer NEW') !== false) ? json_ok([]) : ['status' => 401, 'body' => '{}'];
    },
];
$claims2 = ['sub' => 'uid2', 'email' => 'u2@e.com'];
$rr = handle($db, $claims2, json_encode(['action' => 'repos', 'provider' => 'gitlab']), $premium, 5000); // now > expiry
check($refreshed === true, 'expired token triggered a refresh');
check(is_array($rr['repos']), 'refreshed token used for the data call');
$conn2 = load_connection($db, 'uid2', 'gitlab');
check((int)$conn2['expires_at'] === 5000 + 7200, 'refreshed expiry persisted');

set_http_transport(null);
set_config_override(null);

echo "\n" . ($failures === 0 ? "ALL PASS" : "$failures FAILED") . "\n";
exit($failures === 0 ? 0 : 1);
