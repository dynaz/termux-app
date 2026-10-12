<?php
/**
 * TermiNow Premium check, at https://cp.accmove.com/terminow/premium.php
 *
 * POST with `Authorization: Bearer <Firebase ID token>`. Answers
 * {"premium": true, "plan": "...", "nextDueDate": "YYYY-MM-DD"} or {"premium": false}.
 *
 * It uses the internal API of WHMCS, so no API credentials exist anywhere. It answers nothing
 * else about the client, and never logs the token.
 */

use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\decide;
use function TermiNow\Premium\fetch_certs;
use function TermiNow\Premium\verify_id_token;

require __DIR__ . '/lib.php';

/**
 * WHMCS product ids that are TermiNow Premium, in addition to every product whose name or
 * group name contains "TermiNow". 90 is MobiClaude Premium, which includes TermiNow Premium.
 */
const TERMINOW_PREMIUM_PRODUCT_IDS = [90];

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

$client = localAPI('GetClientsDetails', ['email' => $claims['email'], 'stats' => false]);
if (($client['result'] ?? '') !== 'success' || empty($client['client']['id'] ?? $client['userid'] ?? null)) {
    // No client with this email: not Premium. The same answer as for a client without it.
    respond(200, ['premium' => false]);
}
$clientId = (int)($client['client']['id'] ?? $client['userid']);
$products = localAPI('GetClientsProducts', ['clientid' => $clientId, 'limitnum' => 250]);
if (($products['result'] ?? '') !== 'success') respond(503, ['error' => 'Try again later']);
$list = $products['products']['product'] ?? [];
respond(200, decide(is_array($list) ? $list : [], TERMINOW_PREMIUM_PRODUCT_IDS));
