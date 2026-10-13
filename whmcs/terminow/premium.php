<?php
/**
 * TermiNow Premium check, at https://cp.accmove.com/terminow/premium.php
 *
 * POST with `Authorization: Bearer <Firebase ID token>`. Answers
 * {"premium": true, "plan": "...", "nextDueDate": "YYYY-MM-DD" or null, "source": "accmove" or "play"}
 * or {"premium": false}.
 *
 * It uses the internal API of WHMCS, so no API credentials exist anywhere. It answers nothing
 * else about the client, and never logs the token.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/play_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') respond(405, ['error' => 'POST only']);
$token = bearer_token($_SERVER);
if ($token === null) respond(401, ['error' => 'Sign in first']);

try {
    $claims = verify_id_token($token, fetch_certs(sys_get_temp_dir() . '/terminow-google-certs.json'), time());
} catch (\TermiNow\Premium\TokenException $e) {
    respond(401, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    respond(503, ['error' => 'Try again later']);
}

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

try {
    $db = terminow_db();
    \TermiNow\Play\migrate($db);
    respond(200, terminow_premium($db, $claims));
} catch (\Throwable $e) {
    respond(503, ['error' => 'Try again later']);
}
