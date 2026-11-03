<?php
/**
 * TermiNow Store sync, the WHMCS side: the logic of store_sync.php, without WHMCS, so that it can
 * be tested on its own. Mirrors the shape of play_sync_lib.php and is deliberately kept separate so
 * the Google Play path is never touched.
 *
 * WHMCS (cp.accmove.com) is the source of truth for the production catalog, pricing and billing.
 * The TermiNow Store is a Devnet SANDBOX storefront. Firebase (project linex-bf945) is the
 * entitlement authority the app reads (entitlements/{uid}). This endpoint keeps a LOCAL mirror of a
 * Store entitlement so the same WHMCS client can be reconciled and reported on — it never decides
 * Premium and never bills.
 *
 * Hard guarantees of this module:
 *   - A Devnet/sandbox purchase NEVER creates or activates a WHMCS service and NEVER invoices.
 *     Only WHMCS READ calls are used (GetClientsDetails, to tell "needs link" from "needs client").
 *     No AddClient / AddOrder / AcceptOrder / UpdateClientProduct is ever issued here.
 *   - A client is NEVER merged by email. Association reuses the SAME verified uid<->client link that
 *     the Play addon's one-time client-area code establishes (mod_terminow_play_sync.client_id).
 *     Until that link exists the entitlement is recorded but held (needs_link / needs_client).
 *   - Idempotent and retryable: a monotonic version per (uid, feature_key); an older or already
 *     applied state is ignored; a held record is re-resolved on the next sync (e.g. after linking).
 *   - store_service_ids() is provided for the Premium decision to EXCLUDE any future Store service,
 *     but in sandbox it is always empty because no service is ever created.
 */

namespace TermiNow\StoreSync;

const SIGNATURE_WINDOW = 300;

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
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_store_sync (
        uid VARCHAR(128) NOT NULL,
        feature_key VARCHAR(64) NOT NULL,
        email VARCHAR(191) NULL,
        client_id INTEGER NULL,
        service_id INTEGER NULL,          -- always NULL in sandbox; reserved for a future real path
        version INTEGER NOT NULL,
        store_product_id VARCHAR(100) NULL,
        whmcs_product_id INTEGER NULL,
        whmcs_addon_id INTEGER NULL,
        type VARCHAR(16) NOT NULL,         -- premium | addon
        sandbox INTEGER NOT NULL,
        active INTEGER NOT NULL,           -- current entitlement state (lifecycle)
        expires_at BIGINT NULL,
        status VARCHAR(32) NOT NULL,
        last_error VARCHAR(300) NULL,
        last_sync_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (uid, feature_key))');
}

/** Check the signature of a request from Firebase: HMAC-SHA256 of "<timestamp>.<body>". */
function verify_signature(string $secret, string $timestamp, string $signature, string $body, int $now): void
{
    if (strlen($secret) < 32) throw new Failure(503, 'WHMCS_NOT_CONFIGURED', 'The Store sync secret is not set in the TermiNow Play addon');
    if (!ctype_digit($timestamp) || abs($now - (int)$timestamp) > SIGNATURE_WINDOW)
        throw new Failure(401, 'BAD_SIGNATURE', 'The request is too old or has no timestamp');
    $expected = hash_hmac('sha256', $timestamp . '.' . $body, $secret);
    if (!hash_equals($expected, strtolower($signature))) throw new Failure(401, 'BAD_SIGNATURE', 'The signature is wrong');
}

function handle(\PDO $db, callable $api, array $request, array $settings, int $now): array
{
    $action = (string)($request['action'] ?? '');
    if ($action === 'store_sync') return store_sync($db, $api, $request, $settings, $now);
    throw new Failure(400, 'BAD_REQUEST', 'Unknown action');
}

function valid_uid($uid): bool
{
    return is_string($uid) && preg_match('/^[A-Za-z0-9_-]{1,128}$/', $uid) === 1;
}

function valid_feature_key($key): bool
{
    return is_string($key) && preg_match('/^[a-z0-9][a-z0-9-]{1,63}$/', $key) === 1;
}

/**
 * Record a Store entitlement mirror and associate it to a WHMCS client, WITHOUT creating or
 * activating any WHMCS service and without invoicing. Lifecycle (active/expiry) is tracked on the
 * local row. Returns {status, clientId, serviceId, active, sandbox}.
 */
function store_sync(\PDO $db, callable $api, array $r, array $settings, int $now): array
{
    $uid = $r['uid'] ?? null;
    $featureKey = $r['featureKey'] ?? null;
    $version = $r['version'] ?? null;
    if (!valid_uid($uid) || !valid_feature_key($featureKey) || !is_int($version) || $version < 1)
        throw new Failure(400, 'BAD_REQUEST', 'uid, featureKey and version are required');

    $type = in_array($r['type'] ?? null, ['premium', 'addon'], true) ? $r['type'] : null;
    if ($type === null) throw new Failure(400, 'BAD_REQUEST', 'type must be premium or addon');

    // Devnet sandbox is the only mode enabled. A caller may pass sandbox:false only when the server
    // is explicitly allowed to record real grants; otherwise it is forced to sandbox so Devnet can
    // never masquerade as a real purchase.
    $allowReal = ($settings['allowRealGrants'] ?? false) === true;
    $sandbox = $allowReal ? (($r['sandbox'] ?? true) === true) : true;

    $email = isset($r['email']) && is_string($r['email']) && filter_var($r['email'], FILTER_VALIDATE_EMAIL) ? strtolower($r['email']) : null;
    $whmcsProductId = isset($r['whmcsProductId']) && is_int($r['whmcsProductId']) && $r['whmcsProductId'] > 0 ? $r['whmcsProductId'] : null;
    $whmcsAddonId = isset($r['whmcsAddonId']) && is_int($r['whmcsAddonId']) && $r['whmcsAddonId'] > 0 ? $r['whmcsAddonId'] : null;
    $active = ($r['active'] ?? false) === true;
    $expiresAt = isset($r['expiresAt']) && is_int($r['expiresAt']) ? $r['expiresAt'] : null;
    $storeProductId = is_string($r['storeProductId'] ?? null) ? substr($r['storeProductId'], 0, 100) : null;

    $lock = lock($db, $uid . ':' . $featureKey);
    try {
        $row = one($db, 'SELECT * FROM mod_terminow_store_sync WHERE uid = ? AND feature_key = ?', [$uid, $featureKey]);
        // A retry of an older state, or one already applied: ignore once associated (status 'recorded').
        // A held row (needs_client / needs_link / queued) is always re-resolved, e.g. after linking.
        if ($row !== null && (int)$row['version'] >= $version && $row['status'] === 'recorded') return answer($row);

        $fields = [
            'email' => $email ?? ($row['email'] ?? null),
            'version' => $version,
            'store_product_id' => $storeProductId ?? ($row['store_product_id'] ?? null),
            'whmcs_product_id' => $whmcsProductId ?? ($row !== null ? ($row['whmcs_product_id'] !== null ? (int)$row['whmcs_product_id'] : null) : null),
            'whmcs_addon_id' => $whmcsAddonId ?? ($row !== null ? ($row['whmcs_addon_id'] !== null ? (int)$row['whmcs_addon_id'] : null) : null),
            'type' => $type,
            'sandbox' => $sandbox ? 1 : 0,
            'active' => $active ? 1 : 0,
            'expires_at' => $expiresAt,
            'updated_at' => $now,
        ];
        $row = save($db, $uid, $featureKey, $row, $fields);

        // Associate to a WHMCS client, reusing the verified uid<->client link that the Play addon's
        // one-time code establishes. Never create a client, never merge by email.
        $clientId = $row['client_id'] !== null ? (int)$row['client_id'] : linked_client($db, $uid);
        if ($clientId === null) {
            if ($fields['email'] !== null && client_exists_by_email($api, $fields['email']))
                return finish($db, $uid, $featureKey, 'needs_link', 'A WHMCS client has this email; it is linked when its owner enters a code from the client area', $now);
            return finish($db, $uid, $featureKey, 'needs_client', 'No linked WHMCS client for this TermiNow account yet', $now);
        }
        set_client($db, $uid, $featureKey, $clientId);

        // SANDBOX: the entitlement is recorded and associated, but no WHMCS service is created or
        // activated and nothing is invoiced. The lifecycle lives on this row (active / expires_at).
        return finish($db, $uid, $featureKey, 'recorded', null, $now);
    } finally {
        unlock($db, $lock);
    }
}

/** The WHMCS client already linked to this TermiNow account, from the shared Play/WHMCS link. */
function linked_client(\PDO $db, string $uid): ?int
{
    try {
        $row = one($db, 'SELECT client_id FROM mod_terminow_play_sync WHERE uid = ? AND client_id IS NOT NULL', [$uid]);
        if ($row !== null) return (int)$row['client_id'];
    } catch (\Throwable $e) {
        // The Play table may not exist in a Store-only test database; treat as no link.
    }
    $row = one($db, 'SELECT client_id FROM mod_terminow_store_sync WHERE uid = ? AND client_id IS NOT NULL LIMIT 1', [$uid]);
    return $row !== null ? (int)$row['client_id'] : null;
}

/** Whether a WHMCS client already has this email (read-only). Used only to pick the held status. */
function client_exists_by_email(callable $api, string $email): bool
{
    $existing = $api('GetClientsDetails', ['email' => $email, 'stats' => false]);
    return ($existing['result'] ?? '') === 'success' && !empty($existing['client']['id'] ?? $existing['userid'] ?? null);
}

/** A lock of the entitlement in MySQL for the time of a request, nothing in SQLite of the tests. */
function lock(\PDO $db, string $key): ?string
{
    if ($db->getAttribute(\PDO::ATTR_DRIVER_NAME) !== 'mysql') return null;
    $name = 'terminow_store_' . substr(hash('sha256', $key), 0, 40);
    $statement = $db->prepare('SELECT GET_LOCK(?, 20)');
    $statement->execute([$name]);
    if ((int)$statement->fetchColumn() !== 1) throw new Failure(503, 'WHMCS_BUSY', 'Another update of this account is running');
    return $name;
}

function unlock(\PDO $db, ?string $name): void
{
    if ($name !== null) $db->prepare('SELECT RELEASE_LOCK(?)')->execute([$name]);
}

function save(\PDO $db, string $uid, string $featureKey, ?array $row, array $fields): array
{
    if ($row === null) {
        $fields += ['client_id' => null, 'service_id' => null, 'status' => 'queued', 'last_error' => null, 'last_sync_at' => 0];
        $columns = array_keys($fields);
        $db->prepare('INSERT INTO mod_terminow_store_sync (uid, feature_key, ' . implode(', ', $columns) . ') VALUES (?, ?' . str_repeat(', ?', count($columns)) . ')')
            ->execute(array_merge([$uid, $featureKey], array_values($fields)));
    } else {
        $sets = implode(', ', array_map(fn($c) => "$c = ?", array_keys($fields)));
        $db->prepare("UPDATE mod_terminow_store_sync SET $sets WHERE uid = ? AND feature_key = ?")
            ->execute(array_merge(array_values($fields), [$uid, $featureKey]));
    }
    return one($db, 'SELECT * FROM mod_terminow_store_sync WHERE uid = ? AND feature_key = ?', [$uid, $featureKey]);
}

function set_client(\PDO $db, string $uid, string $featureKey, int $clientId): void
{
    $db->prepare('UPDATE mod_terminow_store_sync SET client_id = ? WHERE uid = ? AND feature_key = ?')->execute([$clientId, $uid, $featureKey]);
}

function finish(\PDO $db, string $uid, string $featureKey, string $status, ?string $error, int $now): array
{
    $db->prepare('UPDATE mod_terminow_store_sync SET status = ?, last_error = ?, last_sync_at = ? WHERE uid = ? AND feature_key = ?')
        ->execute([$status, $error === null ? null : substr($error, 0, 300), $now, $uid, $featureKey]);
    return answer(one($db, 'SELECT * FROM mod_terminow_store_sync WHERE uid = ? AND feature_key = ?', [$uid, $featureKey]));
}

function answer(array $row): array
{
    return [
        'status' => $row['status'],
        'clientId' => $row['client_id'] !== null ? (int)$row['client_id'] : null,
        'serviceId' => $row['service_id'] !== null ? (int)$row['service_id'] : null,
        'active' => (int)$row['active'] === 1,
        'sandbox' => (int)$row['sandbox'] === 1,
    ];
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    $row = $statement->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}

/**
 * WHMCS service ids that the Store manages, so the Premium decision can EXCLUDE them (a Store mirror
 * is never a Premium source of its own). Always empty in sandbox, since no service is created.
 */
function store_service_ids(\PDO $db): array
{
    try {
        return array_map('intval', $db->query('SELECT service_id FROM mod_terminow_store_sync WHERE service_id IS NOT NULL')->fetchAll(\PDO::FETCH_COLUMN));
    } catch (\Throwable $e) {
        return [];
    }
}
