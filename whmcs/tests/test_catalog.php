<?php
// php whmcs/tests/test_catalog.php — the WHMCS catalog authority: mapping, validation, and the
// server-to-store catalog composition that reuses native WHMCS pricing.

require __DIR__ . '/../terminow/catalog_lib.php';

use TermiNow\Catalog\Invalid;
use TermiNow\Catalog\Unauthorized;
use function TermiNow\Catalog\verify_signature;
use function TermiNow\Catalog\all;
use function TermiNow\Catalog\by_product_id;
use function TermiNow\Catalog\catalog_for_store;
use function TermiNow\Catalog\migrate;
use function TermiNow\Catalog\remove;
use function TermiNow\Catalog\upsert;
use function TermiNow\Catalog\validate;
use function TermiNow\Catalog\normalize;
use function TermiNow\Catalog\tnai_to_base_units;
use function TermiNow\Catalog\tnai_from_base_units;
use function TermiNow\Catalog\by_feature;
use function TermiNow\Catalog\preview_sandbox_migration;
use function TermiNow\Catalog\apply_sandbox_migration;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}
function invalid(callable $call, string $needle, string $name): void
{
    try { $call(); check(false, $name); }
    catch (Invalid $e) { check(stripos($e->getMessage(), $needle) !== false, $name . ' (' . $e->getMessage() . ')'); }
}

function db(): PDO
{
    $db = new PDO('sqlite::memory:');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    migrate($db);
    return $db;
}

function unauth(callable $call, string $code, string $name): void
{
    try { $call(); check(false, $name); }
    catch (Unauthorized $e) { check($e->errorCode === $code, $name . ' (' . $e->errorCode . ')'); }
}

$now = 1800000000;

// --- Authenticated server-to-server access --------------------------------------------------------
$secret = str_repeat('c', 40);
$body = '{"locale":"en"}';
$sig = hash_hmac('sha256', $now . '.' . $body, $secret);
try { verify_signature($secret, (string)$now, $sig, $body, $now); check(true, 'a signed catalog request is accepted'); }
catch (Unauthorized $e) { check(false, 'a signed catalog request is accepted'); }
unauth(fn() => verify_signature($secret, (string)$now, $sig, $body . 'x', $now), 'BAD_SIGNATURE', 'a tampered catalog body is refused');
unauth(fn() => verify_signature($secret, (string)($now - 1000), hash_hmac('sha256', ($now - 1000) . '.' . $body, $secret), $body, $now), 'BAD_SIGNATURE', 'an old catalog request is refused');
unauth(fn() => verify_signature('short', (string)$now, $sig, $body, $now), 'WHMCS_NOT_CONFIGURED', 'no catalog secret, no feed');

// --- Validation: exactly one mapping, type rules, parent requirement ------------------------------
check(validate(normalize(['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90]), []) === [], 'a premium mapped to a product is valid');
$errs = validate(normalize(['feature_key' => 'x', 'type' => 'premium', 'whmcs_product_id' => 90, 'whmcs_addon_id' => 5]), []);
check(count($errs) >= 1, 'mapping to both a product and an addon is rejected');
$errs = validate(normalize(['feature_key' => 'x', 'type' => 'premium']), []);
check(count($errs) >= 1, 'mapping to neither is rejected');
$errs = validate(normalize(['feature_key' => 'x', 'type' => 'premium', 'whmcs_addon_id' => 5]), []);
check(in_array('A Premium entry must map to a WHMCS product, not a product-addon', $errs, true), 'a premium cannot map to a product-addon');
check(validate(normalize(['feature_key' => 'BAD KEY', 'type' => 'premium', 'whmcs_product_id' => 1]), []) !== [], 'a bad feature key is rejected');

// Parent requirement
$others = ['terminow-premium' => ['feature_key' => 'terminow-premium', 'type' => 'premium']];
check(validate(normalize(['feature_key' => 'addon-scan', 'type' => 'addon', 'whmcs_addon_id' => 5, 'parent_feature_key' => 'terminow-premium']), $others) === [], 'an addon requiring an existing premium parent is valid');
$errs = validate(normalize(['feature_key' => 'addon-scan', 'type' => 'addon', 'whmcs_addon_id' => 5, 'parent_feature_key' => 'nope']), $others);
check(in_array('Parent feature "nope" does not exist', $errs, true), 'a missing parent is rejected');
$others2 = ['addon-other' => ['feature_key' => 'addon-other', 'type' => 'addon']];
$errs = validate(normalize(['feature_key' => 'addon-scan', 'type' => 'addon', 'whmcs_addon_id' => 5, 'parent_feature_key' => 'addon-other']), $others2);
check(in_array('Parent feature "addon-other" must be a Premium entry', $errs, true), 'a non-premium parent is rejected');
$errs = validate(normalize(['feature_key' => 'premium', 'type' => 'premium', 'whmcs_product_id' => 1, 'store_visible' => true]), []);
check(in_array('A Store-visible entry needs a display name', $errs, true), 'a visible entry without a name is rejected');

// --- Upsert, uniqueness, update, delete -----------------------------------------------------------
$db = db();
$p = upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90, 'store_visible' => true, 'display_name' => 'TermiNow Premium', 'sandbox_price_tnai' => '50'], $now);
check($p['feature_key'] === 'terminow-premium' && (int)$p['whmcs_product_id'] === 90, 'premium entry is stored');
invalid(fn() => upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 91], $now), 'already uses', 'a duplicate feature key is rejected');
$a = upsert($db, ['feature_key' => 'addon-network-scan', 'type' => 'addon', 'whmcs_addon_id' => 7, 'parent_feature_key' => 'terminow-premium', 'store_visible' => true, 'display_name' => 'Network Scan'], $now);
check($a['parent_feature_key'] === 'terminow-premium', 'addon with parent is stored');
$p2 = upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90, 'store_visible' => true, 'display_name' => 'TermiNow Premium Plan'], $now + 10, (int)$p['id']);
check($p2['display_name'] === 'TermiNow Premium Plan', 'an edit updates in place');
check(count(all($db)) === 2, 'two entries exist');
invalid(fn() => remove($db, (int)$p['id']), 'still requires this feature', 'a parent cannot be deleted while an addon needs it');
remove($db, (int)$a['id']);
remove($db, (int)$p['id']);
check(all($db) === [], 'once the child is gone the parent deletes');

// --- by_product_id ---------------------------------------------------------------------------------
$db = db();
upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90, 'store_visible' => true, 'display_name' => 'Premium'], $now);
$map = by_product_id($db);
check(isset($map[90]) && $map[90]['feature_key'] === 'terminow-premium', 'by_product_id indexes product entries');

// --- catalog_for_store: native pricing from the lookup, visibility, unavailable, i18n, sandbox -----
$db = db();
upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90, 'store_visible' => true, 'duration_days' => 30,
    'display_name' => 'TermiNow Premium', 'display_name_i18n' => ['th' => 'เทอร์มิพรีเมียม'], 'sandbox_price_tnai' => '50'], $now);
upsert($db, ['feature_key' => 'addon-network-scan', 'type' => 'addon', 'whmcs_addon_id' => 7, 'parent_feature_key' => 'terminow-premium',
    'store_visible' => true, 'display_name' => 'Network Scan'], $now);
upsert($db, ['feature_key' => 'hidden-thing', 'type' => 'addon', 'whmcs_addon_id' => 9, 'store_visible' => false, 'display_name' => 'Hidden'], $now);
$prices = [90 => ['amount' => '12.00', 'currency' => 'USD', 'billingCycle' => 'monthly']];
$lookup = function (array $row) use ($prices) {
    if ($row['whmcs_product_id'] !== null) return $prices[(int)$row['whmcs_product_id']] ?? null; // product has WHMCS pricing
    return null;                                                                                   // addon price unavailable in this fake
};
$cat = catalog_for_store($db, $lookup);
check(count($cat['items']) === 2, 'only visible entries are served');
$prem = $cat['items'][array_search('terminow-premium', array_column($cat['items'], 'featureKey'))];
check($prem['price']['amount'] === '12.00' && $prem['price']['billingCycle'] === 'monthly' && $prem['available'] === true, 'native WHMCS price and cycle are reused');
check($prem['sandboxPrice']['amount'] === '50000000' && $prem['sandboxPrice']['currency'] === 'TNAI', 'the sandbox TNAI price (50 TNAI) is served as 50000000 base units');
check($prem['durationDays'] === 30, 'the subscription duration in days is served');
$scan = $cat['items'][array_search('addon-network-scan', array_column($cat['items'], 'featureKey'))];
check($scan['available'] === false && $scan['unavailableReason'] !== null, 'an entry without WHMCS pricing is marked unavailable, not dropped');
check($scan['parentFeatureKey'] === 'terminow-premium', 'the add-on carries its parent requirement');
$th = catalog_for_store($db, $lookup, 'th-TH');
$premTh = $th['items'][array_search('terminow-premium', array_column($th['items'], 'featureKey'))];
check($premTh['displayName'] === 'เทอร์มิพรีเมียม', 'a localized display name is used when present (locale falls back to language)');

// --- Sandbox price: exact human-TNAI -> integer base-unit conversion (6 decimals, no float) --------
check(tnai_to_base_units('5') === '5000000', '"5" TNAI -> 5000000 base units');
check(tnai_to_base_units('5.0') === '5000000', '"5.0" converts (is NOT rejected or hidden)');
check(tnai_to_base_units('5.5') === '5500000', '"5.5" -> 5500000');
check(tnai_to_base_units('0.000001') === '1', 'one base unit (0.000001 TNAI) -> 1');
check(tnai_to_base_units('5.123456') === '5123456', 'exactly 6 decimals is allowed');
check(tnai_to_base_units('5.1234567') === null, '7 decimals is rejected (more precision than the token)');
check(tnai_to_base_units('-5') === null, 'a negative amount is rejected');
check(tnai_to_base_units('abc') === null, 'non-numeric is rejected');
check(tnai_to_base_units('5e6') === null, 'exponent notation is rejected');
check(tnai_to_base_units('0') === '0', 'zero converts to "0" (the caller rejects it)');
// Classic float traps that string math must get exactly right:
check(tnai_to_base_units('0.1') === '100000' && tnai_to_base_units('0.3') === '300000', '0.1 and 0.3 are exact (no float drift)');
check(tnai_from_base_units('5000000') === '5' && tnai_from_base_units('5500000') === '5.5' && tnai_from_base_units('1') === '0.000001', 'base units render back to a human amount');

// Save-time: a human amount is stored canonically as base units; an over-precise price is rejected visibly.
$db = db();
$r = upsert($db, ['feature_key' => 'terminow-premium', 'type' => 'premium', 'whmcs_product_id' => 90, 'store_visible' => true, 'display_name' => 'P', 'sandbox_price_tnai' => '5.0'], $now);
check($r['sandbox_price_tnai'] === '5000000', 'upsert stores "5.0" as 5000000 base units');
invalid(fn() => upsert($db, ['feature_key' => 'terminow-vault', 'type' => 'premium', 'whmcs_product_id' => 91, 'store_visible' => true, 'display_name' => 'V', 'sandbox_price_tnai' => '5.1234567'], $now), 'at most 6 decimals', 'an over-precise sandbox price is rejected before saving');
invalid(fn() => upsert($db, ['feature_key' => 'terminow-zero', 'type' => 'premium', 'whmcs_product_id' => 92, 'store_visible' => true, 'display_name' => 'Z', 'sandbox_price_tnai' => '0'], $now), 'greater than zero', 'a zero sandbox price is rejected');

// --- Migration preview for existing rows (read-only; decimals convert, integers assumed base units)
$db = db();
// Simulate legacy rows written by the old code: one human decimal, one already base units, one invalid.
$db->exec("INSERT INTO mod_terminow_store_catalog (feature_key,type,whmcs_product_id,store_visible,display_name,sandbox_price_tnai,sort_order,created_at,updated_at) VALUES "
    . "('legacy-decimal','premium',93,1,'D','5.0',0,$now,$now),"
    . "('legacy-base','premium',94,1,'B','7500000',0,$now,$now),"
    . "('legacy-bad','premium',95,1,'X','1.2345678',0,$now,$now)");
$prev = preview_sandbox_migration($db);
$byf = [];
foreach ($prev as $p) $byf[$p['feature_key']] = $p;
check($byf['legacy-decimal']['status'] === 'convert' && $byf['legacy-decimal']['to'] === '5000000', 'preview converts a legacy decimal "5.0" -> 5000000');
check($byf['legacy-base']['status'] === 'ok' && $byf['legacy-base']['to'] === '7500000', 'preview leaves an integer value as already-base-units');
check($byf['legacy-bad']['status'] === 'invalid', 'preview flags an unconvertible value for manual re-entry');
$changed = apply_sandbox_migration($db, $now + 1);
check($changed === 1 && by_feature($db, 'legacy-decimal')['sandbox_price_tnai'] === '5000000', 'apply converts only the decimal row, leaving the others untouched');

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
