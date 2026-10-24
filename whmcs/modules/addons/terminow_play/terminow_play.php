<?php
/**
 * TermiNow Google Play: the WHMCS addon of the Google Play subscriptions of TermiNow.
 *
 * Install: copy this folder to modules/addons/terminow_play, activate it in
 * System Settings → Addon Modules, set the sync secret (the same value as the Firebase secret
 * WHMCS_SYNC_SECRET) and the payment gateway put on the orders, and give admins access.
 * The endpoint is terminow/play_sync.php, the logic terminow/play_sync_lib.php.
 */

if (!defined('WHMCS')) die('This file can not be accessed directly');

require_once dirname(__DIR__, 3) . '/terminow/play_sync_lib.php';

use WHMCS\Database\Capsule;

function terminow_play_config()
{
    return [
        'name' => 'TermiNow',
        'description' => 'TermiNow integration: Google Play subscriptions (clients and services) and the '
            . 'shared secret the TermiNow MCP relay uses to verify Premium. Google Play collects the payments; '
            . 'the product must have the pay type Free so that WHMCS never invoices them.',
        'version' => '1.1',
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
        $db = Capsule::connection()->getPdo();
        $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        \TermiNow\PlaySync\migrate($db);
        return ['status' => 'success', 'description' => 'The tables of TermiNow Google Play are ready'];
    } catch (\Throwable $e) {
        return ['status' => 'error', 'description' => 'Could not create the tables: ' . $e->getMessage()];
    }
}

function terminow_play_deactivate()
{
    // The records are kept, so that services stay linked to their TermiNow accounts
    return ['status' => 'success', 'description' => 'Deactivated. The records are kept.'];
}

function terminow_play_output($vars)
{
    $db = Capsule::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\PlaySync\migrate($db);
    $h = fn($text) => htmlspecialchars((string)$text, ENT_QUOTES, 'UTF-8');
    $link = $vars['modulelink'];

    if (($_POST['action'] ?? '') === 'retry' && check_token('WHMCS.admin.default', false)) {
        $uid = (string)($_POST['uid'] ?? '');
        try {
            $answer = \TermiNow\PlaySync\retry($db, fn($c, $p) => localAPI($c, $p), $uid, ['paymentMethod' => (string)($vars['paymentMethod'] ?? '')], time());
            echo '<div class="successbox">Synchronized: ' . $h($answer['status']) . '</div>';
        } catch (\TermiNow\PlaySync\Failure $e) {
            echo '<div class="errorbox">' . $h($e->errorCode . ': ' . $e->getMessage()) . '</div>';
        }
    }

    $relayReady = strlen((string)($vars['relaySecret'] ?? '')) >= 16;
    echo '<p>MCP relay secret: <b>' . ($relayReady ? 'configured' : 'not set') . '</b>'
        . ($relayReady ? '' : ' — set it in Setup → Addon Modules → TermiNow to enable "Sign in with Google" on the MCP relay') . '.</p>';

    $rows = $db->query('SELECT * FROM mod_terminow_play_sync ORDER BY updated_at DESC LIMIT 500')->fetchAll(PDO::FETCH_ASSOC);
    $live = count(array_filter($rows, fn($r) => !(int)$r['test'] && (int)$r['premium']));
    $test = count(array_filter($rows, fn($r) => (int)$r['test']));
    $free = count(array_filter($rows, fn($r) => !(int)$r['test'] && !(int)$r['premium']));
    echo '<p>Live Premium accounts: <b>' . $live . '</b> · Free members: <b>' . $free . '</b> · Test purchases (license testers, not revenue): <b>' . $test . '</b>. '
        . 'Google Play collects all payments; WHMCS keeps no invoices for these services.</p>';
    echo '<table class="datatable" width="100%"><tr><th>TermiNow account</th><th>Client</th><th>Service</th><th>Play plan</th>'
        . '<th>State</th><th>Expires (UTC)</th><th>Live/Test</th><th>Verified</th><th>Synced</th><th>Status</th><th></th></tr>';
    foreach ($rows as $r) {
        $client = $r['client_id'] ? '<a href="clientssummary.php?userid=' . (int)$r['client_id'] . '">#' . (int)$r['client_id'] . '</a>' : '—';
        $service = $r['service_id'] ? '<a href="clientsservices.php?userid=' . (int)$r['client_id'] . '&id=' . (int)$r['service_id'] . '">#' . (int)$r['service_id'] . '</a>' : '—';
        echo '<tr>'
            . '<td title="Firebase uid">' . $h($r['uid']) . ($r['email'] ? '<br><small>' . $h($r['email']) . '</small>' : '') . '</td>'
            . '<td>' . $client . '</td><td>' . $service . '</td>'
            . '<td>' . $h(($r['product_id'] ?? '—') . ' / ' . ($r['base_plan_id'] ?? '—')) . '</td>'
            . '<td>' . $h(str_replace('SUBSCRIPTION_STATE_', '', (string)$r['state'])) . ((int)$r['auto_renew'] ? '' : '<br><small>no renewal</small>') . '</td>'
            . '<td>' . ($r['expires_at'] ? gmdate('Y-m-d H:i', intdiv((int)$r['expires_at'], 1000)) : '—') . '</td>'
            . '<td>' . ((int)$r['test'] ? '<b>TEST</b>' : 'Live') . '</td>'
            . '<td>' . ($r['last_verified_at'] ? gmdate('Y-m-d H:i', intdiv((int)$r['last_verified_at'], 1000)) : '—') . '</td>'
            . '<td>' . ($r['last_sync_at'] ? gmdate('Y-m-d H:i', (int)$r['last_sync_at']) : '—') . '</td>'
            . '<td>' . $h($r['status']) . ($r['last_error'] ? '<br><small>' . $h($r['last_error']) . '</small>' : '') . '</td>'
            . '<td><form method="post" action="' . $h($link) . '">' . generate_token('form')
            . '<input type="hidden" name="action" value="retry"><input type="hidden" name="uid" value="' . $h($r['uid']) . '">'
            . '<button type="submit" class="btn btn-default btn-xs">Retry sync</button></form></td>'
            . '</tr>';
    }
    echo '</table>';
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
        $db = Capsule::connection()->getPdo();
        $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
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
