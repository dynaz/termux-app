<?php
/**
 * TermiNow WHMCS -> Firebase entitlement lifecycle hooks.
 *
 * When a mapped WHMCS service changes (create, edit, suspend, unsuspend, terminate, cancel, package
 * change) this re-reads the authoritative WHMCS state for the client and enqueues a signed delivery
 * to Firebase so the app's rights follow WHMCS. The daily cron delivers anything pending and runs a
 * periodic reconciliation. All of it is additive and defensive: a hook never throws into WHMCS, and
 * premium.php polling keeps working as the compatibility path.
 *
 * Only reads are made against WHMCS (GetClientsProducts, and a tblhosting lookup to map a service to
 * its client). No WHMCS core row is written. The Store's Devnet sandbox mirror is NOT a source here.
 */

if (!defined('WHMCS')) die('This file cannot be accessed directly');

require_once dirname(__DIR__, 3) . '/terminow/entitlement_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/catalog_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/play_sync_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/store_sync_lib.php';

use WHMCS\Database\Capsule;

function terminow_ent_db(): PDO
{
    $db = Capsule::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    return $db;
}

function terminow_ent_settings(): array
{
    $settings = [];
    foreach (Capsule::table('tbladdonmodules')->where('module', 'terminow_play')->get(['setting', 'value']) as $row)
        $settings[$row->setting] = $row->value;
    return $settings;
}

/** All Firebase uids linked to a WHMCS client, across the Play and Store link tables. */
function terminow_ent_uids_for_client(PDO $db, int $clientId): array
{
    $uids = [];
    foreach (['mod_terminow_play_sync', 'mod_terminow_store_sync'] as $table) {
        try {
            $st = $db->prepare("SELECT DISTINCT uid FROM $table WHERE client_id = ?");
            $st->execute([$clientId]);
            foreach ($st->fetchAll(PDO::FETCH_COLUMN) as $uid) $uids[(string)$uid] = true;
        } catch (\Throwable $e) {
            // table may not exist yet
        }
    }
    return array_keys($uids);
}

/** Service ids that belong to another source (Google Play, Store mirror) and must be excluded. */
function terminow_ent_excluded(PDO $db): array
{
    return array_merge(\TermiNow\PlaySync\play_service_ids($db), \TermiNow\StoreSync\store_service_ids($db));
}

/**
 * The signed Firebase deliverer, or null when delivery is not configured (events then wait as
 * 'held' and are shown in the admin page). The credential stays server-side.
 */
function terminow_ent_sender(array $settings): ?callable
{
    $url = trim((string)($settings['entitlementSyncUrl'] ?? ''));
    $secret = (string)($settings['entitlementSyncSecret'] ?? '');
    try { $secret = $secret === '' ? '' : decrypt($secret); } catch (\Throwable $e) { $secret = ''; }
    if ($url === '' || strlen($secret) < 32 || !preg_match('#^https://#', $url)) return null;
    return function (array $payload, array $meta) use ($url, $secret): bool {
        $body = json_encode(['event' => $payload, 'idempotencyKey' => $meta['idempotencyKey'], 'seq' => $meta['seq']]);
        $ts = (string)time();
        $sig = \TermiNow\Entitlement\sign($secret, $ts, $body);
        $curl = curl_init($url);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $body,
            CURLOPT_TIMEOUT => 8,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_HTTPHEADER => [
                'Content-Type: application/json',
                'X-TermiNow-Timestamp: ' . $ts,
                'X-TermiNow-Signature: ' . $sig,
            ],
        ]);
        $resBody = (string)curl_exec($curl);
        $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        $err = curl_error($curl);
        curl_close($curl);
        if ($status >= 200 && $status < 300) return true;
        // 409 = the writer is holding this event (e.g. no verified client<->uid link yet): retryable,
        // not a failure. deliver_pending re-queues it as 'held' and tries again later.
        if ($status === 409) throw new \TermiNow\Entitlement\Failure(409, 'HELD', 'Held by Firebase: ' . substr($resBody, 0, 120));
        throw new \RuntimeException('delivery HTTP ' . $status . ($err !== '' ? ' ' . $err : ''));
    };
}

/** Recompute and enqueue a client's entitlements, then try to deliver what is pending. */
function terminow_ent_sync_client(int $clientId): void
{
    try {
        $settings = terminow_ent_settings();
        if (($settings['version'] ?? '') === '') return;
        $db = terminow_ent_db();
        \TermiNow\Catalog\migrate($db);
        \TermiNow\Entitlement\migrate($db);
        $catalog = \TermiNow\Catalog\by_product_id($db);
        if ($catalog === []) return;                       // nothing mapped: nothing to sync
        $uids = terminow_ent_uids_for_client($db, $clientId);
        if ($uids === []) return;                          // no linked TermiNow account
        $api = fn(string $c, array $p) => localAPI($c, $p);
        \TermiNow\Entitlement\sync_client($db, $api, $clientId, $uids, $catalog, terminow_ent_excluded($db), time());
        \TermiNow\Entitlement\deliver_pending($db, terminow_ent_sender($settings), time());
    } catch (\Throwable $e) {
        logActivity('TermiNow entitlement sync error (client ' . $clientId . '): ' . $e->getMessage());
    }
}

/** Resolve a service id to its WHMCS client (read-only lookup). */
function terminow_ent_client_for_service(?int $serviceId): ?int
{
    if (!$serviceId) return null;
    try {
        $userid = Capsule::table('tblhosting')->where('id', $serviceId)->value('userid');
        return $userid ? (int)$userid : null;
    } catch (\Throwable $e) {
        return null;
    }
}

/** Extract a client id from the varied hook payloads and sync it. */
function terminow_ent_from_vars($vars): void
{
    $p = is_array($vars['params'] ?? null) ? $vars['params'] : $vars;
    $clientId = (int)($p['userid'] ?? $vars['userid'] ?? 0);
    if ($clientId <= 0) {
        $serviceId = (int)($p['serviceid'] ?? $vars['serviceid'] ?? $vars['relid'] ?? 0);
        $clientId = (int)(terminow_ent_client_for_service($serviceId) ?? 0);
    }
    if ($clientId > 0) terminow_ent_sync_client($clientId);
}

foreach (['AfterModuleCreate', 'AfterModuleSuspend', 'AfterModuleUnsuspend', 'AfterModuleTerminate',
          'AfterModuleChangePackage', 'ServiceEdit', 'ServiceDelete', 'AfterServiceUpgrade',
          'CancellationRequestSubmit', 'ServiceUnsuspend', 'ServiceSuspend'] as $event) {
    add_hook($event, 1, 'terminow_ent_from_vars');
}

/** Daily: deliver everything pending, then reconcile authoritative state for all linked clients. */
add_hook('DailyCronJob', 1, function () {
    try {
        $settings = terminow_ent_settings();
        if (($settings['version'] ?? '') === '') return;
        $db = terminow_ent_db();
        \TermiNow\Catalog\migrate($db);
        \TermiNow\Entitlement\migrate($db);
        $catalog = \TermiNow\Catalog\by_product_id($db);
        if ($catalog === []) return;
        $sender = terminow_ent_sender($settings);
        \TermiNow\Entitlement\deliver_pending($db, $sender, time());

        // Reconcile every linked client (periodic drift correction), then deliver the corrections.
        $clientUids = [];
        foreach (['mod_terminow_play_sync', 'mod_terminow_store_sync'] as $table) {
            try {
                foreach ($db->query("SELECT DISTINCT client_id, uid FROM $table WHERE client_id IS NOT NULL")->fetchAll(PDO::FETCH_ASSOC) as $r)
                    $clientUids[(int)$r['client_id']][(string)$r['uid']] = true;
            } catch (\Throwable $e) {}
        }
        $map = [];
        foreach ($clientUids as $cid => $uids) $map[$cid] = array_keys($uids);
        if ($map !== []) {
            $api = fn(string $c, array $p) => localAPI($c, $p);
            \TermiNow\Entitlement\reconcile($db, $api, $map, $catalog, terminow_ent_excluded($db), time(), true);
            \TermiNow\Entitlement\deliver_pending($db, $sender, time());
        }
    } catch (\Throwable $e) {
        logActivity('TermiNow entitlement daily sync error: ' . $e->getMessage());
    }
});
