<?php
/**
 * TermiNow Store catalog feed, at https://cp.accmove.com/terminow/catalog.php
 *
 * A server-to-server endpoint the TermiNow Store calls to read the mapped catalog. It is
 * authenticated with the catalog secret of the TermiNow Play addon (catalogSecret), signed exactly
 * like the other endpoints. It is READ ONLY: it returns the Store-visible catalog entries with their
 * native WHMCS price and billing cycle (read live from WHMCS, never duplicated), the sandbox TNAI
 * price and localized display text. The Store keeps the credential server-side and snapshots price,
 * duration and mapping at purchase time, so later edits here never rewrite existing orders.
 */

require __DIR__ . '/catalog_lib.php';
require __DIR__ . '/catalog_whmcs.php';

use TermiNow\Catalog\Unauthorized;

header('Content-Type: application/json');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function catalog_respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') catalog_respond(405, ['error' => 'POST only', 'code' => 'BAD_REQUEST']);
$body = (string)file_get_contents('php://input', false, null, 0, 65536);

require dirname(__DIR__) . '/init.php';

try {
    $settings = [];
    foreach (\Illuminate\Database\Capsule\Manager::table('tbladdonmodules')->where('module', 'terminow_play')->get(['setting', 'value']) as $row)
        $settings[$row->setting] = $row->value;
    if (($settings['version'] ?? '') === '') catalog_respond(503, ['error' => 'The TermiNow Play addon is not activated', 'code' => 'WHMCS_NOT_CONFIGURED']);
    $secret = decrypt((string)($settings['catalogSecret'] ?? ''));
    \TermiNow\Catalog\verify_signature($secret, (string)($_SERVER['HTTP_X_TERMINOW_TIMESTAMP'] ?? ''),
        (string)($_SERVER['HTTP_X_TERMINOW_SIGNATURE'] ?? ''), $body, time());

    $request = json_decode($body, true);
    $locale = is_array($request) && is_string($request['locale'] ?? null) ? substr($request['locale'], 0, 16) : null;

    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\Catalog\migrate($db);

    // Native WHMCS pricing, read live. A mapped item whose pricing cannot be read is returned
    // as unavailable (handled visibly by the Store), never dropped and never faked.
    catalog_respond(200, \TermiNow\Catalog\catalog_for_store($db, terminow_catalog_price_lookup(), $locale));
} catch (Unauthorized $e) {
    catalog_respond(401, ['error' => $e->getMessage(), 'code' => $e->errorCode]);
} catch (\Throwable $e) {
    catalog_respond(503, ['error' => 'Try again later', 'code' => 'WHMCS_ERROR']);
}
