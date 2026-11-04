<?php
/**
 * MOCKED-WHMCS staging driver for the REAL outbox (whmcs/terminow/entitlement_lib.php) and catalog
 * (catalog_lib.php). The ONLY WHMCS-runtime seam is the $api callable (WHMCS localAPI /
 * GetClientsProducts), fed here from synthetic service JSON — exactly the shape WHMCS returns. The
 * sender replicates hooks.php's terminow_ent_sender (envelope + sign + 409->HELD). This drives the
 * real signing, ordering, idempotency, reconcile and retry code against a real HTTP writer; it is NOT
 * a test of the WHMCS runtime itself (no WHMCS install, no real hooks firing).
 *
 * Env: CATALOG_DB, STAGING_DATA (dir of services_<clientId>.json), ENTITLEMENT_URL,
 *      ENTITLEMENT_SECRET_FILE, NOW_S (optional, unix SECONDS — matches hooks.php time()).
 * Usage: php outbox.php <map|sync|deliver|reconcile|retry|events> [args]
 */
require dirname(__DIR__, 2) . '/terminow/catalog_lib.php';
require dirname(__DIR__, 2) . '/terminow/entitlement_lib.php';

$DB = new PDO('sqlite:' . getenv('CATALOG_DB'));
$DB->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
\TermiNow\Catalog\migrate($DB);
\TermiNow\Entitlement\migrate($DB);

$NOW = getenv('NOW_S') ? (int)getenv('NOW_S') : time();   // unix SECONDS, exactly like hooks.php time()
$DATADIR = getenv('STAGING_DATA');

function make_api(): callable {
    global $DATADIR;
    return function (string $action, array $params) use ($DATADIR): array {
        if ($action !== 'GetClientsProducts') return ['result' => 'error', 'message' => 'unsupported'];
        $cid = (int)($params['clientid'] ?? 0);
        $f = $DATADIR . '/services_' . $cid . '.json';
        $products = is_file($f) ? json_decode((string)file_get_contents($f), true) : [];
        return ['result' => 'success', 'products' => ['product' => $products]];
    };
}

// Faithful copy of hooks.php terminow_ent_sender: build envelope, sign, POST, map 409 -> HELD.
function make_sender(): callable {
    $url = getenv('ENTITLEMENT_URL');
    $secret = trim((string)file_get_contents(getenv('ENTITLEMENT_SECRET_FILE')));
    return function (array $payload, array $meta) use ($url, $secret): bool {
        $ts = (string)time();
        $body = json_encode(['event' => $payload, 'idempotencyKey' => $meta['idempotencyKey'], 'seq' => $meta['seq']]);
        $sig = \TermiNow\Entitlement\sign($secret, $ts, $body);
        $curl = curl_init($url);
        curl_setopt_array($curl, [
            CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10,
            CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-TermiNow-Timestamp: ' . $ts, 'X-TermiNow-Signature: ' . $sig],
        ]);
        $resBody = (string)curl_exec($curl);
        $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);
        if ($status === 409) throw new \TermiNow\Entitlement\Failure(409, 'HELD', 'Held by Firebase: ' . substr($resBody, 0, 120));
        if ($status < 200 || $status >= 300) throw new \RuntimeException('HTTP ' . $status . ' ' . substr($resBody, 0, 120));
        return true;
    };
}

$cmd = $argv[1] ?? '';
switch ($cmd) {
    case 'map':
        // Synthetic catalog. Sandbox prices are HUMAN TNAI amounts (catalog_lib converts to base units).
        \TermiNow\Catalog\upsert($DB, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 101,
            'duration_days' => 30, 'store_visible' => 1, 'display_name' => 'TermiNow Premium (30 days)', 'sandbox_price_tnai' => '5'], $NOW);
        \TermiNow\Catalog\upsert($DB, ['feature_key' => 'terminow-cloud-backup', 'type' => 'addon', 'whmcs_addon_id' => 800,
            'parent_feature_key' => 'terminow-premium', 'duration_days' => 30, 'store_visible' => 1, 'display_name' => 'Cloud Backup', 'sandbox_price_tnai' => '2'], $NOW);
        \TermiNow\Catalog\upsert($DB, ['feature_key' => 'terminow-vault', 'type' => 'addon', 'whmcs_addon_id' => 900,
            'parent_feature_key' => 'terminow-premium', 'duration_days' => 30, 'store_visible' => 1, 'display_name' => 'Secrets Vault'], $NOW);
        echo "catalog mapped: " . count(\TermiNow\Catalog\all($DB)) . " entries\n";
        break;
    case 'sync': {
        $cid = (int)$argv[2]; $uids = explode(',', $argv[3]);
        $catalogByPid = \TermiNow\Catalog\by_product_id($DB);
        $enq = \TermiNow\Entitlement\sync_client($DB, make_api(), $cid, $uids, $catalogByPid, [], $NOW);
        $sum = \TermiNow\Entitlement\deliver_pending($DB, make_sender(), $NOW);
        echo "sync client $cid: enqueued=" . count($enq) . " delivered=" . json_encode($sum) . "\n";
        break;
    }
    case 'deliver':
        echo "deliver: " . json_encode(\TermiNow\Entitlement\deliver_pending($DB, make_sender(), $NOW)) . "\n";
        break;
    case 'reconcile': {
        $cid = (int)$argv[2]; $uid = $argv[3]; $apply = ($argv[4] ?? 'preview') === 'apply';
        $r = \TermiNow\Entitlement\reconcile($DB, make_api(), [$cid => [$uid]], \TermiNow\Catalog\by_product_id($DB), [], $NOW, $apply);
        echo "reconcile apply=" . ($apply ? '1' : '0') . ": drift=" . count($r['preview']) . " enqueued=" . $r['enqueued'] . "\n";
        foreach ($r['preview'] as $p) echo "  drift " . $p['featureKey'] . " want=" . $p['desiredState'] . "(active=" . ($p['desiredActive'] ? 1 : 0) . ") lastDelivered=" . ($p['lastDeliveredState'] ?? 'none') . "\n";
        if ($apply) \TermiNow\Entitlement\deliver_pending($DB, make_sender(), $NOW);
        break;
    }
    case 'retry': {
        \TermiNow\Entitlement\retry($DB, (int)$argv[2], $NOW);
        echo "retry row " . $argv[2] . " -> " . json_encode(\TermiNow\Entitlement\deliver_pending($DB, make_sender(), $NOW)) . "\n";
        break;
    }
    case 'events':
        foreach (\TermiNow\Entitlement\events($DB) as $e)
            echo sprintf("#%d uid=%s feat=%-22s seq=%d %-9s active=%d state=%-9s attempts=%d %s\n",
                $e['id'], $e['uid'], $e['feature_key'], $e['seq'], $e['status'], (int)$e['active'], $e['whmcs_state'], (int)$e['attempts'], $e['last_error'] ? '(' . substr($e['last_error'], 0, 40) . ')' : '');
        break;
    default:
        fwrite(STDERR, "unknown command: $cmd\n"); exit(2);
}
