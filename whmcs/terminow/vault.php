<?php
/**
 * TermiNow vaults, at https://cp.accmove.com/terminow/vault.php
 *
 * POST a JSON object with an "action" and `Authorization: Bearer <Firebase ID token>`. See
 * vault_lib.php. Everything stored is encrypted by the app; nothing is logged.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\decide;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';
require __DIR__ . '/vault_lib.php';

/** The same Premium products as premium.php. */
const TERMINOW_VAULT_PREMIUM_PRODUCT_IDS = [90];

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

$isPremium = function (string $email): bool {
    $client = localAPI('GetClientsDetails', ['email' => $email, 'stats' => false]);
    if (($client['result'] ?? '') !== 'success' || empty($client['client']['id'] ?? $client['userid'] ?? null)) return false;
    $products = localAPI('GetClientsProducts', ['clientid' => (int)($client['client']['id'] ?? $client['userid']), 'limitnum' => 250]);
    if (($products['result'] ?? '') !== 'success') return false;
    $list = $products['products']['product'] ?? [];
    return decide(is_array($list) ? $list : [], TERMINOW_VAULT_PREMIUM_PRODUCT_IDS)['premium'];
};

try {
    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\Vault\migrate($db);
    vault_respond(200, \TermiNow\Vault\handle($db, $claims, $request, $isPremium, time()));
} catch (\TermiNow\Vault\Failure $e) {
    if (isset($db) && $db->inTransaction()) $db->rollBack();
    vault_respond($e->status, ['error' => $e->getMessage()] + $e->extra);
} catch (\Throwable $e) {
    if (isset($db) && $db->inTransaction()) $db->rollBack();
    vault_respond(503, ['error' => 'Try again later']);
}
