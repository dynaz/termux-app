<?php
// php whmcs/tests/test_play_sync.php — the WHMCS side of Google Play subscriptions, with a fake WHMCS.

require __DIR__ . '/../terminow/lib.php';
require __DIR__ . '/../terminow/play_sync_lib.php';

use TermiNow\PlaySync\Failure;
use function TermiNow\PlaySync\create_link_code;
use function TermiNow\PlaySync\handle;
use function TermiNow\PlaySync\migrate;
use function TermiNow\PlaySync\retry;
use function TermiNow\PlaySync\verify_signature;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}

function fails(callable $call, string $code, string $name): void
{
    try {
        $call();
        check(false, $name);
    } catch (Failure $e) {
        check($e->errorCode === $code, $name . ' (' . $e->errorCode . ')');
    }
}

/** A WHMCS with the local API commands that are used, which records every call. */
class FakeWhmcs
{
    public array $calls = [];
    public array $clients = [10 => 'existing@example.com'];
    public array $services = [];
    public array $products = [7 => 'free', 8 => 'recurring'];
    public ?string $failOnce = null;
    private int $nextClient = 100;
    private int $nextService = 500;

    public function __invoke(string $command, array $params): array
    {
        $this->calls[] = $command;
        if ($this->failOnce === $command) {
            $this->failOnce = null;
            throw new RuntimeException('WHMCS crashed during ' . $command);
        }
        switch ($command) {
            case 'GetProducts':
                $paytype = $this->products[$params['pid']] ?? null;
                return $paytype === null ? ['result' => 'success', 'products' => ['product' => []]] :
                    ['result' => 'success', 'products' => ['product' => [['pid' => $params['pid'], 'paytype' => $paytype]]]];
            case 'GetClientsDetails':
                $id = array_search($params['email'], $this->clients, true);
                return $id === false ? ['result' => 'error', 'message' => 'Client Not Found'] : ['result' => 'success', 'client' => ['id' => $id]];
            case 'AddClient':
                $id = $this->nextClient++;
                $this->clients[$id] = $params['email'];
                return ['result' => 'success', 'clientid' => $id];
            case 'AddOrder':
                $id = $this->nextService++;
                $this->services[$id] = ['client' => $params['clientid'], 'pid' => $params['pid'][0], 'cycle' => $params['billingcycle'][0],
                    'noinvoice' => $params['noinvoice'], 'status' => 'Pending', 'notes' => ''];
                return ['result' => 'success', 'orderid' => $id + 1000, 'serviceids' => (string)$id];
            case 'AcceptOrder':
                return ['result' => 'success'];
            case 'GetClientsProducts':
                $list = [];
                foreach ($this->services as $id => $s)
                    if ($s['client'] === $params['clientid'] && $s['pid'] === $params['pid']) $list[] = ['id' => $id, 'notes' => $s['notes']];
                return ['result' => 'success', 'products' => ['product' => $list]];
            case 'UpdateClientProduct':
                foreach (['status', 'notes', 'nextduedate'] as $key)
                    if (isset($params[$key])) $this->services[$params['serviceid']][$key] = $params[$key];
                return ['result' => 'success'];
        }
        throw new RuntimeException('Unexpected command ' . $command);
    }

    public function count(string $command): int
    {
        return count(array_filter($this->calls, fn($c) => $c === $command));
    }
}

function db(): PDO
{
    $db = new PDO('sqlite::memory:');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    migrate($db);
    return $db;
}

$now = 1800000000;
$settings = ['paymentMethod' => 'banktransfer'];
function state(array $more = []): array
{
    global $now;
    return $more + ['action' => 'sync', 'uid' => 'alice', 'version' => 1, 'email' => 'alice@example.com', 'whmcsProductId' => 7,
        'subscriptionState' => 'SUBSCRIPTION_STATE_ACTIVE', 'premium' => true, 'expiresAt' => ($now + 86400 * 30) * 1000,
        'autoRenew' => true, 'test' => false, 'productId' => 'terminow_premium', 'basePlanId' => 'monthly'];
}

// Signatures
$secret = str_repeat('k', 40);
$body = '{"action":"sync"}';
$signature = hash_hmac('sha256', $now . '.' . $body, $secret);
try {
    verify_signature($secret, (string)$now, $signature, $body, $now);
    check(true, 'a signed request is accepted');
} catch (Failure $e) {
    check(false, 'a signed request is accepted');
}
fails(fn() => verify_signature($secret, (string)$now, $signature, $body . ' ', $now), 'BAD_SIGNATURE', 'a changed body is refused');
fails(fn() => verify_signature($secret, (string)($now - 600), hash_hmac('sha256', ($now - 600) . '.' . $body, $secret), $body, $now), 'BAD_SIGNATURE', 'an old request is refused');
fails(fn() => verify_signature('', (string)$now, $signature, $body, $now), 'WHMCS_NOT_CONFIGURED', 'no secret, no sync');

// A new subscriber: one client, one free service, no invoice, whatever the retries
$db = db();
$whmcs = new FakeWhmcs();
$answer = handle($db, $whmcs, state(), $settings, $now);
check($answer['status'] === 'synced' && $answer['clientId'] === 100 && $answer['serviceId'] === 500, 'a subscriber gets a client and a service');
$service = $whmcs->services[500];
check($service['cycle'] === 'free' && $service['noinvoice'] === true, 'the service is free and ordered without an invoice');
check($service['status'] === 'Active' && strpos($service['notes'], 'Managed by Google Play') !== false, 'the service is active and marked as Play-managed');
handle($db, $whmcs, state(), $settings, $now);
handle($db, $whmcs, state(['version' => 2]), $settings, $now);
check($whmcs->count('AddClient') === 1 && $whmcs->count('AddOrder') === 1, 'retries never add a second client or service');
check(count(array_intersect($whmcs->calls, ['CreateInvoice', 'GenInvoices', 'AddInvoicePayment', 'UpdateInvoice'])) === 0, 'no invoice is created or paid');

// The lifecycle reaches WHMCS
handle($db, $whmcs, state(['version' => 3, 'subscriptionState' => 'SUBSCRIPTION_STATE_ON_HOLD', 'premium' => false]), $settings, $now);
check($whmcs->services[500]['status'] === 'Suspended', 'on hold suspends the service');
handle($db, $whmcs, state(['version' => 4, 'subscriptionState' => 'SUBSCRIPTION_STATE_EXPIRED', 'premium' => false]), $settings, $now);
check($whmcs->services[500]['status'] === 'Cancelled', 'expired cancels the service');
$before = count($whmcs->calls);
handle($db, $whmcs, state(['version' => 2]), $settings, $now);
check(count($whmcs->calls) === $before && $whmcs->services[500]['status'] === 'Cancelled', 'an older state arriving late is ignored');

// A crash after the order was placed: the retry finds the service by its marker
$db = db();
$whmcs = new FakeWhmcs();
$whmcs->failOnce = 'AcceptOrder';
try {
    handle($db, $whmcs, state(['uid' => 'bob', 'email' => 'bob@example.com']), $settings, $now);
    check(false, 'the crash is reported');
} catch (RuntimeException $e) {
    check(true, 'the crash is reported, so Firebase retries');
}
handle($db, $whmcs, state(['uid' => 'bob', 'email' => 'bob@example.com']), $settings, $now);
check($whmcs->count('AddOrder') === 1 && count($whmcs->services) === 1, 'the retry adopts the service instead of ordering again');

// Never merged by email: linked with a code from the client area
$db = db();
$whmcs = new FakeWhmcs();
$answer = handle($db, $whmcs, state(['uid' => 'carol', 'email' => 'existing@example.com']), $settings, $now);
check($answer['status'] === 'needs_link' && $whmcs->count('AddClient') === 0 && $whmcs->count('AddOrder') === 0, 'an existing client with the email is not taken over');
$code = create_link_code($db, 10, $now);
check(preg_match('/^[A-Z0-9]{8}$/', $code) === 1, 'a link code has 8 characters');
fails(fn() => handle($db, $whmcs, ['action' => 'redeem_link', 'uid' => 'carol', 'code' => 'WRONG123'], $settings, $now), 'INVALID_LINK_CODE', 'a wrong code is refused');
fails(fn() => handle($db, $whmcs, ['action' => 'redeem_link', 'uid' => 'carol', 'code' => $code], $settings, $now + 601), 'INVALID_LINK_CODE', 'an expired code is refused');
check(handle($db, $whmcs, ['action' => 'redeem_link', 'uid' => 'carol', 'code' => strtolower($code)], $settings, $now)['clientId'] === 10, 'the code links the client');
fails(fn() => handle($db, $whmcs, ['action' => 'redeem_link', 'uid' => 'mallory', 'code' => $code], $settings, $now), 'INVALID_LINK_CODE', 'a code works only once');
$answer = handle($db, $whmcs, state(['uid' => 'carol', 'email' => 'existing@example.com', 'version' => 2]), $settings, $now);
check($answer['clientId'] === 10 && $answer['status'] === 'synced' && $whmcs->count('AddClient') === 0, 'the linked client gets the service');
$other = create_link_code($db, 11, $now);
fails(fn() => handle($db, $whmcs, ['action' => 'redeem_link', 'uid' => 'carol', 'code' => $other], $settings, $now), 'LINK_CONFLICT', 'a linked account is not moved to another client');

// Test purchases and products that would invoice
$db = db();
$whmcs = new FakeWhmcs();
$answer = handle($db, $whmcs, state(['uid' => 'tess', 'email' => 'tess@example.com', 'test' => true]), $settings, $now);
check($answer['status'] === 'test_only' && $whmcs->count('AddOrder') === 0 && $whmcs->count('AddClient') === 0, 'test purchases stay out of the financial records');
fails(fn() => handle($db, $whmcs, state(['uid' => 'paula', 'email' => 'paula@example.com', 'whmcsProductId' => 8]), $settings, $now),
    'WHMCS_PRODUCT_NOT_FREE', 'a product that would invoice is refused');
check($whmcs->count('AddOrder') === 0, 'and nothing is ordered for it');
$row = $db->query("SELECT status, last_error FROM mod_terminow_play_sync WHERE uid = 'paula'")->fetch(PDO::FETCH_ASSOC);
check($row['status'] === 'error' && strpos($row['last_error'], 'Free') !== false, 'the error is kept for the admin view');
$whmcs->products[8] = 'free';
check(retry($db, $whmcs, 'paula', $settings, $now)['status'] === 'synced', 'Retry sync applies the last state again');

// The Firestore entitlement, as vault.php reads it
$doc = fn(bool $premium, array $sources) => ['fields' => ['premium' => ['booleanValue' => $premium], 'sources' => ['mapValue' => ['fields' => $sources]]]];
$play = fn(bool $premium, int $expires) => ['mapValue' => ['fields' => ['premium' => ['booleanValue' => $premium], 'expiresAt' => ['integerValue' => (string)$expires]]]];
$nowMs = $now * 1000;
check(\TermiNow\Premium\firestore_premium($doc(true, ['google_play' => $play(true, $nowMs + 1000)]), $nowMs), 'an active Play entitlement is Premium');
check(!\TermiNow\Premium\firestore_premium($doc(true, ['google_play' => $play(true, $nowMs - 1000)]), $nowMs), 'past its verified expiry it is not');
check(!\TermiNow\Premium\firestore_premium(null, $nowMs), 'no document, no Premium');

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
