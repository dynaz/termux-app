<?php
/**
 * TermiNow vaults, at https://cp.accmove.com/terminow/vault.php
 *
 * POST a JSON object with an "action" and `Authorization: Bearer <Firebase ID token>`. See
 * vault_lib.php. Everything stored is encrypted by the app; nothing is logged.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/vault_lib.php';
require __DIR__ . '/play_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function vault_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') vault_respond(405, ['error' => 'POST only']);
$token = bearer_token($_SERVER);
if ($token === null) vault_respond(401, ['error' => 'Sign in first']);

try {
    $claims = verify_id_token($token, fetch_certs(sys_get_temp_dir() . '/terminow-google-certs.json'), time());
} catch (\TermiNow\Premium\TokenException $e) {
    vault_respond(401, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    vault_respond(503, ['error' => 'Try again later']);
}

$body = file_get_contents('php://input', false, null, 0, \TermiNow\Vault\MAX_DATA + 65536);
$request = json_decode((string)$body, true);
if (!is_array($request)) vault_respond(400, ['error' => 'Invalid JSON']);

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

// Only ever asked for the signed-in user, whose uid finds their Play subscriptions
$isPremium = function (string $email) use (&$db, $claims): bool {
    try {
        return (bool)terminow_premium($db, $claims)['premium'];
    } catch (\TermiNow\Play\Failure $e) {
        return false;
    }
};

try {
    $db = terminow_db();
    \TermiNow\Vault\migrate($db);
    \TermiNow\Play\migrate($db);
    vault_respond(200, \TermiNow\Vault\handle($db, $claims, $request, $isPremium, time()));
} catch (\TermiNow\Vault\Failure $e) {
    if (isset($db) && $db->inTransaction()) $db->rollBack();
    vault_respond($e->status, ['error' => $e->getMessage()] + $e->extra);
} catch (\Throwable $e) {
    if (isset($db) && $db->inTransaction()) $db->rollBack();
    vault_respond(503, ['error' => 'Try again later']);
}
