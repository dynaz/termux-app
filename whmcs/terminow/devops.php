<?php
/**
 * TermiNow DevOps actions, at https://cp.accmove.com/terminow/devops.php
 *
 * POST a JSON object with an "action" and `Authorization: Bearer <Firebase ID token>`. Mirrors vault.php:
 * verify the token, then run the action with the existing entitlement. The GitHub OAuth callback is a
 * separate endpoint (devops_callback.php) because GitHub's redirect carries no Firebase header.
 * Nothing secret is logged; provider tokens never leave the server.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/devops_lib.php';
require __DIR__ . '/play_sync_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function devops_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') devops_respond(405, ['error' => 'POST only']);
$token = bearer_token($_SERVER);
if ($token === null) devops_respond(401, ['error' => 'Sign in first']);

try {
    $claims = verify_id_token($token, fetch_certs(sys_get_temp_dir() . '/terminow-google-certs.json'), time());
} catch (\TermiNow\Premium\TokenException $e) {
    devops_respond(401, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    devops_respond(503, ['error' => 'Try again later']);
}

$body = file_get_contents('php://input', false, null, 0, 65536);

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

$isPremiumNow = function () use (&$db, $claims, $token): bool {
    return terminow_premium($db, $claims, $token);
};

try {
    $db = terminow_db();
    \TermiNow\DevOps\migrate($db);
    devops_respond(200, \TermiNow\DevOps\handle($db, $claims, (string)$body, $isPremiumNow, time()));
} catch (\TermiNow\DevOps\Failure $e) {
    devops_respond($e->status, ['error' => $e->getMessage()] + $e->extra);
} catch (\Throwable $e) {
    devops_respond(503, ['error' => 'Try again later']);
}
