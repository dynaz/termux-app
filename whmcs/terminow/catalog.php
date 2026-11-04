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
    $priceCache = [];
    $priceLookup = function (array $entry) use (&$priceCache): ?array {
        if ($entry['whmcs_product_id'] !== null) return catalog_product_price((int)$entry['whmcs_product_id'], $priceCache);
        if ($entry['whmcs_addon_id'] !== null) return catalog_addon_price((int)$entry['whmcs_addon_id']);
        return null;
    };
    catalog_respond(200, \TermiNow\Catalog\catalog_for_store($db, $priceLookup, $locale));
} catch (Unauthorized $e) {
    catalog_respond(401, ['error' => $e->getMessage(), 'code' => $e->errorCode]);
} catch (\Throwable $e) {
    catalog_respond(503, ['error' => 'Try again later', 'code' => 'WHMCS_ERROR']);
}

/** Native recurring price of a WHMCS product, via the internal API (read only). */
function catalog_product_price(int $pid, array &$cache): ?array
{
    if (array_key_exists($pid, $cache)) return $cache[$pid];
    $resp = localAPI('GetProducts', ['pid' => $pid]);
    $info = $resp['products']['product'][0] ?? null;
    if (($resp['result'] ?? '') !== 'success' || !is_array($info)) return $cache[$pid] = null;
    $pricing = $info['pricing'] ?? [];
    if (!is_array($pricing) || $pricing === []) {
        // A free product (Google Play managed) has no store price of its own.
        return $cache[$pid] = (($info['paytype'] ?? '') === 'free') ? ['amount' => '0.00', 'currency' => '', 'billingCycle' => 'free'] : null;
    }
    $currency = array_key_first($pricing);
    $cycles = ['monthly', 'quarterly', 'semiannually', 'annually', 'biennially', 'triennially'];
    foreach ($cycles as $cycle) {
        $value = $pricing[$currency][$cycle] ?? '-1.00';
        if ($value !== '-1.00' && $value !== -1 && $value !== null)
            return $cache[$pid] = ['amount' => (string)$value, 'currency' => (string)$currency, 'billingCycle' => $cycle];
    }
    $once = $pricing[$currency]['onetime'] ?? ($pricing[$currency]['msetupfee'] ?? null);
    if ($once !== null && $once !== '-1.00') return $cache[$pid] = ['amount' => (string)$once, 'currency' => (string)$currency, 'billingCycle' => 'onetime'];
    return $cache[$pid] = null;
}

/** Native price of a WHMCS product-addon, read directly (read only). Null if none is configured. */
function catalog_addon_price(int $addonId): ?array
{
    try {
        $addon = \Illuminate\Database\Capsule\Manager::table('tbladdons')->where('id', $addonId)->first();
        if ($addon === null) return null;
        $price = \Illuminate\Database\Capsule\Manager::table('tblpricing')
            ->where('type', 'addon')->where('relid', $addonId)->first();
        if ($price === null) return null;
        $cycles = ['monthly', 'quarterly', 'semiannually', 'annually', 'biennially', 'triennially'];
        foreach ($cycles as $cycle) {
            $value = $price->$cycle ?? '-1.00';
            if ($value !== '-1.00' && (float)$value >= 0) {
                $currency = \Illuminate\Database\Capsule\Manager::table('tblcurrencies')->where('id', $price->currency)->value('code');
                return ['amount' => (string)$value, 'currency' => (string)($currency ?? ''), 'billingCycle' => $cycle];
            }
        }
    } catch (\Throwable $e) {
        return null;
    }
    return null;
}
