<?php
// php whmcs/tests/test_devops_github_pat.php — GitHub fine-grained PAT credential: format, connect,
// status, per-repo credential selection, Actions/rate-limit/token error mapping, and isolation from the
// OAuth connection and the other providers. SQLite + a fake HTTP transport (no network).

require __DIR__ . '/../terminow/devops_lib.php';

use TermiNow\DevOps\Failure;
use function TermiNow\DevOps\migrate;
use function TermiNow\DevOps\handle;
use function TermiNow\DevOps\save_connection;
use function TermiNow\DevOps\load_connection;
use function TermiNow\DevOps\set_http_transport;
use function TermiNow\DevOps\set_config_override;
use function TermiNow\DevOps\github_pat_valid_format;
use function TermiNow\DevOps\github_pat_type;
use function TermiNow\DevOps\load_pat;
use function TermiNow\DevOps\decrypt;
use function TermiNow\DevOps\list_runs;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}
function throws(callable $fn, int $status, string $code, string $name): void
{
    try { $fn(); check(false, $name . " (no throw)"); }
    catch (Failure $e) { check($e->status === $status && $e->getMessage() === $code, $name . " (got {$e->status}/{$e->getMessage()})"); }
    catch (\Throwable $e) { check(false, $name . " (wrong type: " . get_class($e) . ")"); }
}

$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);

$key = str_repeat('k', 32);
$cfg = ['github_client_id' => 'gh', 'github_client_secret' => 'ghs', 'redirect_uri' => 'https://cp/cb', 'token_key' => $key];
set_config_override($cfg);
$premium = fn() => true;

// Routing fake transport: canned responses by URL substring; captures the last Authorization header.
$ROUTES = [];
$LAST_AUTH = '';
set_http_transport(function (string $method, string $url, array $headers, ?string $body) use (&$ROUTES, &$LAST_AUTH) {
    foreach ($headers as $h) if (stripos($h, 'Authorization:') === 0) $LAST_AUTH = $h;
    foreach ($ROUTES as $needle => $resp) {
        if (strpos($url, $needle) !== false) return is_callable($resp) ? $resp($method, $url, $headers, $body) : $resp;
    }
    return ['status' => 404, 'body' => '{}'];
});
function json_ok(array $data): array { return ['status' => 200, 'body' => json_encode($data)]; }

$FG = 'github_pat_' . str_repeat('A', 30);          // a well-formed fine-grained token
$CLASSIC = 'ghp_' . str_repeat('b', 36);            // a well-formed classic token
$claims = ['sub' => 'uidP', 'email' => 'p@e.com'];

// ---- Format recognition (no network) ------------------------------------------------------------
check(github_pat_valid_format($FG), 'fine-grained format accepted');
check(github_pat_valid_format($CLASSIC), 'classic format accepted');
check(!github_pat_valid_format('notatoken'), 'garbage rejected');
check(!github_pat_valid_format('github_pat_short'), 'too-short fine-grained rejected');
check(!github_pat_valid_format(''), 'empty rejected');
check(github_pat_type($FG) === 'fine_grained' && github_pat_type($CLASSIC) === 'classic', 'token type by format');

// ---- connect: format is checked BEFORE any network call -----------------------------------------
$ROUTES = ['/user' => fn() => json_ok(['login' => 'should-not-be-called'])];
throws(fn() => handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => 'bad']), $premium, 1000),
    400, 'bad_token', 'connect rejects malformed token by format');

// ---- connect: valid format but GitHub says 401 → bad_token (wrong/expired/revoked) ---------------
$ROUTES = ['/user' => fn() => ['status' => 401, 'body' => '{}']];
throws(fn() => handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG]), $premium, 1000),
    400, 'bad_token', 'connect rejects a token GitHub 401s');
check(load_pat($db, 'uidP') === null, 'nothing stored after a failed connect');

// ---- connect: valid → stores, returns login + tokenType -----------------------------------------
$ROUTES = ['/user' => fn() => json_ok(['login' => 'octo'])];
$res = handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG]), $premium, 1000);
check($res['login'] === 'octo' && $res['tokenType'] === 'fine_grained', 'connect returns login + fine_grained type');
$row = load_pat($db, 'uidP');
check($row !== null && $row['login'] === 'octo', 'PAT row stored with login');
check($row['provider'] === 'github_pat', "PAT stored under its own provider key (not 'github')");
check($row['scopes'] === 'fine_grained', "stored 'scopes' is the token type, NOT an auto-added repo scope");
check(decrypt((string)$row['enc_token'], $key) === $FG, 'token stored encrypted and round-trips');
check((int)$row['expires_at'] === 0, 'no bogus expiry fabricated (PAT expiry is detected on 401)');

// ---- connect requires Premium (Free cannot use the API) -----------------------------------------
throws(fn() => handle($db, ['sub' => 'uidFree', 'email' => 'f@e.com'],
    json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG]), fn() => false, 1000),
    403, 'premium_required', 'Free user cannot connect a PAT (premium_required)');

// ---- status: PAT sub-block, healthy then expired ------------------------------------------------
$ROUTES = ['/user' => fn() => json_ok(['login' => 'octolive'])];
$st = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'github']), $premium, 2000);
check(isset($st['providers']['github']['pat']), 'status includes github.pat block');
$pat = $st['providers']['github']['pat'];
check($pat['available'] === true && $pat['connected'] === true && $pat['valid'] === true, 'pat available+connected+valid');
check($pat['login'] === 'octolive', 'pat login refreshed from /user');
check($st['providers']['github']['connected'] === false, 'OAuth github still shows not connected (PAT is independent)');

$ROUTES = ['/user' => fn() => ['status' => 401, 'body' => '{}']];
$st2 = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'github']), $premium, 2000);
check($st2['providers']['github']['pat']['connected'] === true, 'expired PAT still shows connected');
check($st2['providers']['github']['pat']['valid'] === false, 'expired PAT → valid=false (app shows Replace token)');

// ---- repos: PAT-only user lists the PAT repos, tagged source=pat --------------------------------
$ROUTES = ['/user/repos' => fn() => json_ok([
    ['full_name' => 'octo/private-app', 'private' => true, 'default_branch' => 'main', 'pushed_at' => '2026-10-01', 'html_url' => 'https://gh/octo/private-app'],
    ['full_name' => 'octo/pub', 'private' => false, 'default_branch' => 'main'],
])];
$r = handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'github']), $premium, 3000);
check(count($r['repos']) === 2, 'PAT-only repos listed');
$byName = []; foreach ($r['repos'] as $x) $byName[$x['fullName']] = $x;
check($byName['octo/private-app']['source'] === 'pat' && $byName['octo/private-app']['private'] === true, 'PAT repo tagged source=pat, private');
check(empty($r['warnings']), 'healthy PAT → no warnings');

// ---- repos: OAuth + PAT merged, PAT wins on a name collision ------------------------------------
save_connection($db, 'uidP', 'p@e.com', ['access_token' => 'OAUTHTOK'], $key, 1000, 'github', 'octo');
$ROUTES = [
    '/user/repos' => function (string $m, string $u, array $h) {
        // Distinguish the OAuth call (Bearer OAUTHTOK) from the PAT call (Bearer <FG>).
        $auth = implode(' ', $h);
        if (strpos($auth, 'Bearer OAUTHTOK') !== false) {
            return json_ok([['full_name' => 'octo/shared', 'private' => false, 'default_branch' => 'oauthbranch']]);
        }
        return json_ok([
            ['full_name' => 'octo/shared', 'private' => true, 'default_branch' => 'patbranch'],   // same name as OAuth
            ['full_name' => 'octo/only-pat', 'private' => true, 'default_branch' => 'main'],
        ]);
    },
];
$r2 = handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'github']), $premium, 3000);
$m2 = []; foreach ($r2['repos'] as $x) $m2[$x['fullName']] = $x;
check(isset($m2['octo/shared']) && isset($m2['octo/only-pat']), 'merged list has both OAuth and PAT repos');
check($m2['octo/shared']['source'] === 'pat' && $m2['octo/shared']['defaultBranch'] === 'patbranch',
    'PAT entry wins the name collision (explicit, private-capable credential)');

// ---- repos: a DEAD PAT does NOT silently hide the error (keeps OAuth list + pat_invalid warning) -
$ROUTES = [
    '/user/repos' => function (string $m, string $u, array $h) {
        $auth = implode(' ', $h);
        if (strpos($auth, 'Bearer OAUTHTOK') !== false) return json_ok([['full_name' => 'octo/pub', 'private' => false]]);
        return ['status' => 401, 'body' => '{}'];   // PAT is dead
    },
];
$r3 = handle($db, $claims, json_encode(['action' => 'repos', 'provider' => 'github']), $premium, 3000);
check(count($r3['repos']) === 1 && $r3['repos'][0]['fullName'] === 'octo/pub', 'dead PAT keeps the OAuth list (no crash)');
check(in_array('pat_invalid', $r3['warnings'] ?? [], true), 'dead PAT surfaces a pat_invalid warning (error not hidden)');

// ---- runs with source=pat uses the PAT token (never a silent OAuth fallback) --------------------
$ROUTES = [
    '/repos/octo/private-app/actions/runs' => function (string $m, string $u, array $h) {
        $auth = implode(' ', $h);
        if (strpos($auth, 'Bearer ' . $GLOBALS['FG']) === false) return ['status' => 401, 'body' => '{}'];
        return json_ok(['workflow_runs' => [['id' => 42, 'name' => 'CI', 'status' => 'completed', 'conclusion' => 'success', 'head_branch' => 'main', 'created_at' => '2026-10-02', 'html_url' => 'https://gh/42']]]);
    },
];
$GLOBALS['FG'] = $FG;
$runs = handle($db, $claims, json_encode(['action' => 'runs', 'provider' => 'github', 'repo' => 'octo/private-app', 'source' => 'pat']), $premium, 3000);
check(count($runs['runs']) === 1 && $runs['runs'][0]['id'] === '42' && $runs['runs'][0]['conclusion'] === 'success',
    'runs(source=pat) used the PAT token and normalized the run');

// ---- source=pat but the PAT was removed → pat_not_connected (no fallback to OAuth) ---------------
$db->prepare('DELETE FROM mod_terminow_devops_conn WHERE uid=? AND provider=?')->execute(['uidP', 'github_pat']);
throws(fn() => handle($db, $claims, json_encode(['action' => 'runs', 'provider' => 'github', 'repo' => 'octo/private-app', 'source' => 'pat']), $premium, 3000),
    409, 'pat_not_connected', 'source=pat with no PAT → pat_not_connected (never falls back to OAuth)');
// restore the PAT for the error-mapping tests below
$ROUTES = ['/user' => fn() => json_ok(['login' => 'octo'])];
handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG]), $premium, 3000);

// ---- Actions error mapping (list_runs directly, github) -----------------------------------------
// 403 on Actions but the repo itself is readable → actions_forbidden (add Actions:Read).
$ROUTES = [
    '/repos/o/r/actions/runs' => fn() => ['status' => 403, 'body' => json_encode(['message' => 'Resource not accessible by personal access token'])],
    '/repos/o/r' => fn() => json_ok(['full_name' => 'o/r']),   // probe: repo IS visible
];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 403, 'actions_forbidden', 'runs 403 + repo readable → actions_forbidden');

// 403 on Actions AND the repo is not visible → no_access.
$ROUTES = ['/repos/o/r/actions/runs' => fn() => ['status' => 403, 'body' => '{"message":"Not Found"}'],
           '/repos/o/r' => fn() => ['status' => 404, 'body' => '{}']];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 403, 'no_access', 'runs 403 + repo not visible → no_access');

// 404 → no_access.
$ROUTES = ['/repos/o/r/actions/runs' => fn() => ['status' => 404, 'body' => '{}']];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 403, 'no_access', 'runs 404 → no_access');

// 401 → token_invalid.
$ROUTES = ['/repos/o/r/actions/runs' => fn() => ['status' => 401, 'body' => '{}']];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 401, 'token_invalid', 'runs 401 → token_invalid');

// 429, and 403 with a rate-limit message → rate_limited.
$ROUTES = ['/repos/o/r/actions/runs' => fn() => ['status' => 429, 'body' => '{}']];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 429, 'rate_limited', 'runs 429 → rate_limited');
$ROUTES = ['/repos/o/r/actions/runs' => fn() => ['status' => 403, 'body' => json_encode(['message' => 'API rate limit exceeded for user'])]];
throws(fn() => list_runs('github', 'TOK', 'o/r'), 429, 'rate_limited', 'runs 403 "rate limit" → rate_limited');

// ---- disconnect: allowed even without Premium; removes ONLY the PAT row -------------------------
handle($db, $claims, json_encode(['action' => 'github_pat_disconnect', 'provider' => 'github']), fn() => false, 4000);
check(load_pat($db, 'uidP') === null, 'disconnect removed the PAT row (even with Premium lapsed)');
check(load_connection($db, 'uidP', 'github') !== null, 'disconnect kept the OAuth connection (isolation preserved)');

// ---- Replace token overwrites the stored token (clear-then-store), OAuth row untouched ----------
$ROUTES = ['/user' => fn() => json_ok(['login' => 'octo'])];
handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG]), $premium, 5000);
$FG2 = 'github_pat_' . str_repeat('Z', 30);
handle($db, $claims, json_encode(['action' => 'github_pat_connect', 'provider' => 'github', 'token' => $FG2]), $premium, 5000);
$rowR = load_pat($db, 'uidP');
check(decrypt((string)$rowR['enc_token'], $key) === $FG2, 'replace stored the NEW token (old token cleared)');
check(load_connection($db, 'uidP', 'github')['login'] === 'octo', 'replace did not touch the OAuth connection');

// ---- GitLab/Bitbucket unaffected by a GitHub PAT ------------------------------------------------
$st3 = handle($db, $claims, json_encode(['action' => 'status', 'provider' => 'gitlab']), $premium, 6000);
check(!isset($st3['providers']['gitlab']['pat']), 'gitlab status has no PAT block (PAT is GitHub-only)');
check($st3['providers']['gitlab']['connected'] === false, 'gitlab unaffected: still not connected');

set_http_transport(null);
set_config_override(null);

echo "\n" . ($failures === 0 ? "ALL PASS" : "$failures FAILED") . "\n";
exit($failures === 0 ? 0 : 1);
