<?php
/**
 * TermiNow Premium bought with Google Play, the WHMCS side: the logic of play_sync.php, without
 * WHMCS, so that it can be tested on its own.
 *
 * Firebase verifies every purchase with Google Play and decides Premium. It then sends the
 * state of each TermiNow account here, signed with a shared secret, free members too. Every
 * account with a verified email becomes a WHMCS client. A Premium subscriber also gets a service
 * record: the service is of a product with the pay type "free", so WHMCS never invoices it, and
 * Google Play collects the payments.
 *
 * - One WHMCS client and one service per TermiNow account (Firebase uid), whatever the number of
 *   retries: the ids are kept in mod_terminow_play_sync, and a service that was created by an
 *   attempt that failed later is found again by the marker in its admin notes.
 * - A client is never merged by email. A new client is only created when no client has the
 *   email; otherwise the account waits until its owner links it with a code from the client area.
 * - Test purchases of license testers make a client, but never a service.
 * - Only WHMCS API calls are used, never writes to the tables of WHMCS.
 */

namespace TermiNow\PlaySync;

const SIGNATURE_WINDOW = 300;
const LINK_CODE_SECONDS = 600;
const LINK_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

class Failure extends \Exception
{
    public int $status;
    public string $errorCode;

    public function __construct(int $status, string $code, string $message)
    {
        parent::__construct($message);
        $this->status = $status;
        $this->errorCode = $code;
    }
}

function migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_play_sync (
        uid VARCHAR(128) NOT NULL PRIMARY KEY,
        email VARCHAR(191) NULL,
        client_id INTEGER NULL,
        service_id INTEGER NULL,
        version INTEGER NOT NULL,
        product_id VARCHAR(100) NULL,
        base_plan_id VARCHAR(64) NULL,
        whmcs_product_id INTEGER NULL,
        state VARCHAR(64) NOT NULL,
        premium INTEGER NOT NULL,
        expires_at BIGINT NULL,
        auto_renew INTEGER NOT NULL,
        test INTEGER NOT NULL,
        last_verified_at BIGINT NULL,
        status VARCHAR(32) NOT NULL,
        last_error VARCHAR(300) NULL,
        last_sync_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_link_codes (
        code_hash CHAR(64) NOT NULL PRIMARY KEY,
        client_id INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_by_uid VARCHAR(128) NULL,
        created_at INTEGER NOT NULL)');
}

/** Check the signature of a request from Firebase: HMAC-SHA256 of "<timestamp>.<body>". */
function verify_signature(string $secret, string $timestamp, string $signature, string $body, int $now): void
{
    if (strlen($secret) < 32) throw new Failure(503, 'WHMCS_NOT_CONFIGURED', 'The sync secret is not set in the TermiNow Play addon');
    if (!ctype_digit($timestamp) || abs($now - (int)$timestamp) > SIGNATURE_WINDOW)
        throw new Failure(401, 'BAD_SIGNATURE', 'The request is too old or has no timestamp');
    $expected = hash_hmac('sha256', $timestamp . '.' . $body, $secret);
    if (!hash_equals($expected, strtolower($signature))) throw new Failure(401, 'BAD_SIGNATURE', 'The signature is wrong');
}

/**
 * Handle a request of Firebase.
 *
 * @param callable $api fn(string $command, array $params): array, the local API of WHMCS.
 * @param array $settings ['paymentMethod' => the gateway module set on the orders].
 */
function handle(\PDO $db, callable $api, array $request, array $settings, int $now): array
{
    $action = (string)($request['action'] ?? '');
    if ($action === 'sync') return sync($db, $api, $request, $settings, $now);
    if ($action === 'redeem_link') return redeem_link($db, $request, $now);
    throw new Failure(400, 'BAD_REQUEST', 'Unknown action');
}

function valid_uid($uid): bool
{
    return is_string($uid) && preg_match('/^[A-Za-z0-9_-]{1,128}$/', $uid) === 1;
}

function sync(\PDO $db, callable $api, array $r, array $settings, int $now): array
{
    $uid = $r['uid'] ?? null;
    $version = $r['version'] ?? null;
    if (!valid_uid($uid) || !is_int($version) || $version < 1) throw new Failure(400, 'BAD_REQUEST', 'uid and version are required');
    $email = isset($r['email']) && is_string($r['email']) && filter_var($r['email'], FILTER_VALIDATE_EMAIL) ? strtolower($r['email']) : null;
    $whmcsProductId = isset($r['whmcsProductId']) && is_int($r['whmcsProductId']) && $r['whmcsProductId'] > 0 ? $r['whmcsProductId'] : null;
    $state = is_string($r['subscriptionState'] ?? null) ? substr($r['subscriptionState'], 0, 64) : 'NONE';
    $premium = ($r['premium'] ?? false) === true;
    $test = ($r['test'] ?? false) === true;
    $expiresAt = isset($r['expiresAt']) && is_int($r['expiresAt']) ? $r['expiresAt'] : null;

    // One request per account at a time. Not a transaction: the WHMCS API calls below use the
    // same database connection and may run their own transactions.
    $lock = lock($db, $uid);
    try {
        $row = one($db, 'SELECT * FROM mod_terminow_play_sync WHERE uid = ?', [$uid]);
        // A retry of an older state, or one that was applied already
        if ($row !== null && (int)$row['version'] >= $version && $row['status'] === 'synced') return answer($row);
        $fields = [
            'email' => $email ?? ($row['email'] ?? null),
            'version' => $version,
            'product_id' => is_string($r['productId'] ?? null) ? substr($r['productId'], 0, 100) : null,
            'base_plan_id' => is_string($r['basePlanId'] ?? null) ? substr($r['basePlanId'], 0, 64) : null,
            'whmcs_product_id' => $whmcsProductId,
            'state' => $state,
            'premium' => $premium ? 1 : 0,
            'expires_at' => $expiresAt,
            'auto_renew' => ($r['autoRenew'] ?? false) === true ? 1 : 0,
            'test' => $test ? 1 : 0,
            'last_verified_at' => isset($r['lastVerifiedAt']) && is_int($r['lastVerifiedAt']) ? $r['lastVerifiedAt'] : null,
            'updated_at' => $now,
        ];
        $row = save($db, $uid, $row, $fields);

        $clientId = $row['client_id'] !== null ? (int)$row['client_id'] : null;
        if ($clientId === null) {
            if ($fields['email'] === null) return finish($db, $uid, 'needs_email', 'The account has no verified email for a WHMCS client', $now);
            $existing = $api('GetClientsDetails', ['email' => $fields['email'], 'stats' => false]);
            if (($existing['result'] ?? '') === 'success' && !empty($existing['client']['id'] ?? $existing['userid'] ?? null))
                // Never merged by email: its owner links it with a code from the client area
                return finish($db, $uid, 'needs_link', 'A WHMCS client has this email; it is linked when its owner enters a code from the client area', $now);
            $created = $api('AddClient', [
                'firstname' => 'TermiNow',
                'lastname' => 'Member',
                'email' => $fields['email'],
                'password2' => bin2hex(random_bytes(16)),
                'skipvalidation' => true,
                'noemail' => true,
                'notes' => 'Created for a TermiNow account' . ($test ? ', which only made Google Play test purchases' : ''),
            ]);
            if (($created['result'] ?? '') !== 'success' || empty($created['clientid']))
                return finish($db, $uid, 'error', 'AddClient failed: ' . sanitize($created['message'] ?? ''), $now, 'WHMCS_ADD_CLIENT');
            $clientId = (int)$created['clientid'];
            set_ids($db, $uid, $clientId, null);
        }

        // A free member, or a license tester, is a client without a service
        if ($test) return finish($db, $uid, 'test_only', null, $now);
        if ($whmcsProductId === null)
            return $premium ? finish($db, $uid, 'not_configured', 'No WHMCS product is mapped to this Play plan', $now) :
                finish($db, $uid, 'member', null, $now);

        // Google Play collects the payments: the WHMCS product must never invoice
        $product = $api('GetProducts', ['pid' => $whmcsProductId]);
        $info = $product['products']['product'][0] ?? null;
        if (($product['result'] ?? '') !== 'success' || !is_array($info))
            return finish($db, $uid, 'error', 'WHMCS product ' . $whmcsProductId . ' was not found', $now, 'WHMCS_PRODUCT_MISSING');
        if (($info['paytype'] ?? '') !== 'free')
            return finish($db, $uid, 'error', 'WHMCS product ' . $whmcsProductId . ' must have the pay type Free, so WHMCS never invoices Google Play subscribers', $now, 'WHMCS_PRODUCT_NOT_FREE');

        $serviceId = $row['service_id'] !== null ? (int)$row['service_id'] : find_service($api, $clientId, $whmcsProductId, $uid);
        // Nothing to cancel for a subscription that never had a service
        if ($serviceId === null && !$premium) return finish($db, $uid, 'member', null, $now);
        if ($serviceId === null) {
            $order = $api('AddOrder', [
                'clientid' => $clientId,
                'pid' => [$whmcsProductId],
                'billingcycle' => ['free'],
                'paymentmethod' => $settings['paymentMethod'] ?? '',
                'noinvoice' => true,
                'noinvoiceemail' => true,
                'noemail' => true,
            ]);
            if (($order['result'] ?? '') !== 'success' || empty($order['serviceids']))
                return finish($db, $uid, 'error', 'AddOrder failed: ' . sanitize($order['message'] ?? ''), $now, 'WHMCS_ADD_ORDER');
            $serviceId = (int)explode(',', (string)$order['serviceids'])[0];
            // Mark it at once, so that a retry after a failure finds it instead of ordering again
            $api('UpdateClientProduct', ['serviceid' => $serviceId, 'notes' => marker($uid)]);
            $api('AcceptOrder', ['orderid' => $order['orderid'], 'autosetup' => false, 'sendemail' => false]);
        }
        set_ids($db, $uid, $clientId, $serviceId);

        $status = $premium ? 'Active' : (in_array($state, ['SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED'], true) ? 'Suspended' : 'Cancelled');
        $update = $api('UpdateClientProduct', [
            'serviceid' => $serviceId,
            'status' => $status,
            'nextduedate' => $expiresAt !== null ? gmdate('Y-m-d', intdiv($expiresAt, 1000)) : null,
            'notes' => marker($uid) . "\nManaged by Google Play, not invoiced by WHMCS.\nPlay state: " . $state .
                ($fields['auto_renew'] ? ', renews' : ', does not renew') .
                ($expiresAt !== null ? ', until ' . gmdate('Y-m-d H:i', intdiv($expiresAt, 1000)) . ' UTC' : ''),
        ]);
        if (($update['result'] ?? '') !== 'success')
            return finish($db, $uid, 'error', 'UpdateClientProduct failed: ' . sanitize($update['message'] ?? ''), $now, 'WHMCS_UPDATE_SERVICE');
        return finish($db, $uid, 'synced', null, $now);
    } finally {
        unlock($db, $lock);
    }
}

/** A lock of the account in MySQL for the time of a request, nothing in SQLite of the tests. */
function lock(\PDO $db, string $uid): ?string
{
    if ($db->getAttribute(\PDO::ATTR_DRIVER_NAME) !== 'mysql') return null;
    $name = 'terminow_play_' . substr(hash('sha256', $uid), 0, 40);
    $statement = $db->prepare('SELECT GET_LOCK(?, 20)');
    $statement->execute([$name]);
    if ((int)$statement->fetchColumn() !== 1) throw new Failure(503, 'WHMCS_BUSY', 'Another update of this account is running');
    return $name;
}

function unlock(\PDO $db, ?string $name): void
{
    if ($name !== null) $db->prepare('SELECT RELEASE_LOCK(?)')->execute([$name]);
}

/** A service of the client and product that an earlier attempt for this account created. */
function find_service(callable $api, int $clientId, int $productId, string $uid): ?int
{
    $services = $api('GetClientsProducts', ['clientid' => $clientId, 'pid' => $productId, 'limitnum' => 250]);
    foreach (($services['products']['product'] ?? []) as $service) {
        if (is_array($service) && strpos((string)($service['notes'] ?? ''), marker($uid)) !== false) return (int)$service['id'];
    }
    return null;
}

/** The note that ties a service to a TermiNow account, a hash, not the uid. */
function marker(string $uid): string
{
    return 'terminow-play:' . substr(hash('sha256', 'terminow-whmcs:' . $uid), 0, 32);
}

function redeem_link(\PDO $db, array $r, int $now): array
{
    $uid = $r['uid'] ?? null;
    $code = is_string($r['code'] ?? null) ? strtoupper(trim($r['code'])) : '';
    if (!valid_uid($uid) || preg_match('/^[A-Z0-9]{8}$/', $code) !== 1) throw new Failure(400, 'INVALID_LINK_CODE', 'The code is not valid');
    $db->beginTransaction();
    $hash = hash('sha256', $code);
    $link = one($db, 'SELECT * FROM mod_terminow_link_codes WHERE code_hash = ?' . for_update($db), [$hash]);
    if ($link === null || $link['used_by_uid'] !== null || (int)$link['expires_at'] < $now) {
        $db->rollBack();
        throw new Failure(400, 'INVALID_LINK_CODE', 'The code is wrong, used or expired. Get a new one in the client area');
    }
    $clientId = (int)$link['client_id'];
    $row = one($db, 'SELECT * FROM mod_terminow_play_sync WHERE uid = ?' . for_update($db), [$uid]);
    if ($row !== null && $row['client_id'] !== null && (int)$row['client_id'] !== $clientId) {
        $db->rollBack();
        throw new Failure(409, 'LINK_CONFLICT', 'This TermiNow account is linked to another WHMCS client already');
    }
    $db->prepare('UPDATE mod_terminow_link_codes SET used_by_uid = ? WHERE code_hash = ?')->execute([$uid, $hash]);
    if ($row === null)
        $db->prepare('INSERT INTO mod_terminow_play_sync (uid, client_id, version, state, premium, auto_renew, test, status, last_sync_at, updated_at)
            VALUES (?, ?, 0, ?, 0, 0, 0, ?, ?, ?)')->execute([$uid, $clientId, 'NONE', 'linked', $now, $now]);
    else
        $db->prepare('UPDATE mod_terminow_play_sync SET client_id = ?, status = ?, updated_at = ? WHERE uid = ?')->execute([$clientId, 'linked', $now, $uid]);
    $db->commit();
    return ['clientId' => $clientId];
}

/** Apply the last state that Firebase sent again, for the Retry button of the admin page. */
function retry(\PDO $db, callable $api, string $uid, array $settings, int $now): array
{
    $row = one($db, 'SELECT * FROM mod_terminow_play_sync WHERE uid = ?', [$uid]);
    if ($row === null || (int)$row['version'] < 1) throw new Failure(404, 'NOT_FOUND', 'Nothing was received for this account yet');
    $db->prepare('UPDATE mod_terminow_play_sync SET status = ? WHERE uid = ?')->execute(['queued', $uid]);
    return sync($db, $api, [
        'uid' => $uid,
        'version' => (int)$row['version'],
        'email' => $row['email'],
        'whmcsProductId' => $row['whmcs_product_id'] !== null ? (int)$row['whmcs_product_id'] : null,
        'subscriptionState' => $row['state'],
        'premium' => (int)$row['premium'] === 1,
        'test' => (int)$row['test'] === 1,
        'expiresAt' => $row['expires_at'] !== null ? (int)$row['expires_at'] : null,
        'autoRenew' => (int)$row['auto_renew'] === 1,
        'productId' => $row['product_id'],
        'basePlanId' => $row['base_plan_id'],
        'lastVerifiedAt' => $row['last_verified_at'] !== null ? (int)$row['last_verified_at'] : null,
    ], $settings, $now);
}

/** A one-time code for the client that is logged in to the client area, valid for 10 minutes. */
function create_link_code(\PDO $db, int $clientId, int $now): string
{
    $code = '';
    for ($i = 0; $i < 8; $i++) $code .= LINK_ALPHABET[random_int(0, strlen(LINK_ALPHABET) - 1)];
    $db->prepare('DELETE FROM mod_terminow_link_codes WHERE expires_at < ?')->execute([$now - 86400]);
    $db->prepare('INSERT INTO mod_terminow_link_codes (code_hash, client_id, expires_at, created_at) VALUES (?, ?, ?, ?)')
        ->execute([hash('sha256', $code), $clientId, $now + LINK_CODE_SECONDS, $now]);
    return $code;
}

function save(\PDO $db, string $uid, ?array $row, array $fields): array
{
    if ($row === null) {
        $fields += ['client_id' => null, 'service_id' => null, 'status' => 'queued', 'last_error' => null, 'last_sync_at' => 0];
        $columns = array_keys($fields);
        $db->prepare('INSERT INTO mod_terminow_play_sync (uid, ' . implode(', ', $columns) . ') VALUES (?' . str_repeat(', ?', count($columns)) . ')')
            ->execute(array_merge([$uid], array_values($fields)));
    } else {
        $sets = implode(', ', array_map(fn($c) => "$c = ?", array_keys($fields)));
        $db->prepare("UPDATE mod_terminow_play_sync SET $sets WHERE uid = ?")->execute(array_merge(array_values($fields), [$uid]));
    }
    return one($db, 'SELECT * FROM mod_terminow_play_sync WHERE uid = ?', [$uid]);
}

function set_ids(\PDO $db, string $uid, int $clientId, ?int $serviceId): void
{
    $db->prepare('UPDATE mod_terminow_play_sync SET client_id = ?, service_id = ? WHERE uid = ?')->execute([$clientId, $serviceId, $uid]);
}

/**
 * Record the outcome. A WHMCS error is kept for the admin view and answered with
 * 502, so that Firebase retries it; Premium does not depend on it.
 */
function finish(\PDO $db, string $uid, string $status, ?string $error, int $now, ?string $code = null): array
{
    $db->prepare('UPDATE mod_terminow_play_sync SET status = ?, last_error = ?, last_sync_at = ? WHERE uid = ?')
        ->execute([$status, $error === null ? null : substr($error, 0, 300), $now, $uid]);
    $row = one($db, 'SELECT * FROM mod_terminow_play_sync WHERE uid = ?', [$uid]);
    if ($code !== null) throw new Failure(502, $code, (string)$error);
    return answer($row);
}

function answer(array $row): array
{
    return [
        'status' => $row['status'],
        'clientId' => $row['client_id'] !== null ? (int)$row['client_id'] : null,
        'serviceId' => $row['service_id'] !== null ? (int)$row['service_id'] : null,
    ];
}

function sanitize(string $text): string
{
    return substr(preg_replace('/[^\P{C}]+/u', ' ', $text), 0, 200);
}

function for_update(\PDO $db): string
{
    return $db->getAttribute(\PDO::ATTR_DRIVER_NAME) === 'mysql' ? ' FOR UPDATE' : '';
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    $row = $statement->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}

/** The ids of WHMCS services that Google Play manages, which are not Premium of their own in WHMCS. */
function play_service_ids(\PDO $db): array
{
    try {
        return array_map('intval', $db->query('SELECT service_id FROM mod_terminow_play_sync WHERE service_id IS NOT NULL')->fetchAll(\PDO::FETCH_COLUMN));
    } catch (\Throwable $e) {
        return [];
    }
}
