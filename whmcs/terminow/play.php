<?php
/**
 * TermiNow Premium bought with Google Play, at https://cp.accmove.com/terminow/play.php
 *
 * POST {"purchaseToken": "..."} with `Authorization: Bearer <Firebase ID token>` after a
 * purchase in the app. The purchase is checked with Google, kept for this account and
 * acknowledged, see play_lib.php. Answers like premium.php. Never logs the tokens.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/play_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function play_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') play_respond(405, ['error' => 'POST only']);
$token = bearer_token($_SERVER);
if ($token === null) play_respond(401, ['error' => 'Sign in first']);

try {
    $claims = verify_id_token($token, fetch_certs(sys_get_temp_dir() . '/terminow-google-certs.json'), time());
} catch (\TermiNow\Premium\TokenException $e) {
    play_respond(401, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    play_respond(503, ['error' => 'Try again later']);
}

$request = json_decode((string)file_get_contents('php://input', false, null, 0, 8192), true);
$purchaseToken = is_array($request) && is_string($request['purchaseToken'] ?? null) ? $request['purchaseToken'] : '';

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

try {
    $google = terminow_google();
    if ($google === null) play_respond(503, ['error' => 'Google Play purchases are not set up yet']);
    $db = terminow_db();
    \TermiNow\Play\migrate($db);
    \TermiNow\Play\register($db, $google, (string)$claims['sub'], $purchaseToken, time());
    play_respond(200, terminow_premium($db, $claims));
} catch (\TermiNow\Play\Failure $e) {
    play_respond($e->status, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    play_respond(503, ['error' => 'Try again later']);
}
