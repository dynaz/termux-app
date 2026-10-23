<?php
/**
 * What premium.php, vault.php and play_sync.php share inside WHMCS. Needs lib.php and
 * play_sync_lib.php, and init.php of WHMCS loaded.
 *
 * Premium has two sources: an active TermiNow service in WHMCS (accmove.com), and a Google Play
 * subscription, which Firebase verifies and keeps in Firestore (entitlements/{uid}). The
 * services that WHMCS keeps for Google Play subscribers are not a source of their own.
 */

/**
 * WHMCS product ids that are TermiNow Premium, in addition to every product whose name or
 * group name contains "TermiNow". 90 is MobiClaude Premium, which includes TermiNow Premium.
 */
const TERMINOW_PREMIUM_PRODUCT_IDS = [90];

function terminow_db(): PDO
{
    $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    return $db;
}

/** Premium of WHMCS for a verified email, like decide() answers it, without Google Play services. */
function terminow_whmcs_premium(PDO $db, string $email): array
{
    $client = localAPI('GetClientsDetails', ['email' => $email, 'stats' => false]);
    // No client with this email: not Premium. The same answer as for a client without it.
    if (($client['result'] ?? '') !== 'success' || empty($client['client']['id'] ?? $client['userid'] ?? null)) return ['premium' => false];
    $products = localAPI('GetClientsProducts', ['clientid' => (int)($client['client']['id'] ?? $client['userid']), 'limitnum' => 250]);
    if (($products['result'] ?? '') !== 'success') throw new RuntimeException('WHMCS failed');
    $list = $products['products']['product'] ?? [];
    $playServices = \TermiNow\PlaySync\play_service_ids($db);
    $list = array_values(array_filter(is_array($list) ? $list : [], fn($p) => !in_array((int)($p['id'] ?? 0), $playServices, true)));
    return \TermiNow\Premium\decide($list, TERMINOW_PREMIUM_PRODUCT_IDS) + ['source' => 'accmove'];
}

/**
 * Whether the Firestore entitlement of the user says Premium, read with their own ID token,
 * which the security rules allow for their own document only. Fails closed.
 */
function terminow_firebase_premium(string $idToken, string $uid): bool
{
    $url = 'https://firestore.googleapis.com/v1/projects/' . \TermiNow\Premium\FIREBASE_PROJECT_ID .
        '/databases/(default)/documents/entitlements/' . rawurlencode($uid);
    $curl = curl_init($url);
    curl_setopt_array($curl, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 8,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_HTTPHEADER => ['Authorization: Bearer ' . $idToken],
    ]);
    $body = curl_exec($curl);
    $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    if ($status !== 200 || !is_string($body)) return false;
    return \TermiNow\Premium\firestore_premium(json_decode($body, true), time() * 1000);
}

/** Premium from both sources, for a verified Firebase ID token and its claims. */
function terminow_premium(PDO $db, array $claims, string $idToken): bool
{
    try {
        if (terminow_whmcs_premium($db, (string)$claims['email'])['premium']) return true;
    } catch (\Throwable $e) {
        // Google Play may still say Premium
    }
    return terminow_firebase_premium($idToken, (string)$claims['sub']);
}
