<?php
/**
 * Server-to-server Premium check for the TermiNow MCP relay, at
 * https://cp.accmove.com/terminow/relay_check.php
 *
 * The relay (never the app, never a browser) calls this to learn, for a Firebase ID token, the
 * stable user id and whether that user has TermiNow Premium right now. It is the single authority:
 * the relay trusts only what this returns, and this trusts only a Firebase ID token it verifies
 * itself and Premium it reads from WHMCS (accmove) and the Google Play entitlement in Firestore.
 *
 * Auth: a shared secret in `X-Relay-Secret` (stored encrypted in the WHMCS addon, only on the
 * servers), AND `Authorization: Bearer <Firebase ID token>`. The secret proves the caller is the
 * relay; the token proves who the user is. Neither is ever logged.
 *
 * POST -> {"uid": "<firebase uid>", "email": "...", "premium": true|false, "exp": <id token exp>}
 * The email is returned only so the relay can show it; the identity key is the uid.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/play_sync_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function relay_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') relay_respond(405, ['error' => 'POST only']);

$token = bearer_token($_SERVER);
if ($token === null) relay_respond(401, ['error' => 'A Firebase ID token is required']);

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

// The shared secret is the password-type field of the TermiNow Play addon, stored encrypted.
$settings = [];
foreach (\Illuminate\Database\Capsule\Manager::table('tbladdonmodules')->where('module', 'terminow_play')->get(['setting', 'value']) as $row)
    $settings[$row->setting] = $row->value;
$expected = decrypt((string)($settings['relaySecret'] ?? ''));
$presented = (string)($_SERVER['HTTP_X_RELAY_SECRET'] ?? '');
// Fail closed when no secret is configured, and compare in constant time.
if ($expected === '' || strlen($presented) < 16 || !hash_equals($expected, $presented))
    relay_respond(401, ['error' => 'Not authorized']);

try {
    $claims = verify_id_token($token, fetch_certs(sys_get_temp_dir() . '/terminow-google-certs.json'), time());
} catch (\TermiNow\Premium\TokenException $e) {
    relay_respond(401, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    relay_respond(503, ['error' => 'Try again later']);
}

try {
    $premium = terminow_premium(terminow_db(), $claims, $token);
} catch (\Throwable $e) {
    relay_respond(503, ['error' => 'Try again later']);
}

relay_respond(200, [
    'uid' => (string)$claims['sub'],
    'email' => (string)$claims['email'],
    'premium' => (bool)$premium,
    'exp' => (int)$claims['exp'],
]);
