<?php
// php whmcs/tests/test_store_sync.php — the WHMCS side of the TermiNow Store (Devnet sandbox) mirror,
// with a fake WHMCS. Proves sandbox purchases never bill or activate a WHMCS service.

require __DIR__ . '/../terminow/play_sync_lib.php';   // for the shared uid<->client link table
require __DIR__ . '/../terminow/store_sync_lib.php';

use TermiNow\StoreSync\Failure;
use function TermiNow\StoreSync\handle;
use function TermiNow\StoreSync\migrate;
use function TermiNow\StoreSync\pending_rows;
use function TermiNow\StoreSync\retry;
use function TermiNow\StoreSync\store_service_ids;
use function TermiNow\StoreSync\verify_signature;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}
function fails(callable $call, string $code, string $name): void
{
    try { $call(); check(false, $name); }
    catch (Failure $e) { check($e->errorCode === $code, $name . ' (' . $e->errorCode . ')'); }
}

// The WHMCS local API commands that can CHANGE anything. The Store mirror must never call these.
const MUTATIONS = ['AddClient', 'AddOrder', 'AcceptOrder', 'UpdateClientProduct', 'CreateInvoice', 'GenInvoices', 'AddInvoicePayment', 'UpdateInvoice', 'ModuleCreate', 'UpdateClientProductStatus'];

/** A WHMCS that only answers GetClientsDetails and records every call. Any write is a test failure. */
class FakeWhmcs
{
    public array $calls = [];
    public array $clients = [10 => 'existing@example.com'];
    public function __invoke(string $command, array $params): array
    {
        $this->calls[] = $command;
        if ($command === 'GetClientsDetails') {
            $id = array_search(strtolower($params['email'] ?? ''), $this->clients, true);
            return $id === false ? ['result' => 'error', 'message' => 'Client Not Found'] : ['result' => 'success', 'client' => ['id' => $id]];
        }
        throw new RuntimeException('Store sync must not call ' . $command);
    }
    public function mutations(): array { return array_values(array_intersect($this->calls, MUTATIONS)); }
}

function db(): PDO
{
    $db = new PDO('sqlite::memory:');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    \TermiNow\PlaySync\migrate($db);   // the shared link table
    migrate($db);
    return $db;
}

/** Seed a verified uid<->client link, as the Play addon's one-time client-area code would. */
function link_client(PDO $db, string $uid, int $clientId, int $now): void
{
    $db->prepare('INSERT INTO mod_terminow_play_sync (uid, client_id, version, state, premium, auto_renew, test, status, last_sync_at, updated_at)
        VALUES (?, ?, 0, ?, 0, 0, 0, ?, ?, ?)')->execute([$uid, $clientId, 'NONE', 'linked', $now, $now]);
}

$now = 1800000000;
$settings = [];   // real grants not allowed by default
function state(array $more = []): array
{
    global $now;
    return $more + ['action' => 'store_sync', 'uid' => 'alice', 'featureKey' => 'terminow-premium', 'version' => 1,
        'email' => 'alice@example.com', 'type' => 'premium', 'whmcsProductId' => 90, 'sandbox' => true, 'active' => true,
        'expiresAt' => ($now + 86400 * 30) * 1000, 'storeProductId' => 'terminow-premium-30d'];
}

// --- Signatures (same HMAC scheme as Play) ---------------------------------------------------------
$secret = str_repeat('s', 40);
$body = '{"action":"store_sync"}';
$sig = hash_hmac('sha256', $now . '.' . $body, $secret);
try { verify_signature($secret, (string)$now, $sig, $body, $now); check(true, 'a signed request is accepted'); }
catch (Failure $e) { check(false, 'a signed request is accepted'); }
fails(fn() => verify_signature($secret, (string)$now, $sig, $body . ' ', $now), 'BAD_SIGNATURE', 'a changed body is refused');
fails(fn() => verify_signature($secret, (string)($now - 600), hash_hmac('sha256', ($now - 600) . '.' . $body, $secret), $body, $now), 'BAD_SIGNATURE', 'an old request is refused');
fails(fn() => verify_signature('', (string)$now, $sig, $body, $now), 'WHMCS_NOT_CONFIGURED', 'no secret, no sync');

// --- Validation ------------------------------------------------------------------------------------
$db = db(); $w = new FakeWhmcs();
fails(fn() => handle($db, $w, state(['uid' => 'bad uid']), $settings, $now), 'BAD_REQUEST', 'a bad uid is refused');
fails(fn() => handle($db, $w, state(['featureKey' => 'Bad Key']), $settings, $now), 'BAD_REQUEST', 'a bad feature key is refused');
fails(fn() => handle($db, $w, state(['type' => 'gold']), $settings, $now), 'BAD_REQUEST', 'a bad type is refused');
fails(fn() => handle($db, $w, state(['version' => 0]), $settings, $now), 'BAD_REQUEST', 'a non-positive version is refused');
fails(fn() => handle($db, $w, ['action' => 'nope'], $settings, $now), 'BAD_REQUEST', 'an unknown action is refused');
check($w->mutations() === [], 'validation never touches WHMCS');

// --- No linked client yet: the entitlement is recorded but held, nothing is created ---------------
$db = db(); $w = new FakeWhmcs();
$a = handle($db, $w, state(), $settings, $now);   // alice@example.com is not an existing WHMCS client
check($a['status'] === 'needs_client' && $a['clientId'] === null && $a['serviceId'] === null, 'with no link and no matching client: needs_client');
check($w->mutations() === [], 'needs_client creates nothing in WHMCS');
$row = $db->query("SELECT * FROM mod_terminow_store_sync WHERE uid='alice' AND feature_key='terminow-premium'")->fetch(PDO::FETCH_ASSOC);
check((int)$row['sandbox'] === 1 && (int)$row['active'] === 1 && $row['service_id'] === null, 'the held row is sandbox, active, serviceless');

// --- An existing client with the email is NEVER merged: it waits for the one-time link code --------
$db = db(); $w = new FakeWhmcs();
$a = handle($db, $w, state(['uid' => 'bob', 'email' => 'existing@example.com']), $settings, $now);
check($a['status'] === 'needs_link' && $a['clientId'] === null, 'an existing email is not taken over: needs_link');
check($w->mutations() === [], 'needs_link creates nothing in WHMCS');

// --- Once the uid<->client link exists, the entitlement is recorded and associated, no service -----
$db = db(); $w = new FakeWhmcs();
link_client($db, 'alice', 10, $now);
$a = handle($db, $w, state(['version' => 2]), $settings, $now);
check($a['status'] === 'recorded' && $a['clientId'] === 10 && $a['serviceId'] === null && $a['sandbox'] === true && $a['active'] === true,
    'a linked account records the entitlement against its client, with no service');
check($w->mutations() === [], 'recording a sandbox purchase never bills or activates a service');
check($w->calls === ['GetClientsDetails'] || $w->calls === [], 'only a read (or nothing) hits WHMCS');

// --- Several feature keys per account coexist (Premium + add-on) -----------------------------------
$a = handle($db, $w, state(['featureKey' => 'addon-network-scan', 'type' => 'addon', 'whmcsProductId' => null, 'whmcsAddonId' => 5, 'expiresAt' => null, 'storeProductId' => 'addon-network-scan']), $settings, $now);
check($a['status'] === 'recorded' && $a['clientId'] === 10, 'an add-on for the same account is recorded too');
$count = (int)$db->query("SELECT COUNT(*) FROM mod_terminow_store_sync WHERE uid='alice'")->fetchColumn();
check($count === 2, 'premium and add-on are two rows for the one account');

// --- Lifecycle: active -> expired is reflected on the row ------------------------------------------
$a = handle($db, $w, state(['version' => 3, 'active' => false]), $settings, $now + 86400 * 31);
check($a['status'] === 'recorded' && $a['active'] === false, 'an expiry turns the entitlement inactive');
check($w->mutations() === [], 'the lifecycle update never bills or activates');

// --- Idempotency & ordering ------------------------------------------------------------------------
$before = $w->calls;
$a = handle($db, $w, state(['version' => 3, 'active' => false]), $settings, $now + 86400 * 31);
check($a['active'] === false && $w->calls === $before, 'the same version is a no-op');
$a = handle($db, $w, state(['version' => 2, 'active' => true]), $settings, $now);   // older than 3
check($a['active'] === false, 'an older state arriving late is ignored');

// --- Sandbox isolation: Devnet can never masquerade as a real purchase ----------------------------
$db = db(); $w = new FakeWhmcs(); link_client($db, 'carl', 10, $now);
handle($db, $w, state(['uid' => 'carl', 'sandbox' => false]), $settings, $now);   // no allowRealGrants
$row = $db->query("SELECT sandbox FROM mod_terminow_store_sync WHERE uid='carl'")->fetch(PDO::FETCH_ASSOC);
check((int)$row['sandbox'] === 1, 'sandbox:false is forced to sandbox unless real grants are allowed');
handle($db, $w, state(['uid' => 'carl', 'version' => 2, 'sandbox' => false]), ['allowRealGrants' => true], $now);
$row = $db->query("SELECT sandbox, service_id FROM mod_terminow_store_sync WHERE uid='carl'")->fetch(PDO::FETCH_ASSOC);
check((int)$row['sandbox'] === 0 && $row['service_id'] === null, 'with real grants allowed the flag is honored, still no service in this phase');
check($w->mutations() === [], 'even an allowed real grant creates no service in this phase');

// --- Admin dashboard: held/failed rows are surfaced and a Retry re-resolves after linking ---------
$db = db(); $w = new FakeWhmcs();
$a = handle($db, $w, state(), $settings, $now);   // alice unlinked -> held (needs_client)
check($a['status'] === 'needs_client', 'retry fixture starts held');
$pending = pending_rows($db);
check(count($pending) === 1 && $pending[0]['uid'] === 'alice', 'a held row is listed for admin attention');
fails(fn() => retry($db, $w, 'nobody', 'terminow-premium', $settings, $now), 'NOT_FOUND', 'retry on an unknown entitlement fails cleanly');
$a = retry($db, $w, 'alice', 'terminow-premium', $settings, $now);   // still unlinked
check($a['status'] === 'needs_client', 'retry before linking stays held');
link_client($db, 'alice', 10, $now);
$a = retry($db, $w, 'alice', 'terminow-premium', $settings, $now);   // now linked
check($a['status'] === 'recorded' && $a['clientId'] === 10 && $a['serviceId'] === null, 'retry after linking records and associates, no service');
check($w->mutations() === [], 'retry never bills or activates a WHMCS service');
check(pending_rows($db) === [], 'once recorded, nothing remains for admin attention');

// --- The Premium decision excludes Store services (always empty in sandbox) ------------------------
check(store_service_ids($db) === [], 'no Store services exist to count toward Premium');

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
