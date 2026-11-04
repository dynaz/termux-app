<?php
/**
 * MOCKED-WHMCS staging catalog feed. Faithful to whmcs/terminow/catalog.php's logic — it uses the SAME
 * catalog_lib.php verify_signature() + catalog_for_store() — but without WHMCS init.php. The only
 * substitution is a synthetic native-price lookup (no live WHMCS GetProducts). It is NOT a test of the
 * real WHMCS runtime; it exercises the catalog composition, signing and the Store's consumption of it.
 *
 * Run as a router:  CATALOG_DB=… CATALOG_SECRET_FILE=… php -S 127.0.0.1:9310 catalog-feed.php
 * Env:
 *   CATALOG_DB           path to the staging SQLite catalog DB
 *   CATALOG_SECRET_FILE  file holding the shared catalog HMAC secret (never printed)
 *   CATALOG_PRICES_JSON  optional JSON {"products":{"101":{amount,currency,billingCycle}},"addons":{…}}
 */
require dirname(__DIR__, 2) . '/terminow/catalog_lib.php';

use TermiNow\Catalog\Unauthorized;

header('Content-Type: application/json');
header('Cache-Control: no-store');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { http_response_code(405); echo json_encode(['error' => 'POST only', 'code' => 'BAD_REQUEST']); exit; }
$body = (string)file_get_contents('php://input');

try {
    $secret = trim((string)file_get_contents(getenv('CATALOG_SECRET_FILE')));
    \TermiNow\Catalog\verify_signature($secret, (string)($_SERVER['HTTP_X_TERMINOW_TIMESTAMP'] ?? ''),
        (string)($_SERVER['HTTP_X_TERMINOW_SIGNATURE'] ?? ''), $body, time());

    $request = json_decode($body, true);
    $locale = is_array($request) && is_string($request['locale'] ?? null) ? substr($request['locale'], 0, 16) : null;

    $db = new PDO('sqlite:' . getenv('CATALOG_DB'));
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\Catalog\migrate($db);

    // Synthetic native prices (stand in for live WHMCS GetProducts). Defaults keep addon 900 unpriced
    // on purpose so the "unavailable, not dropped" path is exercised.
    $cfg = getenv('CATALOG_PRICES_JSON') ? json_decode(getenv('CATALOG_PRICES_JSON'), true) : [];
    $byProduct = $cfg['products'] ?? [101 => ['amount' => '4.99', 'currency' => 'USD', 'billingCycle' => 'monthly'], 102 => ['amount' => '49.00', 'currency' => 'USD', 'billingCycle' => 'annually']];
    $byAddon = $cfg['addons'] ?? [800 => ['amount' => '2.00', 'currency' => 'USD', 'billingCycle' => 'monthly']];
    $prices = function (array $entry) use ($byProduct, $byAddon): ?array {
        if ($entry['whmcs_product_id'] !== null) return $byProduct[(int)$entry['whmcs_product_id']] ?? null;
        if ($entry['whmcs_addon_id'] !== null) return $byAddon[(int)$entry['whmcs_addon_id']] ?? null;
        return null;
    };

    echo json_encode(\TermiNow\Catalog\catalog_for_store($db, $prices, $locale));
} catch (Unauthorized $e) {
    http_response_code(401);
    echo json_encode(['error' => $e->getMessage(), 'code' => $e->errorCode]);
} catch (\Throwable $e) {
    http_response_code(503);
    echo json_encode(['error' => 'Try again later', 'code' => 'WHMCS_ERROR']);
}
