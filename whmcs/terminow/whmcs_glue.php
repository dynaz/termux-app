<?php
/**
 * What premium.php, vault.php and play.php share inside WHMCS: whether an account has Premium,
 * from an active service of WHMCS (accmove.com) or a subscription of Google Play.
 *
 * Needs lib.php and play_lib.php, and init.php of WHMCS loaded.
 */

/**
 * WHMCS product ids that are TermiNow Premium, in addition to every product whose name or
 * group name contains "TermiNow". 90 is MobiClaude Premium, which includes TermiNow Premium.
 */
const TERMINOW_PREMIUM_PRODUCT_IDS = [90];

/**
 * The key of the Google service account that checks Play purchases, outside the web root. It
 * only needs the Play Console permissions "View financial data" and "Manage orders and
 * subscriptions". Without it, Play purchases are not set up and only WHMCS counts.
 */
const TERMINOW_PLAY_KEY_FILE = '/home/accmovecom/.terminow/play-key.json';

function terminow_db(): PDO
{
    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    return $db;
}

function terminow_google(): ?\TermiNow\Play\Google
{
    return \TermiNow\Play\google_from_key_file(TERMINOW_PLAY_KEY_FILE, sys_get_temp_dir() . '/terminow-play-token.json');
}

/** Premium of WHMCS for a verified email, like decide() answers it. */
function terminow_whmcs_premium(string $email): array
{
    $client = localAPI('GetClientsDetails', ['email' => $email, 'stats' => false]);
    // No client with this email: not Premium. The same answer as for a client without it.
    if (($client['result'] ?? '') !== 'success' || empty($client['client']['id'] ?? $client['userid'] ?? null)) return ['premium' => false];
    $products = localAPI('GetClientsProducts', ['clientid' => (int)($client['client']['id'] ?? $client['userid']), 'limitnum' => 250]);
    if (($products['result'] ?? '') !== 'success') throw new RuntimeException('WHMCS failed');
    $list = $products['products']['product'] ?? [];
    return \TermiNow\Premium\decide(is_array($list) ? $list : [], TERMINOW_PREMIUM_PRODUCT_IDS);
}

/**
 * Premium from both sources, for the claims of a verified Firebase ID token. Run
 * \TermiNow\Play\migrate() first, outside a transaction, as MySQL commits on CREATE TABLE.
 */
function terminow_premium(PDO $db, array $claims): array
{
    $whmcs = terminow_whmcs_premium((string)$claims['email']);
    $play = \TermiNow\Play\premium($db, terminow_google(), (string)$claims['sub'], time());
    return \TermiNow\Play\combine($whmcs, $play);
}
