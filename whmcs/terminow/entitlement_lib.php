<?php
/**
 * TermiNow WHMCS -> Firebase entitlement lifecycle, the testable core.
 *
 * WHMCS is authoritative for services bought and billed there. When a mapped service changes
 * (activation, renewal, suspension, cancellation, termination, expiry) this module re-reads the
 * authoritative WHMCS state for the client, computes the entitlement, and enqueues a delivery to
 * Firebase so the app's rights (entitlements/{uid}) follow WHMCS. Delivery is:
 *   - authenticated  (HMAC-SHA256 of "<timestamp>.<body>", like the other endpoints),
 *   - idempotent     (a per (uid, feature, source) payload hash; an unchanged state is not re-sent),
 *   - ordered        (a monotonic seq per (uid, feature, source); a later event never overtakes an
 *                     undelivered earlier one),
 *   - retried        (pending/failed rows are redelivered; unconfigured delivery is 'held', not failed),
 *   - reconciled     (a periodic sweep recomputes authoritative state and enqueues any drift).
 *
 * Sources are kept separate. This module only ever emits source "whmcs". The Firebase side merges by
 * source, so dropping WHMCS rights must not remove a user's Google Play or Store sandbox rights, and
 * vice versa. premium.php polling stays as a compatibility path and is untouched.
 *
 * SANDBOX: the Store's Devnet mirror (source "store") is tracking-only and never produces a grant
 * here; this lifecycle concerns real WHMCS services only.
 */

namespace TermiNow\Entitlement;

const SOURCE = 'whmcs';
const MAX_ATTEMPTS = 8;

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
    $auto = $db->getAttribute(\PDO::ATTR_DRIVER_NAME) === 'mysql' ? 'INTEGER PRIMARY KEY AUTO_INCREMENT' : 'INTEGER PRIMARY KEY AUTOINCREMENT';
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_entitlement_sync (
        id ' . $auto . ',
        uid VARCHAR(128) NOT NULL,
        client_id INTEGER NULL,
        feature_key VARCHAR(64) NOT NULL,
        source VARCHAR(16) NOT NULL,              -- always "whmcs" from this module
        active INTEGER NOT NULL,
        expires_at BIGINT NULL,
        tier VARCHAR(16) NULL,                    -- premium | addon (so the writer knows what grants Premium)
        whmcs_state VARCHAR(32) NULL,             -- Active | Suspended | Cancelled | Terminated | Expired | None
        seq INTEGER NOT NULL,                     -- monotonic per (uid, feature_key, source)
        idempotency_key CHAR(64) NOT NULL,
        payload_hash CHAR(64) NOT NULL,           -- hash of the delivered state, for dedup
        status VARCHAR(16) NOT NULL,              -- pending | delivered | failed | held
        attempts INTEGER NOT NULL DEFAULT 0,
        last_error VARCHAR(300) NULL,
        last_attempt_at INTEGER NULL,
        delivered_at INTEGER NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
    // A unique index would be ideal on (uid, feature_key, source, seq); kept as a plain index so a
    // replayed migration never fails on existing data.
    try { $db->exec('CREATE INDEX idx_tnent_group ON mod_terminow_entitlement_sync (uid, feature_key, source, seq)'); } catch (\Throwable $e) {}
    try { $db->exec('CREATE INDEX idx_tnent_status ON mod_terminow_entitlement_sync (status)'); } catch (\Throwable $e) {}
}

/** Map a WHMCS service status to {active, state}. Active rights come only from an Active service. */
function map_service_state(string $whmcsStatus, ?string $nextDueDate, int $now): array
{
    $state = $whmcsStatus === '' ? 'None' : $whmcsStatus;
    $active = $whmcsStatus === 'Active';
    // An Active service past its due date (lapsed, not yet suspended by cron) is treated as expired.
    if ($active && $nextDueDate !== null && $nextDueDate !== '' && $nextDueDate !== '0000-00-00') {
        $due = strtotime($nextDueDate . ' 23:59:59 UTC');
        if ($due !== false && $due < $now) { $active = false; $state = 'Expired'; }
    }
    return ['active' => $active, 'state' => $state];
}

/**
 * Re-read the authoritative WHMCS state for a client and return the entitlement for every mapped
 * feature the client has a service for: [feature_key => {active, expiresAt(ms)|null, state, serviceId}].
 * Services managed by Google Play or the Store mirror are excluded (separate sources). $catalogByPid
 * maps a WHMCS product id to its catalog row (from Catalog\by_product_id()).
 *
 * @param callable $api fn(string, array): array  — the WHMCS local API (read only here)
 */
function compute_client_entitlements(callable $api, int $clientId, array $catalogByPid, array $excludedServiceIds, int $now): array
{
    $resp = $api('GetClientsProducts', ['clientid' => $clientId, 'limitnum' => 250]);
    if (($resp['result'] ?? '') !== 'success') throw new Failure(502, 'WHMCS_READ', 'GetClientsProducts failed for client ' . $clientId);
    $services = service_list($resp['products']['product'] ?? []);
    $excluded = array_flip(array_map('intval', $excludedServiceIds));

    // For each mapped feature pick the "best" service: an active one wins, else the latest.
    $out = [];
    foreach ($services as $svc) {
        if (!is_array($svc)) continue;
        $sid = (int)($svc['id'] ?? 0);
        if ($sid > 0 && isset($excluded[$sid])) continue;       // Play / Store service, another source
        $pid = (int)($svc['pid'] ?? 0);
        $entry = $catalogByPid[$pid] ?? null;
        if ($entry === null) continue;                           // not a mapped TermiNow catalog product
        $featureKey = $entry['feature_key'];
        $st = map_service_state((string)($svc['status'] ?? ''), $svc['nextduedate'] ?? null, $now);
        $expiresAt = expiry_ms($svc['nextduedate'] ?? null);
        $candidate = ['active' => $st['active'], 'expiresAt' => $expiresAt, 'state' => $st['state'], 'serviceId' => $sid,
            'type' => $entry['type'], 'whmcsProductId' => $pid];
        if (!isset($out[$featureKey]) || ($candidate['active'] && !$out[$featureKey]['active'])
            || ($candidate['active'] === $out[$featureKey]['active'] && ($expiresAt ?? 0) > ($out[$featureKey]['expiresAt'] ?? 0)))
            $out[$featureKey] = $candidate;
    }
    return $out;
}

/** GetClientsProducts returns one service as an object and several as a list; normalize to a list. */
function service_list($product): array
{
    if (!is_array($product) || $product === []) return [];
    if (isset($product['id']) || isset($product['pid']) || isset($product['name'])) return [$product];
    return array_values(array_filter($product, 'is_array'));
}

function expiry_ms($nextDueDate): ?int
{
    if (!is_string($nextDueDate) || $nextDueDate === '' || $nextDueDate === '0000-00-00') return null;
    $t = strtotime($nextDueDate . ' 00:00:00 UTC');
    return $t === false ? null : $t * 1000;
}

/** The canonical payload delivered to Firebase for one feature of one account. */
function payload(string $uid, string $featureKey, array $state, ?int $clientId = null): array
{
    return [
        'uid' => $uid,
        'clientId' => $clientId,
        'source' => SOURCE,
        'featureKey' => $featureKey,
        'active' => (bool)$state['active'],
        'expiresAt' => $state['expiresAt'] ?? null,
        'state' => $state['state'] ?? null,
        'type' => $state['type'] ?? null,
    ];
}

function payload_hash(array $payload): string
{
    return hash('sha256', json_encode([$payload['active'], $payload['expiresAt'], $payload['state'], $payload['type']]));
}

/** The most recent row for (uid, feature, source), whatever its status. */
function latest(\PDO $db, string $uid, string $featureKey): ?array
{
    return one($db, 'SELECT * FROM mod_terminow_entitlement_sync WHERE uid = ? AND feature_key = ? AND source = ? ORDER BY seq DESC LIMIT 1',
        [$uid, $featureKey, SOURCE]);
}

/**
 * Enqueue a delivery for (uid, feature) if the authoritative state differs from the last row, or the
 * last row is not yet delivered with this exact state. Idempotent: an unchanged, already-delivered
 * state enqueues nothing. Returns the new row, or null when nothing changed.
 */
function enqueue(\PDO $db, string $uid, ?int $clientId, string $featureKey, array $state, int $now): ?array
{
    $p = payload($uid, $featureKey, $state);
    $hash = payload_hash($p);
    $last = latest($db, $uid, $featureKey);
    if ($last !== null && $last['payload_hash'] === $hash && $last['status'] === 'delivered') return null; // no change
    if ($last !== null && $last['payload_hash'] === $hash && in_array($last['status'], ['pending', 'held'], true)) return null; // already queued, same state
    $seq = $last !== null ? (int)$last['seq'] + 1 : 1;
    $idem = hash('sha256', $uid . '|' . $featureKey . '|' . SOURCE . '|' . $seq);
    $db->prepare('INSERT INTO mod_terminow_entitlement_sync
        (uid, client_id, feature_key, source, active, expires_at, tier, whmcs_state, seq, idempotency_key, payload_hash, status, attempts, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)')
        ->execute([$uid, $clientId, $featureKey, SOURCE, $p['active'] ? 1 : 0, $p['expiresAt'], $p['type'], $p['state'], $seq, $idem, $hash, 'pending', $now, $now]);
    return latest($db, $uid, $featureKey);
}

/**
 * Compute the authoritative WHMCS entitlement for a client and enqueue any changes, for EACH uid
 * linked to the client. Mapped features that the client no longer holds are enqueued as inactive so
 * the right is withdrawn. Returns the list of enqueued rows.
 *
 * @param string[] $uids   the Firebase uids linked to this client
 */
function sync_client(\PDO $db, callable $api, int $clientId, array $uids, array $catalogByPid, array $excludedServiceIds, int $now): array
{
    $ents = compute_client_entitlements($api, $clientId, $catalogByPid, $excludedServiceIds, $now);
    $allFeatures = [];
    foreach ($catalogByPid as $row) $allFeatures[$row['feature_key']] = $row;
    $enqueued = [];
    foreach ($uids as $uid) {
        foreach ($allFeatures as $featureKey => $row) {
            $state = $ents[$featureKey] ?? ['active' => false, 'expiresAt' => null, 'state' => 'None', 'type' => $row['type'], 'whmcsProductId' => $row['whmcs_product_id'] !== null ? (int)$row['whmcs_product_id'] : null];
            // Only enqueue a withdrawal if we have previously said something about this feature.
            if (!isset($ents[$featureKey]) && latest($db, $uid, $featureKey) === null) continue;
            $r = enqueue($db, $uid, $clientId, $featureKey, $state, $now);
            if ($r !== null) $enqueued[] = $r;
        }
    }
    return $enqueued;
}

/**
 * Deliver pending/failed rows through $sender, preserving per-group order: within a
 * (uid, feature, source) group the lowest-seq undelivered row is tried first and a later one is not
 * attempted until it succeeds. $sender(array $payload, array $meta): true on success, or throws.
 * When delivery is not configured ($sender === null) rows are parked as 'held', never 'failed'.
 * Returns ['delivered'=>n, 'failed'=>n, 'held'=>n, 'blocked'=>n].
 */
function deliver_pending(\PDO $db, ?callable $sender, int $now, int $limit = 200): array
{
    $rows = $db->query("SELECT * FROM mod_terminow_entitlement_sync WHERE status IN ('pending','failed','held') ORDER BY uid, feature_key, seq ASC")->fetchAll(\PDO::FETCH_ASSOC);
    $summary = ['delivered' => 0, 'failed' => 0, 'held' => 0, 'blocked' => 0];
    $blockedGroups = [];
    $done = 0;
    foreach ($rows as $row) {
        if ($done >= $limit) break;
        $group = $row['uid'] . '|' . $row['feature_key'] . '|' . $row['source'];
        if (isset($blockedGroups[$group])) { $summary['blocked']++; continue; } // an earlier seq is undelivered

        if ($sender === null) {
            mark($db, (int)$row['id'], 'held', (int)$row['attempts'], 'Entitlement delivery is not configured', $now);
            $summary['held']++;
            $blockedGroups[$group] = true;   // keep order: nothing after this seq until it lands
            continue;
        }
        $done++;
        try {
            $ok = $sender(rebuild_payload($row), ['idempotencyKey' => $row['idempotency_key'], 'seq' => (int)$row['seq']]);
            if ($ok === false) throw new \RuntimeException('delivery rejected');
            mark($db, (int)$row['id'], 'delivered', (int)$row['attempts'] + 1, null, $now);
            $summary['delivered']++;
        } catch (\Throwable $e) {
            // A "held" outcome (e.g. the account is not yet linked on the Firebase side) is retryable
            // and must NOT count as a failure; the group still waits so ordering is preserved.
            $heldOutcome = $e instanceof Failure && $e->errorCode === 'HELD';
            $attempts = (int)$row['attempts'] + 1;
            mark($db, (int)$row['id'], $heldOutcome ? 'held' : 'failed', $attempts, substr($e->getMessage(), 0, 300), $now);
            $summary[$heldOutcome ? 'held' : 'failed']++;
            $blockedGroups[$group] = true;   // do not overtake an earlier event that has not landed
        }
    }
    return $summary;
}

function rebuild_payload(array $row): array
{
    return [
        'uid' => $row['uid'],
        'clientId' => $row['client_id'] !== null ? (int)$row['client_id'] : null,
        'source' => $row['source'],
        'featureKey' => $row['feature_key'],
        'active' => (int)$row['active'] === 1,
        'expiresAt' => $row['expires_at'] !== null ? (int)$row['expires_at'] : null,
        'state' => $row['whmcs_state'],
        'type' => $row['tier'] ?? null,
    ];
}

function mark(\PDO $db, int $id, string $status, int $attempts, ?string $error, int $now): void
{
    $db->prepare('UPDATE mod_terminow_entitlement_sync SET status = ?, attempts = ?, last_error = ?, last_attempt_at = ?, delivered_at = ?, updated_at = ? WHERE id = ?')
        ->execute([$status, $attempts, $error === null ? null : substr($error, 0, 300), $now, $status === 'delivered' ? $now : null, $now, $id]);
}

/**
 * Recompute authoritative WHMCS state for the given (uid list keyed by client id) and report the
 * drift versus the last delivered state. With $apply=false it only previews; with $apply=true it
 * enqueues corrections. Returns ['preview'=>[...], 'enqueued'=>n].
 *
 * @param array $clientUids  [clientId => [uid, ...]]
 */
function reconcile(\PDO $db, callable $api, array $clientUids, array $catalogByPid, array $excludedServiceIds, int $now, bool $apply): array
{
    $allFeatures = [];
    foreach ($catalogByPid as $row) $allFeatures[$row['feature_key']] = $row;
    $preview = [];
    $enqueued = 0;
    foreach ($clientUids as $clientId => $uids) {
        $ents = compute_client_entitlements($api, (int)$clientId, $catalogByPid, $excludedServiceIds, $now);
        foreach ($uids as $uid) {
            foreach ($allFeatures as $featureKey => $row) {
                $state = $ents[$featureKey] ?? ['active' => false, 'expiresAt' => null, 'state' => 'None', 'type' => $row['type'], 'whmcsProductId' => $row['whmcs_product_id'] !== null ? (int)$row['whmcs_product_id'] : null];
                $last = latest($db, $uid, $featureKey);
                $wantHash = payload_hash(payload($uid, $featureKey, $state));
                $haveHash = $last !== null && $last['status'] === 'delivered' ? $last['payload_hash'] : null;
                if (!isset($ents[$featureKey]) && $last === null) continue;      // never spoke about it, no drift
                if ($wantHash === $haveHash) continue;                            // in sync
                $preview[] = ['uid' => $uid, 'clientId' => (int)$clientId, 'featureKey' => $featureKey,
                    'desiredActive' => (bool)$state['active'], 'desiredState' => $state['state'],
                    'lastDeliveredState' => $last['whmcs_state'] ?? null,
                    'lastDeliveredActive' => $last !== null ? ((int)$last['active'] === 1) : null,
                    'pending' => $last !== null && $last['status'] !== 'delivered'];
                if ($apply && enqueue($db, $uid, (int)$clientId, $featureKey, $state, $now) !== null) $enqueued++;
            }
        }
    }
    return ['preview' => $preview, 'enqueued' => $enqueued];
}

/** Re-queue one failed/held row for another delivery attempt (admin Retry). */
function retry(\PDO $db, int $id, int $now): void
{
    $row = get($db, $id);
    if ($row === null) throw new Failure(404, 'NOT_FOUND', 'No such entitlement event');
    if ($row['status'] === 'delivered') return;
    $db->prepare("UPDATE mod_terminow_entitlement_sync SET status = 'pending', last_error = NULL, updated_at = ? WHERE id = ?")->execute([$now, $id]);
}

/** Rows for the admin view, newest first, optionally only those needing attention. */
function events(\PDO $db, bool $onlyAttention = false, int $limit = 500): array
{
    $where = $onlyAttention ? "WHERE status IN ('failed','held')" : '';
    $st = $db->prepare("SELECT * FROM mod_terminow_entitlement_sync $where ORDER BY updated_at DESC, id DESC LIMIT ?");
    $st->execute([$limit]);
    return $st->fetchAll(\PDO::FETCH_ASSOC);
}

function get(\PDO $db, int $id): ?array
{
    return one($db, 'SELECT * FROM mod_terminow_entitlement_sync WHERE id = ?', [$id]);
}

/** Authenticated delivery signature, same scheme as the other TermiNow endpoints. */
function sign(string $secret, string $timestamp, string $body): string
{
    return hash_hmac('sha256', $timestamp . '.' . $body, $secret);
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $st = $db->prepare($sql);
    $st->execute($args);
    $row = $st->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}
