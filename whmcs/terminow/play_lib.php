<?php
/**
 * TermiNow Premium bought with Google Play: the logic, without WHMCS, so that it can be tested
 * on its own.
 *
 * The app sends the purchase token of a Play subscription. The token is checked with the Google
 * Play Developer API, and it must have been bought for this TermiNow account: the app passes a
 * hash of the Firebase uid as the obfuscated account id of the purchase, which Google returns.
 * A token belongs to the first account that registers it, so it can not be shared. The server
 * then acknowledges the purchase, which Google requires within three days.
 *
 * Play subscriptions are kept in mod_terminow_play and checked with Google again once a day, and
 * whenever they expire, so that renewals, cancellations and refunds are seen.
 */

namespace TermiNow\Play;

const PACKAGE = 'com.terminow';
const PRODUCT_ID = 'terminow_premium';
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/';
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
/** How long a check with Google is trusted before it is made again. */
const RECHECK_SECONDS = 24 * 60 * 60;
/** Expired subscriptions are still checked for this long, as one on hold can come back. */
const KEEP_CHECKING_SECONDS = 60 * 24 * 60 * 60;
const MAX_TOKEN = 1024;

/** The subscription states of Google in which the user has Premium, until the expiry time. */
const ENTITLED_STATES = ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'];

class Failure extends \Exception
{
    public int $status;

    public function __construct(int $status, string $message)
    {
        parent::__construct($message);
        $this->status = $status;
    }
}

/** The obfuscated account id that the app sets on a purchase: never the uid itself. */
function account_id(string $uid): string
{
    return hash('sha256', 'terminow:' . $uid);
}

function migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_play (
        token_hash CHAR(64) NOT NULL PRIMARY KEY,
        token TEXT NOT NULL,
        uid VARCHAR(128) NOT NULL,
        product_id VARCHAR(100) NOT NULL,
        state VARCHAR(64) NOT NULL,
        expiry INTEGER NOT NULL,
        auto_renewing INTEGER NOT NULL,
        checked_at INTEGER NOT NULL)');
}

/**
 * What a subscription of subscriptionsv2.get means, for the account with this uid.
 *
 * @return array{entitled: bool, state: string, expiry: int, autoRenewing: bool, acknowledged: bool, productId: string}
 * @throws Failure When it is not a TermiNow Premium subscription of this account.
 */
function evaluate(array $subscription, string $uid, int $now): array
{
    $expiry = 0;
    $autoRenewing = false;
    $productId = '';
    foreach ((array)($subscription['lineItems'] ?? []) as $item) {
        if (!is_array($item) || ($item['productId'] ?? '') !== PRODUCT_ID) continue;
        $productId = PRODUCT_ID;
        $time = strtotime((string)($item['expiryTime'] ?? ''));
        if ($time !== false && $time > $expiry) {
            $expiry = $time;
            $autoRenewing = (bool)($item['autoRenewingPlan']['autoRenewEnabled'] ?? false);
        }
    }
    if ($productId === '') throw new Failure(400, 'Not a TermiNow Premium purchase');
    // Bought for another account, or without one: the token could be someone else's
    $accountId = (string)($subscription['externalAccountIdentifiers']['obfuscatedExternalAccountId'] ?? '');
    if (!hash_equals(account_id($uid), $accountId)) throw new Failure(403, 'This purchase belongs to another TermiNow account');
    $state = (string)($subscription['subscriptionState'] ?? '');
    return [
        'entitled' => in_array($state, ENTITLED_STATES, true) && $expiry > $now,
        'state' => $state,
        'expiry' => $expiry,
        'autoRenewing' => $autoRenewing,
        'acknowledged' => ($subscription['acknowledgementState'] ?? '') === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        'productId' => $productId,
    ];
}

/**
 * The Google Play Developer API, a function so that the tests can answer instead of Google.
 *
 * @param callable $http fn(string $method, string $url, ?string $body): array{0: int, 1: ?array}
 */
final class Google
{
    /** @var callable */
    private $http;

    public function __construct(callable $http)
    {
        $this->http = $http;
    }

    public function subscription(string $token): array
    {
        [$status, $body] = ($this->http)('GET', API . PACKAGE . '/purchases/subscriptionsv2/tokens/' . rawurlencode($token), null);
        if ($status === 400 || $status === 404 || $status === 410) throw new Failure(400, 'Google Play does not know this purchase');
        if ($status !== 200 || !is_array($body)) throw new Failure(503, 'Google Play could not be reached, try again later');
        return $body;
    }

    public function acknowledge(string $token): void
    {
        [$status] = ($this->http)('POST', API . PACKAGE . '/purchases/subscriptions/' . PRODUCT_ID . '/tokens/' .
            rawurlencode($token) . ':acknowledge', '{}');
        if ($status !== 200 && $status !== 204) throw new Failure(503, 'Google Play could not be reached, try again later');
    }
}

/**
 * Register a purchase token for the account: check it with Google, keep it, and acknowledge it.
 *
 * @throws Failure
 */
function register(\PDO $db, Google $google, string $uid, string $token, int $now): array
{
    if ($token === '' || strlen($token) > MAX_TOKEN || !preg_match('/^[A-Za-z0-9._\-]+$/', $token))
        throw new Failure(400, 'Invalid purchase token');
    $hash = hash('sha256', $token);
    $row = one($db, 'SELECT uid FROM mod_terminow_play WHERE token_hash = ?', [$hash]);
    if ($row !== null && !hash_equals((string)$row['uid'], $uid))
        throw new Failure(403, 'This purchase belongs to another TermiNow account');

    $result = evaluate($google->subscription($token), $uid, $now);
    save($db, $hash, $token, $uid, $result, $now);
    // A purchase that is not acknowledged in three days is refunded by Google
    if (!$result['acknowledged'] && $result['entitled']) $google->acknowledge($token);
    return $result;
}

/**
 * The Play Premium of the account: the subscription that lasts longest, checking again with
 * Google those that are due. Google failing is not fatal, the last known answer stays.
 *
 * @return array{premium: bool, expiry?: int, autoRenewing?: bool}
 */
function premium(\PDO $db, ?Google $google, string $uid, int $now): array
{
    $rows = all($db, 'SELECT * FROM mod_terminow_play WHERE uid = ? AND expiry > ?', [$uid, $now - KEEP_CHECKING_SECONDS]);
    $best = null;
    foreach ($rows as $row) {
        $result = [
            'entitled' => in_array($row['state'], ENTITLED_STATES, true) && (int)$row['expiry'] > $now,
            'expiry' => (int)$row['expiry'],
            'autoRenewing' => (bool)$row['auto_renewing'],
        ];
        $due = (int)$row['checked_at'] < $now - RECHECK_SECONDS || ((int)$row['expiry'] <= $now && (int)$row['checked_at'] < (int)$row['expiry']);
        if ($google !== null && $due) {
            try {
                $result = evaluate($google->subscription((string)$row['token']), $uid, $now);
                save($db, (string)$row['token_hash'], (string)$row['token'], $uid, $result, $now);
            } catch (Failure $e) {
                if ($e->status === 400 || $e->status === 403) {
                    // Google no longer knows it, for example after a refund and a long time
                    $db->prepare('UPDATE mod_terminow_play SET state = ?, checked_at = ? WHERE token_hash = ?')
                        ->execute(['UNKNOWN', $now, $row['token_hash']]);
                    $result['entitled'] = false;
                }
            }
        }
        if ($result['entitled'] && ($best === null || $result['expiry'] > $best['expiry'])) $best = $result;
    }
    if ($best === null) return ['premium' => false];
    return ['premium' => true, 'expiry' => $best['expiry'], 'autoRenewing' => $best['autoRenewing']];
}

/**
 * The answer of premium.php from both sources: Premium of WHMCS (accmove.com) and of Google Play.
 * An auto-renewing Play subscription has no due date, so that the app does not end Premium on
 * the renewal day before it checked again.
 */
function combine(array $whmcs, array $play): array
{
    if (empty($play['premium'])) return $whmcs + ['source' => empty($whmcs['premium']) ? null : 'accmove'];
    $playAnswer = [
        'premium' => true,
        'plan' => 'TermiNow Premium (Google Play)',
        'nextDueDate' => $play['autoRenewing'] ? null : gmdate('Y-m-d', $play['expiry']),
        'source' => 'play',
    ];
    if (empty($whmcs['premium'])) return $playAnswer;
    // Both: the one without an end, or the one that ends later
    if ($whmcs['nextDueDate'] === null) return $whmcs + ['source' => 'accmove'];
    if ($playAnswer['nextDueDate'] === null) return $playAnswer;
    return strcmp($whmcs['nextDueDate'], $playAnswer['nextDueDate']) >= 0 ? $whmcs + ['source' => 'accmove'] : $playAnswer;
}

function save(\PDO $db, string $hash, string $token, string $uid, array $result, int $now): void
{
    $values = [$result['productId'] ?? PRODUCT_ID, $result['state'], $result['expiry'], $result['autoRenewing'] ? 1 : 0, $now];
    $update = $db->prepare('UPDATE mod_terminow_play SET product_id = ?, state = ?, expiry = ?, auto_renewing = ?, checked_at = ? WHERE token_hash = ?');
    $update->execute(array_merge($values, [$hash]));
    if ($update->rowCount() === 0)
        $db->prepare('INSERT INTO mod_terminow_play (product_id, state, expiry, auto_renewing, checked_at, token_hash, token, uid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
            ->execute(array_merge($values, [$hash, $token, $uid]));
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    $row = $statement->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}

function all(\PDO $db, string $sql, array $args): array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    return $statement->fetchAll(\PDO::FETCH_ASSOC);
}

/**
 * A Google client that signs in with a service account key file, caching its access token.
 * Returns null when the key file is missing: Play purchases are then not set up yet.
 */
function google_from_key_file(string $keyFile, string $cacheFile): ?Google
{
    if (!is_readable($keyFile)) return null;
    $key = json_decode((string)file_get_contents($keyFile), true);
    if (!is_array($key) || empty($key['client_email']) || empty($key['private_key'])) return null;
    $accessToken = null;
    $tokenFor = function () use ($key, $cacheFile, &$accessToken): string {
        if ($accessToken !== null) return $accessToken;
        $cached = @json_decode((string)@file_get_contents($cacheFile), true);
        if (is_array($cached) && ($cached['expires'] ?? 0) > time() + 60 && is_string($cached['token'] ?? null))
            return $accessToken = $cached['token'];
        $now = time();
        $b64 = fn(string $data) => rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
        $input = $b64(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . $b64(json_encode([
                'iss' => $key['client_email'], 'scope' => SCOPE, 'aud' => 'https://oauth2.googleapis.com/token',
                'iat' => $now, 'exp' => $now + 3600]));
        if (!openssl_sign($input, $signature, $key['private_key'], OPENSSL_ALGO_SHA256)) throw new Failure(503, 'Google Play sign-in failed');
        [$status, $body] = http_request('POST', 'https://oauth2.googleapis.com/token',
            http_build_query(['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $input . '.' . $b64($signature)]),
            ['Content-Type: application/x-www-form-urlencoded']);
        if ($status !== 200 || !is_string($body['access_token'] ?? null)) throw new Failure(503, 'Google Play sign-in failed');
        $accessToken = $body['access_token'];
        $tmp = $cacheFile . '.' . bin2hex(random_bytes(4));
        $old = umask(077);
        if (@file_put_contents($tmp, json_encode(['token' => $accessToken, 'expires' => $now + (int)($body['expires_in'] ?? 3600)])) !== false)
            @rename($tmp, $cacheFile);
        umask($old);
        return $accessToken;
    };
    return new Google(function (string $method, string $url, ?string $body) use ($tokenFor): array {
        $headers = ['Authorization: Bearer ' . $tokenFor()];
        if ($body !== null) $headers[] = 'Content-Type: application/json';
        return http_request($method, $url, $body, $headers);
    });
}

/** @return array{0: int, 1: ?array} */
function http_request(string $method, string $url, ?string $body, array $headers): array
{
    $curl = curl_init($url);
    curl_setopt_array($curl, [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_HTTPHEADER => $headers,
    ]);
    if ($body !== null) curl_setopt($curl, CURLOPT_POSTFIELDS, $body);
    $text = curl_exec($curl);
    $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    curl_close($curl);
    $json = is_string($text) ? json_decode($text, true) : null;
    return [$status, is_array($json) ? $json : null];
}
