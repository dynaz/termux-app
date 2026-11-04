<?php
/**
 * TermiNow WHMCS addon: central management of TermiNow packages and add-ons.
 *
 * Install: copy this folder to modules/addons/terminow_play, activate it in
 * System Settings → Addon Modules, set the secrets and the payment gateway, and give admins access.
 *
 * It is the hub for three concerns, each on its own admin tab:
 *   - Overview:     Google Play subscriptions (play_sync) and the Store Devnet sandbox mirror (store_sync).
 *   - Catalog:      the Store catalog — product/add-on mapping, feature keys, visibility, display
 *                   text and sandbox TNAI prices (catalog_lib; served to the Store by terminow/catalog.php).
 *   - Entitlements: the WHMCS -> Firebase lifecycle outbox (entitlement_lib; delivered by hooks.php).
 */

if (!defined('WHMCS')) die('This file can not be accessed directly');

require_once dirname(__DIR__, 3) . '/terminow/play_sync_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/store_sync_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/catalog_lib.php';
require_once dirname(__DIR__, 3) . '/terminow/catalog_whmcs.php';
require_once dirname(__DIR__, 3) . '/terminow/entitlement_lib.php';

use WHMCS\Database\Capsule;

function terminow_play_config()
{
    return [
        'name' => 'TermiNow',
        'description' => 'Central management of TermiNow packages and add-ons: Google Play and Store sync, the Store '
            . 'catalog mapping, and the WHMCS → Firebase entitlement lifecycle.',
        'version' => '1.2',
        'author' => 'TermiNow',
        'fields' => [
            'syncSecret' => [
                'FriendlyName' => 'Sync secret',
                'Type' => 'password',
                'Size' => '64',
                'Description' => 'The same value as the Firebase secret WHMCS_SYNC_SECRET, at least 32 characters',
            ],
            'paymentMethod' => [
                'FriendlyName' => 'Payment gateway of the orders',
                'Type' => 'text',
                'Size' => '30',
                'Description' => 'The module name of an active gateway, required by AddOrder. No invoice is ever created.',
            ],
            'storeSyncSecret' => [
                'FriendlyName' => 'Store sync secret',
                'Type' => 'password',
                'Size' => '64',
                'Description' => 'Shared secret for the TermiNow Store (Devnet sandbox) sync at terminow/store_sync.php. '
                    . 'Separate from the Google Play sync secret. At least 32 characters. The Store mirror never bills '
                    . 'or activates a WHMCS service.',
            ],
            'catalogSecret' => [
                'FriendlyName' => 'Catalog feed secret',
                'Type' => 'password',
                'Size' => '64',
                'Description' => 'Shared secret the Store signs its server-to-server catalog request with at '
                    . 'terminow/catalog.php. At least 32 characters. Read only; the feed exposes no billing data.',
            ],
            'entitlementSyncUrl' => [
                'FriendlyName' => 'Entitlement sync URL (Firebase)',
                'Type' => 'text',
                'Size' => '60',
                'Description' => 'HTTPS endpoint that writes entitlements/{uid} in Firebase. Leave blank to queue '
                    . 'lifecycle events as "held" until configured. WHMCS lifecycle changes are delivered here.',
            ],
            'entitlementSyncSecret' => [
                'FriendlyName' => 'Entitlement sync secret',
                'Type' => 'password',
                'Size' => '64',
                'Description' => 'Shared secret for signing the Firebase entitlement delivery, at least 32 characters.',
            ],
            'relaySecret' => [
                'FriendlyName' => 'MCP relay secret',
                'Type' => 'password',
                'Size' => '64',
                'Description' => 'Shared secret the TermiNow MCP relay sends as X-Relay-Secret to relay_check.php. '
                    . 'Use the same value as RELAY_BACKEND_SECRET on the relay server. At least 16 characters.',
            ],
        ],
    ];
}

function terminow_play_activate()
{
    try {
        $db = terminow_play_db();
        \TermiNow\PlaySync\migrate($db);
        \TermiNow\StoreSync\migrate($db);
        \TermiNow\Catalog\migrate($db);
        \TermiNow\Entitlement\migrate($db);
        return ['status' => 'success', 'description' => 'The TermiNow tables are ready'];
    } catch (\Throwable $e) {
        return ['status' => 'error', 'description' => 'Could not create the tables: ' . $e->getMessage()];
    }
}

function terminow_play_deactivate()
{
    // The records are kept, so that services stay linked to their TermiNow accounts
    return ['status' => 'success', 'description' => 'Deactivated. The records are kept.'];
}

function terminow_play_db(): PDO
{
    $db = Capsule::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    return $db;
}

function terminow_play_output($vars)
{
    $db = terminow_play_db();
    \TermiNow\PlaySync\migrate($db);
    \TermiNow\StoreSync\migrate($db);
    \TermiNow\Catalog\migrate($db);
    \TermiNow\Entitlement\migrate($db);
    $h = fn($text) => htmlspecialchars((string)$text, ENT_QUOTES, 'UTF-8');
    $link = $vars['modulelink'];
    $view = in_array($_REQUEST['view'] ?? '', ['overview', 'catalog', 'entitlements'], true) ? $_REQUEST['view'] : 'overview';

    echo '<style>.tnp-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch}.tnp-table td{word-break:break-word}'
        . '.tnp-nav{margin:0 0 16px;border-bottom:1px solid #ddd;padding-bottom:6px}.tnp-nav a{display:inline-block;padding:6px 14px;margin-right:4px;border-radius:4px 4px 0 0;text-decoration:none}'
        . '.tnp-nav a.active{background:#3a87ad;color:#fff;font-weight:600}'
        . '.tnp-form label{display:inline-block;min-width:150px;font-weight:600;vertical-align:top}.tnp-form .row{margin-bottom:8px}'
        . '@media(max-width:767px){.tnp-table thead{display:none}.tnp-table,.tnp-table tbody{display:block;width:100%}'
        . '.tnp-table tr{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px 8px;border:1px solid #ddd;border-radius:4px;margin-bottom:6px;padding:6px 8px;font-size:12px;line-height:1.3}'
        . '.tnp-table td{display:block;border:0!important;padding:0!important;background:none!important;text-align:left;min-width:0}'
        . '.tnp-table td[data-label]::before{content:attr(data-label);display:block;font-size:10px;text-transform:uppercase;letter-spacing:.02em;color:#888}'
        . '.tnp-table td.tnp-uid{grid-column:1/3;grid-row:1;font-weight:600;word-break:break-all}.tnp-table td.tnp-uid small{font-weight:400}'
        . '.tnp-table td.tnp-act{grid-column:3;grid-row:1;text-align:right}.tnp-table td.tnp-act .btn{padding:2px 8px;font-size:11px}'
        . '.tnp-table td.tnp-status{grid-column:1/-1;order:1}.tnp-table td.tnp-late{order:2}.tnp-form label{min-width:0;display:block}}</style>';

    $tab = function ($id, $title) use ($view, $link, $h) {
        return '<a class="' . ($view === $id ? 'active' : '') . '" href="' . $h($link . '&view=' . $id) . '">' . $h($title) . '</a>';
    };
    echo '<div class="tnp-nav">' . $tab('overview', 'Overview') . $tab('catalog', 'Catalog') . $tab('entitlements', 'Entitlements') . '</div>';

    if ($view === 'catalog') { terminow_play_view_catalog($db, $vars, $h, $link); return; }
    if ($view === 'entitlements') { terminow_play_view_entitlements($db, $vars, $h, $link); return; }
    terminow_play_view_overview($db, $vars, $h, $link);
}

function terminow_play_view_overview(PDO $db, $vars, callable $h, string $link): void
{
    if (($_POST['action'] ?? '') === 'retry' && check_token('WHMCS.admin.default', false)) {
        $uid = (string)($_POST['uid'] ?? '');
        try {
            $answer = \TermiNow\PlaySync\retry($db, fn($c, $p) => localAPI($c, $p), $uid, ['paymentMethod' => (string)($vars['paymentMethod'] ?? '')], time());
            echo '<div class="successbox">Synchronized: ' . $h($answer['status']) . '</div>';
        } catch (\TermiNow\PlaySync\Failure $e) {
            echo '<div class="errorbox">' . $h($e->errorCode . ': ' . $e->getMessage()) . '</div>';
        }
    }

    if (($_POST['action'] ?? '') === 'store_retry' && check_token('WHMCS.admin.default', false)) {
        $uid = (string)($_POST['uid'] ?? '');
        $featureKey = (string)($_POST['feature_key'] ?? '');
        try {
            // allowRealGrants is never passed, so retry stays a Devnet sandbox record: no WHMCS service.
            $answer = \TermiNow\StoreSync\retry($db, fn($c, $p) => localAPI($c, $p), $uid, $featureKey, [], time());
            echo '<div class="successbox">Store sync: ' . $h($answer['status']) . '</div>';
        } catch (\TermiNow\StoreSync\Failure $e) {
            echo '<div class="errorbox">' . $h($e->errorCode . ': ' . $e->getMessage()) . '</div>';
        }
    }

    $rows = $db->query('SELECT * FROM mod_terminow_play_sync ORDER BY updated_at DESC LIMIT 500')->fetchAll(PDO::FETCH_ASSOC);
    $live = count(array_filter($rows, fn($r) => !(int)$r['test'] && (int)$r['premium']));
    $test = count(array_filter($rows, fn($r) => (int)$r['test']));
    $free = count(array_filter($rows, fn($r) => !(int)$r['test'] && !(int)$r['premium']));
    echo '<p>Live Premium accounts: <b>' . $live . '</b> · Free members: <b>' . $free . '</b> · Test purchases (license testers, not revenue): <b>' . $test . '</b>. '
        . 'Google Play collects all payments; WHMCS keeps no invoices for these services.</p>';
    $relayReady = strlen((string)($vars['relaySecret'] ?? '')) >= 16;
    echo '<p>MCP relay secret: <b>' . ($relayReady ? 'configured' : 'not set') . '</b>'
        . ($relayReady ? '' : ' — set it to enable "Sign in with Google" on the MCP relay') . '.</p>';
    echo '<div class="tnp-wrap"><table class="datatable tnp-table" width="100%"><thead><tr><th>TermiNow account</th><th>Client</th><th>Service</th><th>Play plan</th>'
        . '<th>State</th><th>Expires (UTC)</th><th>Live/Test</th><th>Verified</th><th>Synced</th><th>Status</th><th></th></tr></thead><tbody>';
    foreach ($rows as $r) {
        $client = $r['client_id'] ? '<a href="clientssummary.php?userid=' . (int)$r['client_id'] . '">#' . (int)$r['client_id'] . '</a>' : '—';
        $service = $r['service_id'] ? '<a href="clientsservices.php?userid=' . (int)$r['client_id'] . '&id=' . (int)$r['service_id'] . '">#' . (int)$r['service_id'] . '</a>' : '—';
        echo '<tr>'
            . '<td class="tnp-uid" title="Firebase uid">' . $h($r['uid']) . ($r['email'] ? '<br><small>' . $h($r['email']) . '</small>' : '') . '</td>'
            . '<td data-label="Client">' . $client . '</td><td data-label="Service">' . $service . '</td>'
            . '<td data-label="Play plan">' . $h(($r['product_id'] ?? '—') . ' / ' . ($r['base_plan_id'] ?? '—')) . '</td>'
            . '<td data-label="State">' . $h(str_replace('SUBSCRIPTION_STATE_', '', (string)$r['state'])) . ((int)$r['auto_renew'] ? '' : '<br><small>no renewal</small>') . '</td>'
            . '<td data-label="Expires (UTC)">' . ($r['expires_at'] ? gmdate('Y-m-d H:i', intdiv((int)$r['expires_at'], 1000)) : '—') . '</td>'
            . '<td data-label="Live/Test">' . ((int)$r['test'] ? '<b>TEST</b>' : 'Live') . '</td>'
            . '<td class="tnp-late" data-label="Verified">' . ($r['last_verified_at'] ? gmdate('Y-m-d H:i', intdiv((int)$r['last_verified_at'], 1000)) : '—') . '</td>'
            . '<td class="tnp-late" data-label="Synced">' . ($r['last_sync_at'] ? gmdate('Y-m-d H:i', (int)$r['last_sync_at']) : '—') . '</td>'
            . '<td class="tnp-status" data-label="Status">' . $h($r['status']) . ($r['last_error'] ? '<br><small>' . $h($r['last_error']) . '</small>' : '') . '</td>'
            . '<td class="tnp-act"><form method="post" action="' . $h($link) . '">' . generate_token('form')
            . '<input type="hidden" name="action" value="retry"><input type="hidden" name="uid" value="' . $h($r['uid']) . '">'
            . '<button type="submit" class="btn btn-default btn-xs">Retry sync</button></form></td>'
            . '</tr>';
    }
    echo '</tbody></table></div>';

    // ---- TermiNow Store (Devnet sandbox) mirror ----
    $storeRows = $db->query('SELECT * FROM mod_terminow_store_sync ORDER BY updated_at DESC LIMIT 500')->fetchAll(PDO::FETCH_ASSOC);
    $recorded = count(array_filter($storeRows, fn($r) => $r['status'] === 'recorded'));
    $held = count(array_filter($storeRows, fn($r) => $r['status'] !== 'recorded'));
    echo '<h2 style="margin-top:28px">TermiNow Store (Devnet sandbox)</h2>';
    echo '<p>Sandbox entitlement mirror from the TermiNow Store. These records <b>never</b> bill and <b>never</b> create or '
        . 'activate a WHMCS service — they associate a verified client and track rights only. '
        . 'Recorded: <b>' . $recorded . '</b> · Needs attention (held/failed): <b>' . $held . '</b>.</p>';
    if ($storeRows === []) {
        echo '<p><i>No Store sync records yet.</i></p>';
    } else {
        echo '<div class="tnp-wrap"><table class="datatable tnp-table" width="100%"><thead><tr><th>TermiNow account</th><th>Client</th>'
            . '<th>Feature</th><th>Store product</th><th>WHMCS product / add-on</th><th>Active</th><th>Expires (UTC)</th>'
            . '<th>Synced</th><th>Status</th><th></th></tr></thead><tbody>';
        foreach ($storeRows as $r) {
            $client = $r['client_id'] ? '<a href="clientssummary.php?userid=' . (int)$r['client_id'] . '">#' . (int)$r['client_id'] . '</a>' : '—';
            $whmcsRef = trim(($r['whmcs_product_id'] ? 'P' . (int)$r['whmcs_product_id'] : '') . ' '
                . ($r['whmcs_addon_id'] ? 'A' . (int)$r['whmcs_addon_id'] : '')) ?: '—';
            $pending = $r['status'] !== 'recorded';
            echo '<tr>'
                . '<td class="tnp-uid" title="Firebase uid">' . $h($r['uid']) . ($r['email'] ? '<br><small>' . $h($r['email']) . '</small>' : '') . '</td>'
                . '<td data-label="Client">' . $client . '</td>'
                . '<td data-label="Feature">' . $h($r['feature_key']) . '</td>'
                . '<td data-label="Store product">' . $h($r['store_product_id'] ?? '—') . '</td>'
                . '<td data-label="WHMCS product / add-on">' . $h($whmcsRef) . '</td>'
                . '<td data-label="Active">' . ((int)$r['active'] ? 'yes' : 'no') . '</td>'
                . '<td data-label="Expires (UTC)">' . ($r['expires_at'] ? gmdate('Y-m-d H:i', (int)$r['expires_at']) : '—') . '</td>'
                . '<td class="tnp-late" data-label="Synced">' . ($r['last_sync_at'] ? gmdate('Y-m-d H:i', (int)$r['last_sync_at']) : '—') . '</td>'
                . '<td class="tnp-status" data-label="Status">' . ($pending ? '<b>' . $h($r['status']) . '</b>' : $h($r['status']))
                . ($r['last_error'] ? '<br><small>' . $h($r['last_error']) . '</small>' : '') . '</td>'
                . '<td class="tnp-act"><form method="post" action="' . $h($link) . '">' . generate_token('form')
                . '<input type="hidden" name="action" value="store_retry">'
                . '<input type="hidden" name="uid" value="' . $h($r['uid']) . '">'
                . '<input type="hidden" name="feature_key" value="' . $h($r['feature_key']) . '">'
                . '<button type="submit" class="btn btn-default btn-xs">Retry sync</button></form></td>'
                . '</tr>';
        }
        echo '</tbody></table></div>';
    }
}

/** The Store catalog tab: product/add-on mapping, feature keys, visibility, display text, sandbox price. */
function terminow_play_view_catalog(PDO $db, $vars, callable $h, string $link): void
{
    if (($_POST['action'] ?? '') === 'catalog_save' && check_token('WHMCS.admin.default', false)) {
        try {
            $id = ($_POST['id'] ?? '') !== '' ? (int)$_POST['id'] : null;
            \TermiNow\Catalog\upsert($db, [
                'feature_key' => $_POST['feature_key'] ?? '',
                'type' => $_POST['type'] ?? '',
                'whmcs_product_id' => ($_POST['whmcs_product_id'] ?? '') !== '' ? $_POST['whmcs_product_id'] : null,
                'whmcs_addon_id' => ($_POST['whmcs_addon_id'] ?? '') !== '' ? $_POST['whmcs_addon_id'] : null,
                'parent_feature_key' => $_POST['parent_feature_key'] ?? null,
                'duration_days' => $_POST['duration_days'] ?? null,
                'store_visible' => !empty($_POST['store_visible']),
                'display_name' => $_POST['display_name'] ?? null,
                'description' => $_POST['description'] ?? null,
                'display_name_i18n' => $_POST['display_name_i18n'] ?? null,
                'description_i18n' => $_POST['description_i18n'] ?? null,
                'sandbox_price_tnai' => $_POST['sandbox_price_tnai'] ?? null,
                'sort_order' => $_POST['sort_order'] ?? 0,
            ], time(), $id);
            echo '<div class="successbox">Catalog entry saved.</div>';
        } catch (\TermiNow\Catalog\Invalid $e) {
            echo '<div class="errorbox">Could not save:<ul><li>' . implode('</li><li>', array_map($h, $e->errors)) . '</li></ul></div>';
        }
    }
    if (($_POST['action'] ?? '') === 'catalog_delete' && check_token('WHMCS.admin.default', false)) {
        try {
            \TermiNow\Catalog\remove($db, (int)($_POST['id'] ?? 0));
            echo '<div class="successbox">Catalog entry deleted.</div>';
        } catch (\TermiNow\Catalog\Invalid $e) {
            echo '<div class="errorbox">' . $h($e->getMessage()) . '</div>';
        }
    }

    // Live Store-feed preview: exactly what terminow/catalog.php returns, with native WHMCS prices.
    if (($_POST['action'] ?? '') === 'catalog_preview' && check_token('WHMCS.admin.default', false)) {
        try {
            $feed = \TermiNow\Catalog\catalog_for_store($db, terminow_catalog_price_lookup());
            echo '<div class="infobox"><b>Store feed preview</b> — ' . count($feed['items']) . ' visible item(s), catalog version ' . (int)$feed['catalogVersion'] . '. This is what the Store receives.';
            if ($feed['items'] !== []) {
                echo '<table class="datatable" width="100%" style="margin-top:8px"><thead><tr><th>Feature</th><th>Type</th><th>Native WHMCS price</th><th>Sandbox TNAI</th><th>Available</th></tr></thead><tbody>';
                foreach ($feed['items'] as $it) {
                    $price = $it['price'] ? $h($it['price']['amount'] . ' ' . $it['price']['currency'] . ' / ' . $it['price']['billingCycle']) : '—';
                    echo '<tr><td>' . $h($it['featureKey']) . '</td><td>' . $h($it['type']) . '</td><td>' . $price . '</td>'
                        . '<td>' . ($it['sandboxPrice'] ? $h($it['sandboxPrice']['amount']) . ' TNAI' : '—') . '</td>'
                        . '<td>' . ($it['available'] ? 'yes' : '<b>no</b> — ' . $h($it['unavailableReason'])) . '</td></tr>';
                }
                echo '</tbody></table>';
            }
            echo '</div>';
        } catch (\Throwable $e) {
            echo '<div class="errorbox">Preview failed: ' . $h($e->getMessage()) . '</div>';
        }
    }

    $rows = \TermiNow\Catalog\all($db);
    $priceLookup = terminow_catalog_price_lookup();
    echo '<p>The catalog maps WHMCS products and product-add-ons to Store features. Native WHMCS prices and billing '
        . 'cycles are reused (served live by <code>terminow/catalog.php</code>); only Store concerns are stored here. '
        . 'Devnet sandbox TNAI prices are informational and never billed.</p>';
    echo '<form method="post" action="' . $h($link . '&view=catalog') . '" style="margin-bottom:12px">' . generate_token('form')
        . '<input type="hidden" name="action" value="catalog_preview"><button class="btn btn-default">Preview Store feed (live prices)</button></form>';

    echo '<div class="tnp-wrap"><table class="datatable tnp-table" width="100%"><thead><tr><th>Feature key</th><th>Type</th>'
        . '<th>WHMCS product / add-on</th><th>Native price</th><th>Parent</th><th>Visible</th><th>Display name</th><th>Sandbox TNAI</th><th></th></tr></thead><tbody>';
    foreach ($rows as $r) {
        $map = $r['whmcs_product_id'] !== null ? 'product #' . (int)$r['whmcs_product_id'] : 'add-on #' . (int)$r['whmcs_addon_id'];
        $live = $priceLookup($r);
        $priceCell = $live ? $h($live['amount'] . ' ' . $live['currency'] . ' / ' . $live['billingCycle']) : '<span style="color:#b94a48">unavailable</span>';
        echo '<tr>'
            . '<td class="tnp-uid">' . $h($r['feature_key']) . '</td>'
            . '<td data-label="Type">' . $h($r['type']) . '</td>'
            . '<td data-label="WHMCS">' . $h($map) . '</td>'
            . '<td data-label="Native price">' . $priceCell . '</td>'
            . '<td data-label="Parent">' . $h($r['parent_feature_key'] ?? '—') . '</td>'
            . '<td data-label="Visible">' . ((int)$r['store_visible'] ? 'yes' : 'no') . '</td>'
            . '<td data-label="Display name">' . $h($r['display_name'] ?? '—') . '</td>'
            . '<td data-label="Sandbox TNAI">' . $h($r['sandbox_price_tnai'] ?? '—') . '</td>'
            . '<td class="tnp-act"><a class="btn btn-default btn-xs" href="' . $h($link . '&view=catalog&edit=' . (int)$r['id']) . '">Edit</a> '
            . '<form method="post" action="' . $h($link . '&view=catalog') . '" style="display:inline" onsubmit="return confirm(\'Delete this catalog entry?\')">' . generate_token('form')
            . '<input type="hidden" name="action" value="catalog_delete"><input type="hidden" name="id" value="' . (int)$r['id'] . '">'
            . '<button type="submit" class="btn btn-danger btn-xs">Delete</button></form></td>'
            . '</tr>';
    }
    if ($rows === []) echo '<tr><td colspan="9"><i>No catalog entries yet.</i></td></tr>';
    echo '</tbody></table></div>';

    // Add / edit form
    $edit = null;
    if (($_GET['edit'] ?? '') !== '') $edit = \TermiNow\Catalog\get($db, (int)$_GET['edit']);
    $v = fn($k, $d = '') => $h($edit[$k] ?? $d);
    echo '<h3 style="margin-top:24px">' . ($edit ? 'Edit entry #' . (int)$edit['id'] : 'Add a catalog entry') . '</h3>';
    echo '<form class="tnp-form" method="post" action="' . $h($link . '&view=catalog') . '">' . generate_token('form')
        . '<input type="hidden" name="action" value="catalog_save">'
        . ($edit ? '<input type="hidden" name="id" value="' . (int)$edit['id'] . '">' : '')
        . '<div class="row"><label>Feature key</label><input name="feature_key" value="' . $v('feature_key') . '" placeholder="terminow-premium" required></div>'
        . '<div class="row"><label>Type</label><select name="type"><option value="premium"' . (($edit['type'] ?? '') === 'premium' ? ' selected' : '') . '>premium (subscription)</option>'
        . '<option value="addon"' . (($edit['type'] ?? '') === 'addon' ? ' selected' : '') . '>addon (feature)</option></select></div>'
        . '<div class="row"><label>WHMCS product id</label><input name="whmcs_product_id" value="' . $v('whmcs_product_id') . '" placeholder="e.g. 90"> <small>set this OR the add-on id</small></div>'
        . '<div class="row"><label>WHMCS add-on id</label><input name="whmcs_addon_id" value="' . $v('whmcs_addon_id') . '"></div>'
        . '<div class="row"><label>Parent feature</label><input name="parent_feature_key" value="' . $v('parent_feature_key') . '" placeholder="(for an add-on that needs Premium)"></div>'
        . '<div class="row"><label>Duration (days)</label><input name="duration_days" value="' . $v('duration_days') . '" placeholder="e.g. 30 — blank = permanent"></div>'
        . '<div class="row"><label>Store visible</label><input type="checkbox" name="store_visible" value="1"' . (!empty($edit['store_visible']) ? ' checked' : '') . '></div>'
        . '<div class="row"><label>Display name</label><input name="display_name" value="' . $v('display_name') . '" size="40"></div>'
        . '<div class="row"><label>Description</label><textarea name="description" rows="2" cols="50">' . $v('description') . '</textarea></div>'
        . '<div class="row"><label>Display name i18n</label><input name="display_name_i18n" value="' . $v('display_name_i18n') . '" size="50" placeholder=\'{"th":"...","en":"..."}\'></div>'
        . '<div class="row"><label>Description i18n</label><input name="description_i18n" value="' . $v('description_i18n') . '" size="50" placeholder=\'{"th":"..."}\'></div>'
        . '<div class="row"><label>Sandbox TNAI price</label><input name="sandbox_price_tnai" value="' . $v('sandbox_price_tnai') . '" placeholder="e.g. 50"></div>'
        . '<div class="row"><label>Sort order</label><input name="sort_order" value="' . $v('sort_order', '0') . '" size="4"></div>'
        . '<div class="row"><button type="submit" class="btn btn-primary">' . ($edit ? 'Save changes' : 'Add entry') . '</button>'
        . ($edit ? ' <a class="btn btn-default" href="' . $h($link . '&view=catalog') . '">Cancel</a>' : '') . '</div>'
        . '</form>';
}

/** The Entitlements tab: the WHMCS → Firebase outbox, delivery status, retries and reconciliation. */
function terminow_play_view_entitlements(PDO $db, $vars, callable $h, string $link): void
{
    $url = trim((string)($vars['entitlementSyncUrl'] ?? ''));
    $secret = (string)($vars['entitlementSyncSecret'] ?? '');
    $sender = terminow_play_entitlement_sender($url, $secret);
    $configured = $sender !== null;

    if (($_POST['action'] ?? '') === 'ent_retry' && check_token('WHMCS.admin.default', false)) {
        try {
            \TermiNow\Entitlement\retry($db, (int)($_POST['id'] ?? 0), time());
            $s = \TermiNow\Entitlement\deliver_pending($db, $sender, time());
            echo '<div class="successbox">Re-queued. Delivered ' . (int)$s['delivered'] . ', held ' . (int)$s['held'] . ', failed ' . (int)$s['failed'] . '.</div>';
        } catch (\TermiNow\Entitlement\Failure $e) {
            echo '<div class="errorbox">' . $h($e->errorCode . ': ' . $e->getMessage()) . '</div>';
        }
    }
    if (($_POST['action'] ?? '') === 'ent_deliver' && check_token('WHMCS.admin.default', false)) {
        $s = \TermiNow\Entitlement\deliver_pending($db, $sender, time());
        echo '<div class="successbox">Delivered ' . (int)$s['delivered'] . ', held ' . (int)$s['held'] . ', failed ' . (int)$s['failed'] . ', blocked ' . (int)$s['blocked'] . '.</div>';
    }

    $applyReconcile = ($_POST['action'] ?? '') === 'ent_reconcile_apply' && check_token('WHMCS.admin.default', false);
    $previewReconcile = ($_POST['action'] ?? '') === 'ent_reconcile_preview' && check_token('WHMCS.admin.default', false);
    if ($applyReconcile || $previewReconcile) {
        $catalog = \TermiNow\Catalog\by_product_id($db);
        $map = terminow_play_linked_client_uids($db);
        $excluded = array_merge(\TermiNow\PlaySync\play_service_ids($db), \TermiNow\StoreSync\store_service_ids($db));
        $api = fn(string $c, array $p) => localAPI($c, $p);
        try {
            $result = \TermiNow\Entitlement\reconcile($db, $api, $map, $catalog, $excluded, time(), $applyReconcile);
            if ($applyReconcile) {
                $s = \TermiNow\Entitlement\deliver_pending($db, $sender, time());
                echo '<div class="successbox">Reconciliation applied: ' . (int)$result['enqueued'] . ' correction(s) enqueued, delivered ' . (int)$s['delivered'] . ', held ' . (int)$s['held'] . '.</div>';
            } else {
                echo '<div class="infobox"><b>Reconciliation preview</b> — ' . count($result['preview']) . ' entitlement(s) differ from the last delivered state. Nothing has been changed.';
                if ($result['preview'] !== []) {
                    echo '<table class="datatable" width="100%" style="margin-top:8px"><thead><tr><th>Account</th><th>Client</th><th>Feature</th><th>WHMCS now</th><th>Last delivered</th><th>Queued?</th></tr></thead><tbody>';
                    foreach ($result['preview'] as $p)
                        echo '<tr><td style="word-break:break-all">' . $h($p['uid']) . '</td><td>#' . (int)$p['clientId'] . '</td><td>' . $h($p['featureKey']) . '</td>'
                            . '<td>' . ($p['desiredActive'] ? 'active' : 'inactive') . ' (' . $h($p['desiredState']) . ')</td>'
                            . '<td>' . ($p['lastDeliveredActive'] === null ? '—' : ($p['lastDeliveredActive'] ? 'active' : 'inactive')) . ' (' . $h($p['lastDeliveredState'] ?? '—') . ')</td>'
                            . '<td>' . ($p['pending'] ? 'pending' : 'no') . '</td></tr>';
                    echo '</tbody></table>';
                    echo '<form method="post" action="' . $h($link . '&view=entitlements') . '" style="margin-top:8px">' . generate_token('form')
                        . '<input type="hidden" name="action" value="ent_reconcile_apply">'
                        . '<button type="submit" class="btn btn-warning">Apply these ' . count($result['preview']) . ' correction(s)</button></form>';
                }
                echo '</div>';
            }
        } catch (\Throwable $e) {
            echo '<div class="errorbox">Reconciliation failed: ' . $h($e->getMessage()) . '</div>';
        }
    }

    echo '<p>WHMCS is authoritative for services billed here. Lifecycle changes (activation, renewal, suspension, '
        . 'cancellation, termination, expiry) are delivered to Firebase so the app\'s rights follow WHMCS. '
        . 'Delivery is signed, idempotent, ordered and retried; <code>premium.php</code> polling still works as a fallback. '
        . 'Google Play, Store sandbox and WHMCS are separate sources — removing one never drops another\'s rights.</p>';
    echo '<p>Firebase delivery: <b>' . ($configured ? 'configured' : 'NOT configured') . '</b>'
        . ($configured ? '' : ' — set the entitlement sync URL (https) and a ≥32-char secret; events wait as "held" until then') . '.</p>';

    $all = \TermiNow\Entitlement\events($db);
    $by = fn($s) => count(array_filter($all, fn($r) => $r['status'] === $s));
    echo '<p>Events — pending: <b>' . $by('pending') . '</b> · held: <b>' . $by('held') . '</b> · failed: <b>' . $by('failed') . '</b> · delivered: <b>' . $by('delivered') . '</b>.</p>';

    echo '<form method="post" action="' . $h($link . '&view=entitlements') . '" style="display:inline">' . generate_token('form')
        . '<input type="hidden" name="action" value="ent_deliver"><button class="btn btn-default">Deliver pending now</button></form> ';
    echo '<form method="post" action="' . $h($link . '&view=entitlements') . '" style="display:inline">' . generate_token('form')
        . '<input type="hidden" name="action" value="ent_reconcile_preview"><button class="btn btn-default">Preview reconciliation</button></form>';

    echo '<div class="tnp-wrap" style="margin-top:12px"><table class="datatable tnp-table" width="100%"><thead><tr><th>Account</th><th>Client</th><th>Feature</th><th>Source</th>'
        . '<th>Active</th><th>State</th><th>Seq</th><th>Status</th><th>Attempts</th><th>Updated</th><th></th></tr></thead><tbody>';
    foreach ($all as $r) {
        $attn = in_array($r['status'], ['failed', 'held'], true);
        echo '<tr>'
            . '<td class="tnp-uid">' . $h($r['uid']) . '</td>'
            . '<td data-label="Client">' . ($r['client_id'] ? '#' . (int)$r['client_id'] : '—') . '</td>'
            . '<td data-label="Feature">' . $h($r['feature_key']) . '</td>'
            . '<td data-label="Source">' . $h($r['source']) . '</td>'
            . '<td data-label="Active">' . ((int)$r['active'] ? 'yes' : 'no') . '</td>'
            . '<td data-label="State">' . $h($r['whmcs_state'] ?? '—') . '</td>'
            . '<td data-label="Seq">' . (int)$r['seq'] . '</td>'
            . '<td class="tnp-status" data-label="Status">' . ($attn ? '<b>' . $h($r['status']) . '</b>' : $h($r['status']))
            . ($r['last_error'] ? '<br><small>' . $h($r['last_error']) . '</small>' : '') . '</td>'
            . '<td data-label="Attempts">' . (int)$r['attempts'] . '</td>'
            . '<td class="tnp-late" data-label="Updated">' . ($r['updated_at'] ? gmdate('Y-m-d H:i', (int)$r['updated_at']) : '—') . '</td>'
            . '<td class="tnp-act">' . ($attn ? '<form method="post" action="' . $h($link . '&view=entitlements') . '">' . generate_token('form')
                . '<input type="hidden" name="action" value="ent_retry"><input type="hidden" name="id" value="' . (int)$r['id'] . '">'
                . '<button type="submit" class="btn btn-default btn-xs">Retry</button></form>' : '') . '</td>'
            . '</tr>';
    }
    if ($all === []) echo '<tr><td colspan="11"><i>No entitlement events yet.</i></td></tr>';
    echo '</tbody></table></div>';
}

/** [clientId => [uid, ...]] across the Play and Store link tables, for reconciliation. */
function terminow_play_linked_client_uids(PDO $db): array
{
    $map = [];
    foreach (['mod_terminow_play_sync', 'mod_terminow_store_sync'] as $table) {
        try {
            foreach ($db->query("SELECT DISTINCT client_id, uid FROM $table WHERE client_id IS NOT NULL")->fetchAll(PDO::FETCH_ASSOC) as $r)
                $map[(int)$r['client_id']][(string)$r['uid']] = true;
        } catch (\Throwable $e) {}
    }
    $out = [];
    foreach ($map as $cid => $uids) $out[$cid] = array_keys($uids);
    return $out;
}

/** The signed Firebase deliverer for the admin actions (settings are already decrypted in $vars). */
function terminow_play_entitlement_sender(string $url, string $secret): ?callable
{
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
            CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-TermiNow-Timestamp: ' . $ts, 'X-TermiNow-Signature: ' . $sig],
        ]);
        $resBody = (string)curl_exec($curl);
        $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);
        if ($status >= 200 && $status < 300) return true;
        if ($status === 409) throw new \TermiNow\Entitlement\Failure(409, 'HELD', 'Held by Firebase: ' . substr($resBody, 0, 120));
        throw new \RuntimeException('delivery HTTP ' . $status);
    };
}

/**
 * The client area page index.php?m=terminow_play: a one-time code that links this client to a
 * TermiNow account. WHMCS asks for the login and checks the form token, as on its own pages.
 */
function terminow_play_clientarea($vars)
{
    $clientId = 0;
    if (class_exists('\WHMCS\Authentication\CurrentUser')) {
        $client = (new \WHMCS\Authentication\CurrentUser())->client();
        if ($client) $clientId = (int)$client->id;
    } elseif (!empty($_SESSION['uid'])) {
        $clientId = (int)$_SESSION['uid'];
    }
    $code = null;
    if ($clientId > 0 && ($_SERVER['REQUEST_METHOD'] ?? '') === 'POST' && ($_POST['action'] ?? '') === 'code') {
        $db = terminow_play_db();
        \TermiNow\PlaySync\migrate($db);
        $code = \TermiNow\PlaySync\create_link_code($db, $clientId, time());
    }
    return [
        'pagetitle' => 'Link TermiNow',
        'breadcrumb' => ['index.php?m=terminow_play' => 'Link TermiNow'],
        'templatefile' => 'link',
        'requirelogin' => true,
        'forcessl' => true,
        'vars' => ['code' => $code, 'seconds' => \TermiNow\PlaySync\LINK_CODE_SECONDS],
    ];
}
