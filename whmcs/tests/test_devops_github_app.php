<?php
// php whmcs/tests/test_devops_github_app.php — GitHub App (private repos) logic: JWT signing, installation
// token mint, secure uid<->installation binding, repo listing/merge, and source-based token selection.
// Uses a freshly generated RSA key + a fake HTTP transport (no network).

require __DIR__ . '/../terminow/devops_lib.php';

use TermiNow\DevOps\Failure;
use function TermiNow\DevOps\migrate;
use function TermiNow\DevOps\set_http_transport;
use function TermiNow\DevOps\set_config_override;
use function TermiNow\DevOps\github_app_config;
use function TermiNow\DevOps\github_app_jwt;
use function TermiNow\DevOps\github_app_install_url;
use function TermiNow\DevOps\github_app_installation_token;
use function TermiNow\DevOps\github_app_list_repos;
use function TermiNow\DevOps\github_app_bind;
use function TermiNow\DevOps\github_app_load;
use function TermiNow\DevOps\save_connection;
use function TermiNow\DevOps\handle;
use function TermiNow\DevOps\handle_callback;
use function TermiNow\DevOps\state_create;

$failures = 0;
function check(bool $ok, string $name): void { global $failures; echo ($ok ? "ok   " : "FAIL ") . $name . "\n"; if (!$ok) $failures++; }
function throws(callable $fn, int $status, string $name): void {
    try { $fn(); check(false, $name . " (no throw)"); }
    catch (Failure $e) { check($e->status === $status, $name . " ($e->status)"); }
    catch (\Throwable $e) { check(false, $name . " (wrong type: " . get_class($e) . ")"); }
}

// Generate a throwaway RSA keypair to act as the GitHub App private key.
$res = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
openssl_pkey_export($res, $privPem);
$pub = openssl_pkey_get_details($res)['key'];

$key = str_repeat('k', 32);
$cfg = [
    'github_client_id' => 'gh', 'github_client_secret' => 'ghs', 'redirect_uri' => 'https://cp/cb', 'token_key' => $key,
    'github_app_id' => '12345', 'github_app_private_key' => $privPem, 'github_app_slug' => 'terminow-devops',
];

$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);
set_config_override($cfg);

// ---- config + JWT + install url ----
$app = github_app_config($cfg);
check($app !== null, 'github_app configured when id+key+slug present');
check(github_app_config(['github_app_id' => '1', 'github_app_private_key' => 'x']) === null, 'not configured without slug');
$jwt = github_app_jwt($app, 1000);
$parts = explode('.', $jwt);
check(count($parts) === 3, 'JWT has 3 segments');
$sig = base64_decode(strtr($parts[2], '-_', '+/') . str_repeat('=', (4 - strlen($parts[2]) % 4) % 4));
check(openssl_verify($parts[0] . '.' . $parts[1], $sig, $pub, OPENSSL_ALGO_SHA256) === 1, 'JWT RS256 signature verifies with the public key');
check(strpos(github_app_install_url($app, 'STATE1'), 'github.com/apps/terminow-devops/installations/new?state=STATE1') !== false, 'install url format');

// ---- fake GitHub App API ----
$ROUTES = [];
set_http_transport(function ($m, $u, $h, $b) use (&$ROUTES) {
    foreach ($ROUTES as $needle => $resp) if (strpos($u, $needle) !== false) return is_callable($resp) ? $resp($m, $u, $h, $b) : $resp;
    return ['status' => 404, 'body' => '{}'];
});
function ok201(array $d): array { return ['status' => 201, 'body' => json_encode($d)]; }
function ok200(array $d): array { return ['status' => 200, 'body' => json_encode($d)]; }

$ROUTES = [
    '/app/installations/77/access_tokens' => fn() => ok201(['token' => 'INSTTOK', 'expires_at' => '2026-10-29T21:00:00Z']),
    '/app/installations/77'               => fn() => ok200(['account' => ['login' => 'dynaz']]),
    '/installation/repositories'          => fn($m, $u, $h) => (strpos(implode(' ', $h), 'Bearer INSTTOK') !== false)
        ? ok200(['total_count' => 1, 'repositories' => [[
            'full_name' => 'dynaz/secret-app', 'private' => true, 'default_branch' => 'main',
            'pushed_at' => '2026-10-20', 'html_url' => 'https://github.com/dynaz/secret-app']]])
        : ['status' => 401, 'body' => '{}'],
];

// ---- installation token + repo listing ----
check(github_app_installation_token($app, '77', 1000) === 'INSTTOK', 'installation token minted');
$appRepos = github_app_list_repos('INSTTOK');
check(count($appRepos) === 1 && $appRepos[0]['fullName'] === 'dynaz/secret-app', 'installation repos listed');
check($appRepos[0]['private'] === true && $appRepos[0]['source'] === 'app', 'app repo tagged private + source=app');

// ---- secure binding: must match the OAuth GitHub login ----
throws(fn() => github_app_bind($db, $app, 'uid1', '77', '', 1000), 409, 'bind needs an OAuth github login first');
throws(fn() => github_app_bind($db, $app, 'uid1', '77', 'someoneelse', 1000), 403, 'bind rejects a mismatched account (not yours)');
throws(fn() => github_app_bind($db, $app, 'uid1', 'abc', 'dynaz', 1000), 400, 'bind rejects a non-numeric installation id');
check(github_app_bind($db, $app, 'uid1', '77', 'dynaz', 1000) === 'dynaz', 'bind accepts the matching account');
check((github_app_load($db, 'uid1')['installation_id'] ?? '') === '77', 'installation id stored server-side');

// ---- handle(): connect returns install url; status reports privateRepos; repos merge; source token ----
$claims = ['sub' => 'uid1', 'email' => 'u@e.com'];
$prem = fn() => true;
$cres = handle($db, $claims, json_encode(['action' => 'github_app_connect']), $prem, 1000);
check(strpos($cres['installUrl'] ?? '', 'installations/new') !== false, 'github_app_connect returns install url');

// Give uid1 a github OAuth connection so repos()/status have an OAuth token + login.
save_connection($db, 'uid1', 'u@e.com', ['access_token' => 'OAUTHTOK'], $key, 1000, 'github', 'dynaz');
$ROUTES['/user/repos'] = fn() => ok200([['full_name' => 'dynaz/public-site', 'private' => false, 'default_branch' => 'main', 'pushed_at' => '2026-10-01', 'html_url' => 'https://github.com/dynaz/public-site']]);
$ROUTES['/user'] = fn() => ok200(['login' => 'dynaz']);

$st = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'github']), $prem, 1000);
check(($st['providers']['github']['privateRepos']['available'] ?? false) === true, 'status: privateRepos available');
check(($st['providers']['github']['privateRepos']['installed'] ?? false) === true, 'status: privateRepos installed');
check(($st['providers']['github']['privateRepos']['account'] ?? '') === 'dynaz', 'status: privateRepos account');

$rres = handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'github']), $prem, 1000);
$names = array_map(fn($r) => $r['fullName'], $rres['repos']);
check(in_array('dynaz/public-site', $names, true) && in_array('dynaz/secret-app', $names, true), 'repos merge: public (oauth) + private (app)');
foreach ($rres['repos'] as $r) if ($r['fullName'] === 'dynaz/secret-app') check($r['source'] === 'app' && $r['private'] === true, 'merged private repo keeps source=app');

// runs with source=app must use the INSTALLATION token (fake route asserts Bearer INSTTOK).
$ROUTES['/repos/dynaz/secret-app/actions/runs'] = fn($m, $u, $h) => (strpos(implode(' ', $h), 'Bearer INSTTOK') !== false)
    ? ok200(['workflow_runs' => [['id' => 5, 'name' => 'CI', 'status' => 'completed', 'conclusion' => 'success', 'head_branch' => 'main', 'created_at' => '2026-10-20', 'html_url' => 'h']]])
    : ['status' => 401, 'body' => '{}'];
$runs = handle($db, $claims, json_encode(['action' => 'runs', 'provider' => 'github', 'repo' => 'dynaz/secret-app', 'source' => 'app']), $prem, 1000);
check(count($runs['runs']) === 1 && $runs['runs'][0]['conclusion'] === 'success', 'private-repo runs via installation token');

// github_app_disconnect removes the binding (allowed even if premium lapsed).
handle($db, $claims, json_encode(['action' => 'github_app_disconnect']), fn() => false, 1000);
check(github_app_load($db, 'uid1') === null, 'github_app_disconnect removes the binding');

// ---- setup callback binds via single-use state ----
save_connection($db, 'uid2', 'u2@e.com', ['access_token' => 'T'], $key, 1000, 'github', 'dynaz');
$state = state_create($db, 'uid2', 'u2@e.com', 1000, 'github_app');
handle_callback($db, ['installation_id' => '77', 'state' => $state], fn() => true, 1000);
check((github_app_load($db, 'uid2')['installation_id'] ?? '') === '77', 'setup callback bound installation via state');

set_http_transport(null);
set_config_override(null);
echo "\n" . ($failures === 0 ? "ALL PASS" : "$failures FAILED") . "\n";
exit($failures === 0 ? 0 : 1);
