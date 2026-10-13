<?php
/**
 * TermiNow Google Play sync, at https://cp.accmove.com/terminow/play_sync.php
 *
 * Only Firebase calls it, with requests signed by the secret of the TermiNow Play addon (see
 * play_sync_lib.php). It keeps the WHMCS client and service of Google Play subscribers, and
 * redeems link codes. Premium never depends on it.
 */

require __DIR__ . '/play_sync_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function sync_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') sync_respond(405, ['error' => 'POST only', 'code' => 'BAD_REQUEST']);
$body = (string)file_get_contents('php://input', false, null, 0, 65536);

require dirname(__DIR__) . '/init.php';

try {
    $settings = [];
    foreach (\Illuminate\Database\Capsule\Manager::table('tbladdonmodules')->where('module', 'terminow_play')->get(['setting', 'value']) as $row)
        $settings[$row->setting] = $row->value;
    if (($settings['version'] ?? '') === '') sync_respond(503, ['error' => 'The TermiNow Play addon is not activated', 'code' => 'WHMCS_NOT_CONFIGURED']);
    // The password field of the addon is stored encrypted by WHMCS
    $secret = decrypt((string)($settings['syncSecret'] ?? ''));
    \TermiNow\PlaySync\verify_signature($secret, (string)($_SERVER['HTTP_X_TERMINOW_TIMESTAMP'] ?? ''),
        (string)($_SERVER['HTTP_X_TERMINOW_SIGNATURE'] ?? ''), $body, time());
    $request = json_decode($body, true);
    if (!is_array($request)) sync_respond(400, ['error' => 'Invalid JSON', 'code' => 'BAD_REQUEST']);

    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\PlaySync\migrate($db);
    $api = fn(string $command, array $params) => localAPI($command, $params);
    sync_respond(200, \TermiNow\PlaySync\handle($db, $api, $request, ['paymentMethod' => (string)($settings['paymentMethod'] ?? '')], time()));
} catch (\TermiNow\PlaySync\Failure $e) {
    sync_respond($e->status, ['error' => $e->getMessage(), 'code' => $e->errorCode]);
} catch (\Throwable $e) {
    sync_respond(503, ['error' => 'Try again later', 'code' => 'WHMCS_ERROR']);
}
