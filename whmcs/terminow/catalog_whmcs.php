<?php
/**
 * WHMCS-runtime price lookup for the catalog feed. Kept out of catalog_lib.php (which is pure and
 * unit-tested) because it reads live WHMCS pricing via the internal API and the WHMCS tables. Shared
 * by terminow/catalog.php (the Store feed) and the addon admin "Preview Store feed", so the preview
 * shows exactly what the Store will receive. Read-only: it never writes to WHMCS.
 */

if (!function_exists('terminow_catalog_price_lookup')) {

    /**
     * A callable fn(array $catalogRow): ?array => ['amount','currency','billingCycle'] or null, backed
     * by live WHMCS pricing. Products via GetProducts; product-addons via tbladdons/tblpricing. The
     * per-product cache keeps one admin render or one feed request cheap.
     */
    function terminow_catalog_price_lookup(): callable
    {
        $cache = [];
        return function (array $entry) use (&$cache): ?array {
            if ($entry['whmcs_product_id'] !== null) return terminow_catalog_product_price((int)$entry['whmcs_product_id'], $cache);
            if ($entry['whmcs_addon_id'] !== null) return terminow_catalog_addon_price((int)$entry['whmcs_addon_id']);
            return null;
        };
    }

    /** Native recurring price of a WHMCS product, via the internal API (read only). */
    function terminow_catalog_product_price(int $pid, array &$cache): ?array
    {
        if (array_key_exists($pid, $cache)) return $cache[$pid];
        $resp = localAPI('GetProducts', ['pid' => $pid]);
        $info = $resp['products']['product'][0] ?? null;
        if (($resp['result'] ?? '') !== 'success' || !is_array($info)) return $cache[$pid] = null;
        $pricing = $info['pricing'] ?? [];
        if (!is_array($pricing) || $pricing === [])
            return $cache[$pid] = (($info['paytype'] ?? '') === 'free') ? ['amount' => '0.00', 'currency' => '', 'billingCycle' => 'free'] : null;
        $currency = array_key_first($pricing);
        foreach (['monthly', 'quarterly', 'semiannually', 'annually', 'biennially', 'triennially'] as $cycle) {
            $value = $pricing[$currency][$cycle] ?? '-1.00';
            if ($value !== '-1.00' && $value !== -1 && $value !== null)
                return $cache[$pid] = ['amount' => (string)$value, 'currency' => (string)$currency, 'billingCycle' => $cycle];
        }
        $once = $pricing[$currency]['onetime'] ?? ($pricing[$currency]['msetupfee'] ?? null);
        if ($once !== null && $once !== '-1.00') return $cache[$pid] = ['amount' => (string)$once, 'currency' => (string)$currency, 'billingCycle' => 'onetime'];
        return $cache[$pid] = null;
    }

    /** Native price of a WHMCS product-addon, read directly (read only). Null if none is configured. */
    function terminow_catalog_addon_price(int $addonId): ?array
    {
        try {
            $cap = '\Illuminate\Database\Capsule\Manager';
            if ($cap::table('tbladdons')->where('id', $addonId)->first() === null) return null;
            $price = $cap::table('tblpricing')->where('type', 'addon')->where('relid', $addonId)->first();
            if ($price === null) return null;
            foreach (['monthly', 'quarterly', 'semiannually', 'annually', 'biennially', 'triennially'] as $cycle) {
                $value = $price->$cycle ?? '-1.00';
                if ($value !== '-1.00' && (float)$value >= 0) {
                    $currency = $cap::table('tblcurrencies')->where('id', $price->currency)->value('code');
                    return ['amount' => (string)$value, 'currency' => (string)($currency ?? ''), 'billingCycle' => $cycle];
                }
            }
        } catch (\Throwable $e) {
            return null;
        }
        return null;
    }
}
