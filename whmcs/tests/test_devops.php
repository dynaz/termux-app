<?php
// php whmcs/tests/test_devops.php — tests of the TermiNow DevOps server logic, with SQLite and no network.

require __DIR__ . '/../terminow/devops_lib.php';

use TermiNow\DevOps\Failure;
use function TermiNow\DevOps\migrate;
use function TermiNow\DevOps\encrypt;
use function TermiNow\DevOps\decrypt;
use function TermiNow\DevOps\state_create;
use function TermiNow\DevOps\state_consume;
use function TermiNow\DevOps\repo_param;
use function TermiNow\DevOps\authorize_url;

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
    catch (\Throwable $e) { check(false, $name . " (wrong type)"); }
}

$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);
migrate($db); // idempotent
check(true, 'migrate is idempotent');

// Encryption round-trip with a 32-byte key; ciphertext differs from plaintext and each call.
$key = random_bytes(32);
$a = encrypt('gho_secrettoken', $key);
$b = encrypt('gho_secrettoken', $key);
check(decrypt($a, $key) === 'gho_secrettoken', 'encrypt/decrypt round-trip');
check($a !== $b, 'encryption uses a fresh IV each time');
check(strpos(base64_decode($a), 'gho_secrettoken') === false, 'ciphertext does not contain plaintext');
throws(fn() => decrypt($a, random_bytes(32)), 503, 'decrypt with wrong key fails');

// State: single-use and short-lived, bound to uid+email.
$now = 1800000000;
$state = state_create($db, 'uid123', 'u@example.com', $now);
check(preg_match('/^[0-9a-f]{64}$/', $state) === 1, 'state is 64 hex chars');
[$uid, $email] = state_consume($db, $state, $now + 5);
check($uid === 'uid123' && $email === 'u@example.com', 'state consume returns uid+email');
throws(fn() => state_consume($db, $state, $now + 6), 400, 'state is single-use');

$expired = state_create($db, 'uid123', 'u@example.com', $now);
throws(fn() => state_consume($db, $expired, $now + 601), 400, 'state expires after TTL');
throws(fn() => state_consume($db, 'not-a-real-state', $now), 400, 'unknown state rejected');

// repo_param validation (prevents path injection into the GitHub API).
check(repo_param(['repo' => 'owner/name']) === 'owner/name', 'valid repo accepted');
foreach (['', 'owner', 'owner/name/extra', '../etc', 'owner/na me', 'owner/name?x=1'] as $bad)
    throws(fn() => repo_param(['repo' => $bad]), 400, "bad repo rejected: '$bad'");

// authorize_url builds the expected GitHub URL with the read-only scope and no `workflow`.
$config = ['github_client_id' => 'cid', 'github_client_secret' => 'x', 'redirect_uri' => 'https://cp.accmove.com/terminow/devops/callback/github', 'token_key' => $key];
$url = authorize_url($config, $state);
check(strpos($url, 'https://github.com/login/oauth/authorize?') === 0, 'authorize url base');
check(strpos($url, 'client_id=cid') !== false, 'authorize url has client id');
check(strpos($url, rawurlencode('read:user repo')) !== false || strpos($url, 'read%3Auser+repo') !== false || strpos($url, 'scope=read%3Auser') !== false, 'authorize url has read-only scope');
check(strpos($url, 'workflow') === false, 'authorize url does NOT request the workflow scope');

echo $failures === 0 ? "\nALL PASS\n" : "\n$failures FAILED\n";
exit($failures === 0 ? 0 : 1);
