<?php
/**
 * TermiNow Store sync, at https://cp.accmove.com/terminow/store_sync.php
 *
 * Only Firebase calls it, with requests signed by the Store sync secret of the TermiNow Play addon
 * (storeSyncSecret). It records a Devnet SANDBOX entitlement mirror and associates the WHMCS client.
 * It NEVER bills and NEVER creates or activates a WHMCS service. Premium never depends on it.
 */

require __DIR__ . '/store_sync_lib.php';

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function store_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') store_respond(405, ['error' => 'POST only', 'code' => 'BAD_REQUEST']);
$body = (string)file_get_contents('php://input', false, null, 0, 65536);

require dirname(__DIR__) . '/init.php';

try {
    $settings = [];
    foreach (\Illuminate\Database\Capsule\Manager::table('tbladdonmodules')->where('module', 'terminow_play')->get(['setting', 'value']) as $row)
        $settings[$row->setting] = $row->value;
    if (($settings['version'] ?? '') === '') store_respond(503, ['error' => 'The TermiNow Play addon is not activated', 'code' => 'WHMCS_NOT_CONFIGURED']);
    // A dedicated secret so the Store and Play endpoints rotate independently. Stored encrypted by WHMCS.
    $secret = decrypt((string)($settings['storeSyncSecret'] ?? ''));
    \TermiNow\StoreSync\verify_signature($secret, (string)($_SERVER['HTTP_X_TERMINOW_TIMESTAMP'] ?? ''),
        (string)($_SERVER['HTTP_X_TERMINOW_SIGNATURE'] ?? ''), $body, time());
    $request = json_decode($body, true);
    if (!is_array($request)) store_respond(400, ['error' => 'Invalid JSON', 'code' => 'BAD_REQUEST']);

    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\StoreSync\migrate($db);
    $api = fn(string $command, array $params) => localAPI($command, $params);
    // Real grants stay DISABLED in this phase: allowRealGrants is never true, so every mirror is a
    // Devnet sandbox record and no WHMCS service is ever created, whatever the request claims.
    store_respond(200, \TermiNow\StoreSync\handle($db, $api, $request, ['allowRealGrants' => false], time()));
} catch (\TermiNow\StoreSync\Failure $e) {
    store_respond($e->status, ['error' => $e->getMessage(), 'code' => $e->errorCode]);
} catch (\Throwable $e) {
    store_respond(503, ['error' => 'Try again later', 'code' => 'WHMCS_ERROR']);
}
