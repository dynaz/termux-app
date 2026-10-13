<?php
// php whmcs/tests/test_play.php — tests of TermiNow Premium bought with Google Play, without WHMCS or Google.

require __DIR__ . '/../terminow/play_lib.php';

use TermiNow\Play\Failure;
use TermiNow\Play\Google;
use function TermiNow\Play\account_id;
use function TermiNow\Play\combine;
use function TermiNow\Play\evaluate;
use function TermiNow\Play\migrate;
use function TermiNow\Play\premium;
use function TermiNow\Play\register;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}

function fails(callable $call, int $status, string $name): void
{
    try {
        $call();
        check(false, $name);
    } catch (Failure $e) {
        check($e->status === $status, $name . ' (' . $e->getMessage() . ')');
    }
}

$now = 1800000000;
function subscription(string $uid, string $state, int $expiry, array $more = []): array
{
    return $more + [
        'subscriptionState' => $state,
        'acknowledgementState' => 'ACKNOWLEDGEMENT_STATE_PENDING',
        'externalAccountIdentifiers' => ['obfuscatedExternalAccountId' => account_id($uid)],
        'lineItems' => [['productId' => 'terminow_premium', 'expiryTime' => gmdate('Y-m-d\TH:i:s.000\Z', $expiry),
            'autoRenewingPlan' => ['autoRenewEnabled' => $state === 'SUBSCRIPTION_STATE_ACTIVE']]],
    ];
}

// evaluate
$r = evaluate(subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now + 86400), 'alice', $now);
check($r['entitled'] && $r['autoRenewing'] && $r['expiry'] === $now + 86400 && !$r['acknowledged'], 'active subscription is Premium');
check(evaluate(subscription('alice', 'SUBSCRIPTION_STATE_CANCELED', $now + 86400), 'alice', $now)['entitled'], 'cancelled stays Premium until it expires');
check(evaluate(subscription('alice', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', $now + 86400), 'alice', $now)['entitled'], 'grace period is Premium');
check(!evaluate(subscription('alice', 'SUBSCRIPTION_STATE_ON_HOLD', $now + 86400), 'alice', $now)['entitled'], 'on hold is not Premium');
check(!evaluate(subscription('alice', 'SUBSCRIPTION_STATE_PENDING', $now + 86400), 'alice', $now)['entitled'], 'pending payment is not Premium');
check(!evaluate(subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now - 1), 'alice', $now)['entitled'], 'past expiry is not Premium');
fails(fn() => evaluate(subscription('bob', 'SUBSCRIPTION_STATE_ACTIVE', $now + 86400), 'alice', $now), 403, 'bought for another account');
fails(fn() => evaluate(array_diff_key(subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now + 86400), ['externalAccountIdentifiers' => 1]), 'alice', $now), 403, 'bought without an account');
$other = subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now + 86400);
$other['lineItems'][0]['productId'] = 'something_else';
fails(fn() => evaluate($other, 'alice', $now), 400, 'another product');
check(strlen(account_id('alice')) === 64 && strpos(account_id('alice'), 'alice') === false, 'account id is a 64 character hash');

// register and premium, with a fake Google
$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);
$google_state = ['tokA' => subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now + 30 * 86400)];
$calls = [];
$down = false;
$google = new Google(function (string $method, string $url, ?string $body) use (&$google_state, &$calls, &$down): array {
    $calls[] = $method . ' ' . $url;
    if ($down) return [500, null];
    if (!preg_match('~/tokens/([^/:]+)(:acknowledge)?$~', $url, $m)) return [400, null];
    $token = rawurldecode($m[1]);
    if (!isset($google_state[$token])) return [410, null];
    if (!empty($m[2])) {
        $google_state[$token]['acknowledgementState'] = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
        return [200, []];
    }
    return [200, $google_state[$token]];
});

fails(fn() => register($db, $google, 'alice', 'bad token!', $now), 400, 'malformed token');
fails(fn() => register($db, $google, 'alice', 'unknown', $now), 400, 'token Google does not know');
$r = register($db, $google, 'alice', 'tokA', $now);
check($r['entitled'], 'register an active purchase');
check($google_state['tokA']['acknowledgementState'] === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED', 'the purchase is acknowledged');
check(count(array_filter($calls, fn($c) => str_contains($c, ':acknowledge'))) === 1, 'acknowledged once');
register($db, $google, 'alice', 'tokA', $now);
check(count(array_filter($calls, fn($c) => str_contains($c, ':acknowledge'))) === 1, 'an acknowledged purchase is not acknowledged again');
fails(fn() => register($db, $google, 'bob', 'tokA', $now), 403, 'another account can not register the same token');
check(!premium($db, $google, 'bob', $now)['premium'], 'the other account has no Premium');

$calls = [];
$p = premium($db, $google, 'alice', $now + 60);
check($p['premium'] && $p['autoRenewing'] && $p['expiry'] === $now + 30 * 86400, 'Premium from the kept purchase');
check($calls === [], 'a fresh check is not repeated with Google');

// Google is down: the last answer stays
$down = true;
check(premium($db, $google, 'alice', $now + 2 * 86400)['premium'], 'Google failing keeps the last answer');
$down = false;

// Renewed: after expiry the server asks Google and sees the new expiry
$google_state['tokA'] = subscription('alice', 'SUBSCRIPTION_STATE_ACTIVE', $now + 60 * 86400, ['acknowledgementState' => 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED']);
$p = premium($db, $google, 'alice', $now + 31 * 86400);
check($p['premium'] && $p['expiry'] === $now + 60 * 86400, 'a renewal is seen after the old expiry');

// Refunded and revoked: Google says expired at the next daily check
$google_state['tokA'] = subscription('alice', 'SUBSCRIPTION_STATE_EXPIRED', $now + 32 * 86400, ['acknowledgementState' => 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED']);
check(!premium($db, $google, 'alice', $now + 33 * 86400)['premium'], 'a revoked subscription ends Premium');
check(!premium($db, null, 'alice', $now + 33 * 86400)['premium'], 'and stays ended without Google');

// combine
$whmcs = ['premium' => true, 'plan' => 'TermiNow Premium', 'nextDueDate' => '2027-01-01'];
check(combine(['premium' => false], ['premium' => false]) === ['premium' => false, 'source' => null], 'neither');
check(combine($whmcs, ['premium' => false])['source'] === 'accmove', 'only accmove');
$c = combine(['premium' => false], ['premium' => true, 'expiry' => $now, 'autoRenewing' => true]);
check($c['premium'] && $c['source'] === 'play' && $c['nextDueDate'] === null, 'auto-renewing Play has no due date');
$c = combine(['premium' => false], ['premium' => true, 'expiry' => gmmktime(12, 0, 0, 3, 4, 2027), 'autoRenewing' => false]);
check($c['nextDueDate'] === '2027-03-04', 'cancelled Play ends on its expiry date');
check(combine($whmcs, ['premium' => true, 'expiry' => gmmktime(12, 0, 0, 3, 4, 2027), 'autoRenewing' => false])['source'] === 'play', 'both: the one that ends later');
check(combine($whmcs, ['premium' => true, 'expiry' => $now, 'autoRenewing' => true])['source'] === 'play', 'both: the one without an end');

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
