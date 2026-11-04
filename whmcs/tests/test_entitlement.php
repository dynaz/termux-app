<?php
// php whmcs/tests/test_entitlement.php — the WHMCS -> Firebase entitlement lifecycle: authoritative
// re-read, idempotency, ordering, retries/held, reconciliation, and source separation.

require __DIR__ . '/../terminow/entitlement_lib.php';

use TermiNow\Entitlement\Failure;
use function TermiNow\Entitlement\compute_client_entitlements;
use function TermiNow\Entitlement\deliver_pending;
use function TermiNow\Entitlement\enqueue;
use function TermiNow\Entitlement\events;
use function TermiNow\Entitlement\latest;
use function TermiNow\Entitlement\map_service_state;
use function TermiNow\Entitlement\migrate;
use function TermiNow\Entitlement\reconcile;
use function TermiNow\Entitlement\retry;
use function TermiNow\Entitlement\sync_client;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}

function db(): PDO
{
    $db = new PDO('sqlite::memory:');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    migrate($db);
    return $db;
}

$now = 1800000000;                 // 2027-01-15
$catalog = [
    90 => ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90],
    91 => ['feature_key' => 'addon-network-scan', 'type' => 'addon', 'whmcs_product_id' => 91],
];

/** A WHMCS whose GetClientsProducts returns a fixed service list. */
function api(array $services): callable
{
    return function (string $command, array $params) use ($services) {
        if ($command !== 'GetClientsProducts') throw new RuntimeException('unexpected ' . $command);
        return ['result' => 'success', 'products' => ['product' => $services]];
    };
}

// --- map_service_state -----------------------------------------------------------------------------
check(map_service_state('Active', '2099-01-01', $now) === ['active' => true, 'state' => 'Active'], 'an Active service in date is active');
check(map_service_state('Suspended', '2099-01-01', $now)['active'] === false, 'a Suspended service is inactive');
check(map_service_state('Terminated', null, $now) === ['active' => false, 'state' => 'Terminated'], 'a Terminated service is inactive');
$lapsed = map_service_state('Active', '2000-01-01', $now);
check($lapsed['active'] === false && $lapsed['state'] === 'Expired', 'an Active service past due is treated as expired');

// --- compute_client_entitlements: mapped only, Play/Store services excluded, best wins -------------
$svcs = [
    ['id' => 100, 'pid' => 90, 'status' => 'Active', 'nextduedate' => '2099-02-01'],   // premium active
    ['id' => 101, 'pid' => 91, 'status' => 'Suspended', 'nextduedate' => '2099-01-01'],// addon suspended
    ['id' => 102, 'pid' => 55, 'status' => 'Active', 'nextduedate' => '2099-01-01'],   // unmapped product
    ['id' => 103, 'pid' => 90, 'status' => 'Active', 'nextduedate' => '2030-01-01'],   // another premium service (Play's)
];
$ents = compute_client_entitlements(api($svcs), 10, $catalog, [103], $now);   // 103 excluded as a Play service
check(isset($ents['terminow-premium']) && $ents['terminow-premium']['active'] === true && $ents['terminow-premium']['serviceId'] === 100, 'the mapped active premium is picked, the excluded Play service ignored');
check($ents['addon-network-scan']['active'] === false, 'the suspended addon is inactive');
check(!isset($ents['unmapped']) && count($ents) === 2, 'unmapped products are ignored');
// object-shaped single service
$one = compute_client_entitlements(api(['id' => 200, 'pid' => 90, 'status' => 'Active', 'nextduedate' => '2099-01-01']), 10, $catalog, [], $now);
check(isset($one['terminow-premium']), 'a single service returned as an object is handled');

// --- enqueue idempotency + monotonic seq -----------------------------------------------------------
$db = db();
$r1 = enqueue($db, 'alice', 10, 'terminow-premium', ['active' => true, 'expiresAt' => null, 'state' => 'Active', 'type' => 'premium'], $now);
check($r1 !== null && (int)$r1['seq'] === 1 && $r1['status'] === 'pending', 'first state enqueues at seq 1');
$same = enqueue($db, 'alice', 10, 'terminow-premium', ['active' => true, 'expiresAt' => null, 'state' => 'Active', 'type' => 'premium'], $now);
check($same === null, 'an identical still-pending state does not re-enqueue');
$r2 = enqueue($db, 'alice', 10, 'terminow-premium', ['active' => false, 'expiresAt' => null, 'state' => 'Cancelled', 'type' => 'premium'], $now + 1);
check($r2 !== null && (int)$r2['seq'] === 2, 'a changed state enqueues at the next seq');

// --- deliver_pending: ordering, held-when-unconfigured, success ------------------------------------
$db = db();
enqueue($db, 'bob', 10, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
enqueue($db, 'bob', 10, 'terminow-premium', ['active' => false, 'state' => 'Cancelled', 'expiresAt' => null, 'type' => 'premium'], $now + 1);
$heldSummary = deliver_pending($db, null, $now);   // delivery not configured
check($heldSummary['held'] === 1 && $heldSummary['blocked'] === 1, 'unconfigured delivery holds the first and blocks the later one (never failed)');
check(latest($db, 'bob', 'terminow-premium')['status'] !== 'delivered', 'nothing is marked delivered while unconfigured');

// A sender that fails the first attempt, then succeeds; ordering must be preserved.
$sent = [];
$attempt = 0;
$flaky = function (array $payload, array $meta) use (&$sent, &$attempt) {
    $attempt++;
    if ($attempt === 1) throw new RuntimeException('network');   // first delivery fails
    $sent[] = [$payload['featureKey'], $payload['active'], $meta['seq']];
    return true;
};
$db = db();
enqueue($db, 'carl', 10, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
enqueue($db, 'carl', 10, 'terminow-premium', ['active' => false, 'state' => 'Cancelled', 'expiresAt' => null, 'type' => 'premium'], $now + 1);
$s1 = deliver_pending($db, $flaky, $now);
check($s1['failed'] === 1 && $s1['blocked'] === 1, 'a failed earlier event blocks the later one in its group');
$s2 = deliver_pending($db, $flaky, $now + 5);   // retry: seq1 succeeds, seq2 then delivered
$rows = events($db);
$delivered = array_values(array_filter($rows, fn($r) => $r['status'] === 'delivered'));
check(count($delivered) === 2, 'after retry both events deliver');
check($sent[0] === [ 'terminow-premium', true, 1 ], 'seq 1 (active) was delivered before seq 2');

// --- sync_client: withdraws a right the client no longer has ---------------------------------------
$db = db();
// first the client has active premium
$enq = sync_client($db, api([['id' => 300, 'pid' => 90, 'status' => 'Active', 'nextduedate' => '2099-01-01']]), 10, ['dave'], $catalog, [], $now);
check(count($enq) === 1 && $enq[0]['feature_key'] === 'terminow-premium' && (int)$enq[0]['active'] === 1, 'sync_client enqueues the active premium');
// deliver it
deliver_pending($db, fn($p, $m) => true, $now);
// now the service is gone -> withdrawal enqueued as inactive
$enq2 = sync_client($db, api([]), 10, ['dave'], $catalog, [], $now + 10);
check(count($enq2) === 1 && (int)$enq2[0]['active'] === 0, 'when the service disappears the right is withdrawn (active:false)');

// --- reconcile: preview vs apply -------------------------------------------------------------------
$db = db();
// delivered state says active; authoritative WHMCS now says cancelled -> drift
enqueue($db, 'erin', 10, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
deliver_pending($db, fn($p, $m) => true, $now);
$authApi = api([['id' => 400, 'pid' => 90, 'status' => 'Cancelled', 'nextduedate' => '2099-01-01']]);
$preview = reconcile($db, $authApi, [10 => ['erin']], $catalog, [], $now + 20, false);
check(count($preview['preview']) === 1 && $preview['enqueued'] === 0 && $preview['preview'][0]['desiredActive'] === false, 'reconcile preview reports drift without enqueuing');
$applied = reconcile($db, $authApi, [10 => ['erin']], $catalog, [], $now + 21, true);
check($applied['enqueued'] === 1, 'reconcile apply enqueues the correction');
$inSync = reconcile($db, api([['id' => 400, 'pid' => 90, 'status' => 'Cancelled', 'nextduedate' => '2099-01-01']]), [10 => ['erin']], $catalog, [], $now + 22, false);
// the correction is still pending (not delivered), so it still shows as drift but marked pending
check(count($inSync['preview']) === 1 && $inSync['preview'][0]['pending'] === true, 'a queued-but-undelivered correction is shown as pending drift');

// --- a HELD delivery outcome is retryable, not a failure -------------------------------------------
$db = db();
enqueue($db, 'guy', 10, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
$holdSender = function (array $payload, array $meta) { throw new Failure(409, 'HELD', 'no link yet'); };
$sh = deliver_pending($db, $holdSender, $now);
check($sh['held'] === 1 && $sh['failed'] === 0, 'a HELD throw marks the row held, not failed');
check(latest($db, 'guy', 'terminow-premium')['status'] === 'held', 'the row status is held');
// clientId travels in the delivered payload for the writer to verify against whmcsLinks
$seen = null;
$db2 = db();
enqueue($db2, 'hank', 77, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
deliver_pending($db2, function (array $payload, array $meta) use (&$seen) { $seen = $payload; return true; }, $now);
check($seen['clientId'] === 77 && $seen['uid'] === 'hank' && $seen['source'] === 'whmcs', 'the delivered payload carries uid, clientId and source');

// --- retry one row ---------------------------------------------------------------------------------
$db = db();
enqueue($db, 'fay', 10, 'terminow-premium', ['active' => true, 'state' => 'Active', 'expiresAt' => null, 'type' => 'premium'], $now);
deliver_pending($db, null, $now);   // -> held
$held = latest($db, 'fay', 'terminow-premium');
retry($db, (int)$held['id'], $now + 1);
check(latest($db, 'fay', 'terminow-premium')['status'] === 'pending', 'retry re-queues a held row');
try { retry($db, 999999, $now); check(false, 'retry of a missing row throws'); }
catch (Failure $e) { check($e->errorCode === 'NOT_FOUND', 'retry of a missing row throws NOT_FOUND'); }

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
