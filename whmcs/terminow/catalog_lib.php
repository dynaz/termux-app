<?php
/**
 * TermiNow Store catalog, the WHMCS side: the logic behind the admin catalog UI and the
 * server-to-server catalog endpoint, without WHMCS, so it can be tested on its own.
 *
 * WHMCS (cp.accmove.com) is the source of truth for the production catalog, pricing and billing.
 * This module owns the Store-specific presentation of that catalog in its OWN tables, keyed by the
 * WHMCS product / product-addon id. It never duplicates WHMCS prices or billing cycles: those are
 * read from WHMCS at serve time (the $priceLookup callable) so the Store always reuses native WHMCS
 * pricing. The module only adds Store concerns: visibility, feature keys, localized display text and
 * a Devnet sandbox TNAI price.
 *
 * A catalog entry is one of:
 *   - type "premium": a Premium subscription, mapped to a WHMCS product id.
 *   - type "addon":   a feature add-on, mapped to a WHMCS product id OR a WHMCS product-addon id
 *                     (exactly one), and optionally requiring a parent Premium feature.
 */

namespace TermiNow\Catalog;

const TYPES = ['premium', 'addon'];
const SIGNATURE_WINDOW = 300;

// Devnet TNAI is an SPL token with 6 decimals, so 1 TNAI = 1_000_000 base units. The sandbox price is
// STORED and SERVED as an integer number of base units (what the Store charges and validates), but the
// admin enters a human-readable TNAI amount. Conversion is exact string arithmetic — never floating
// point, which cannot represent most decimals exactly.
const TNAI_DECIMALS = 6;

/**
 * Convert a human-readable TNAI amount ("5", "5.0", "5.5", "0.000001") to its exact integer base-unit
 * string, using string math only. Returns null when the input is not a non-negative decimal with at
 * most TNAI_DECIMALS fractional digits (the caller turns null into a visible validation error). The
 * result has no leading zeros; "0" is returned for a true zero (the caller decides if zero is allowed).
 */
function tnai_to_base_units(string $human): ?string
{
    $human = trim($human);
    if (!preg_match('/^[0-9]+(\.[0-9]+)?$/', $human)) return null;      // no sign, no exponent, no junk
    $parts = explode('.', $human, 2);
    $intPart = $parts[0];
    $fracPart = $parts[1] ?? '';
    if (strlen($fracPart) > TNAI_DECIMALS) return null;                 // more precision than the token has
    $fracPart = str_pad($fracPart, TNAI_DECIMALS, '0');                 // pad to exactly 6 base-unit digits
    $base = ltrim($intPart . $fracPart, '0');
    return $base === '' ? '0' : $base;
}

/** Render an integer base-unit string back to a human TNAI amount for the admin edit form. */
function tnai_from_base_units(string $base): string
{
    if (!preg_match('/^[0-9]+$/', $base)) return $base;                 // leave anything unexpected as-is
    $base = str_pad($base, TNAI_DECIMALS + 1, '0', STR_PAD_LEFT);       // ensure at least one integer digit
    $intPart = substr($base, 0, -TNAI_DECIMALS);
    $fracPart = rtrim(substr($base, -TNAI_DECIMALS), '0');
    $intPart = ltrim($intPart, '0');
    if ($intPart === '') $intPart = '0';
    return $fracPart === '' ? $intPart : $intPart . '.' . $fracPart;
}

/** Authenticated server-to-server access: HMAC-SHA256 of "<timestamp>.<body>", like the other endpoints. */
function verify_signature(string $secret, string $timestamp, string $signature, string $body, int $now): void
{
    if (strlen($secret) < 32) throw new Unauthorized('WHMCS_NOT_CONFIGURED', 'The catalog secret is not set in the TermiNow Play addon');
    if (!ctype_digit($timestamp) || abs($now - (int)$timestamp) > SIGNATURE_WINDOW)
        throw new Unauthorized('BAD_SIGNATURE', 'The request is too old or has no timestamp');
    $expected = hash_hmac('sha256', $timestamp . '.' . $body, $secret);
    if (!hash_equals($expected, strtolower($signature))) throw new Unauthorized('BAD_SIGNATURE', 'The signature is wrong');
}

class Unauthorized extends \Exception
{
    public string $errorCode;
    public function __construct(string $code, string $message)
    {
        parent::__construct($message);
        $this->errorCode = $code;
    }
}

class Invalid extends \Exception
{
    /** @var string[] */
    public array $errors;
    public function __construct(array $errors)
    {
        parent::__construct(implode('; ', $errors));
        $this->errors = $errors;
    }
}

function migrate(\PDO $db): void
{
    $auto = $db->getAttribute(\PDO::ATTR_DRIVER_NAME) === 'mysql' ? 'INTEGER PRIMARY KEY AUTO_INCREMENT' : 'INTEGER PRIMARY KEY AUTOINCREMENT';
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_store_catalog (
        id ' . $auto . ',
        feature_key VARCHAR(64) NOT NULL UNIQUE,
        type VARCHAR(16) NOT NULL,                 -- premium | addon
        whmcs_product_id INTEGER NULL,             -- a WHMCS product
        whmcs_addon_id INTEGER NULL,               -- or a WHMCS product-addon (exactly one is set)
        parent_feature_key VARCHAR(64) NULL,       -- an addon may require this Premium feature
        duration_days INTEGER NULL,                -- how long a grant lasts; NULL = permanent
        store_visible INTEGER NOT NULL DEFAULT 0,
        display_name VARCHAR(191) NULL,
        description TEXT NULL,
        display_name_i18n TEXT NULL,               -- JSON { "<locale>": "<name>" }
        description_i18n TEXT NULL,                -- JSON { "<locale>": "<description>" }
        sandbox_price_tnai VARCHAR(32) NULL,       -- Devnet TNAI price, sandbox only, never billed
        sort_order INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
}

function valid_feature_key($key): bool
{
    return is_string($key) && preg_match('/^[a-z0-9][a-z0-9-]{1,63}$/', $key) === 1;
}

function pos_int($v): ?int
{
    if (is_int($v)) return $v > 0 ? $v : null;
    if (is_string($v) && ctype_digit($v) && (int)$v > 0) return (int)$v;
    return null;
}

/**
 * Normalize raw admin input into a clean entry. JSON fields may arrive as arrays or strings.
 * Returns the normalized associative array (not yet validated).
 */
function normalize(array $in): array
{
    $json = function ($v): ?string {
        if ($v === null || $v === '' || $v === []) return null;
        if (is_string($v)) { $d = json_decode($v, true); return is_array($d) ? json_encode($d) : null; }
        return is_array($v) ? json_encode($v) : null;
    };
    $price = $in['sandbox_price_tnai'] ?? null;
    return [
        'feature_key' => is_string($in['feature_key'] ?? null) ? strtolower(trim($in['feature_key'])) : '',
        'type' => in_array($in['type'] ?? null, TYPES, true) ? $in['type'] : '',
        'whmcs_product_id' => pos_int($in['whmcs_product_id'] ?? null),
        'whmcs_addon_id' => pos_int($in['whmcs_addon_id'] ?? null),
        'parent_feature_key' => is_string($in['parent_feature_key'] ?? null) && trim($in['parent_feature_key']) !== ''
            ? strtolower(trim($in['parent_feature_key'])) : null,
        'duration_days' => ($in['duration_days'] ?? '') !== '' && $in['duration_days'] !== null ? (int)$in['duration_days'] : null,
        'store_visible' => ($in['store_visible'] ?? false) ? 1 : 0,
        'display_name' => isset($in['display_name']) && trim((string)$in['display_name']) !== '' ? substr(trim((string)$in['display_name']), 0, 191) : null,
        'description' => isset($in['description']) && trim((string)$in['description']) !== '' ? (string)$in['description'] : null,
        'display_name_i18n' => $json($in['display_name_i18n'] ?? null),
        'description_i18n' => $json($in['description_i18n'] ?? null),
        'sandbox_price_tnai' => ($price === null || $price === '') ? null : (string)$price,
        'sort_order' => (int)($in['sort_order'] ?? 0),
    ];
}

/**
 * Validate a normalized entry against the other catalog entries (keyed by feature_key, with the
 * row being edited removed). Returns a list of human-readable errors; empty means valid.
 */
function validate(array $e, array $others): array
{
    $err = [];
    if (!valid_feature_key($e['feature_key'])) $err[] = 'Feature key must be lowercase letters, digits and hyphens (2-64 chars)';
    if (!in_array($e['type'], TYPES, true)) $err[] = 'Type must be premium or addon';
    if (isset($others[$e['feature_key']])) $err[] = 'Another catalog entry already uses the feature key "' . $e['feature_key'] . '"';

    $hasProduct = $e['whmcs_product_id'] !== null;
    $hasAddon = $e['whmcs_addon_id'] !== null;
    if ($hasProduct === $hasAddon) $err[] = 'Map to exactly one of a WHMCS product id or a WHMCS product-addon id';
    if ($e['type'] === 'premium' && $hasAddon) $err[] = 'A Premium entry must map to a WHMCS product, not a product-addon';

    if ($e['parent_feature_key'] !== null) {
        if ($e['type'] !== 'addon') $err[] = 'Only an add-on may require a parent feature';
        if ($e['parent_feature_key'] === $e['feature_key']) $err[] = 'An entry cannot be its own parent';
        $parent = $others[$e['parent_feature_key']] ?? null;
        if ($parent === null) $err[] = 'Parent feature "' . $e['parent_feature_key'] . '" does not exist';
        elseif ($parent['type'] !== 'premium') $err[] = 'Parent feature "' . $e['parent_feature_key'] . '" must be a Premium entry';
    }

    if ($e['duration_days'] !== null && $e['duration_days'] <= 0) $err[] = 'Duration must be a positive number of days, or blank for permanent';
    if ($e['store_visible'] && $e['display_name'] === null) $err[] = 'A Store-visible entry needs a display name';
    // The sandbox price is a human TNAI amount; it must convert EXACTLY to integer base units (6
    // decimals) so the Store can charge it. A value like "5.0" is valid (-> 5000000 base units); one
    // with too many decimals, a sign, or non-numeric text is rejected here, before anything is saved —
    // never silently dropped, which would hide the product from the Store.
    if ($e['sandbox_price_tnai'] !== null) {
        $base = tnai_to_base_units((string)$e['sandbox_price_tnai']);
        if ($base === null) $err[] = 'Sandbox TNAI price must be a non-negative amount with at most ' . TNAI_DECIMALS . ' decimals (e.g. 5 or 5.5)';
        elseif ($base === '0') $err[] = 'Sandbox TNAI price must be greater than zero, or blank for no sandbox price';
    }
    return $err;
}

/** All catalog rows, ordered for display. */
function all(\PDO $db): array
{
    return $db->query('SELECT * FROM mod_terminow_store_catalog ORDER BY sort_order ASC, type DESC, feature_key ASC')->fetchAll(\PDO::FETCH_ASSOC);
}

function get(\PDO $db, int $id): ?array
{
    return one($db, 'SELECT * FROM mod_terminow_store_catalog WHERE id = ?', [$id]);
}

function by_feature(\PDO $db, string $featureKey): ?array
{
    return one($db, 'SELECT * FROM mod_terminow_store_catalog WHERE feature_key = ?', [$featureKey]);
}

/** Catalog rows indexed by the WHMCS product id (only product-mapped entries). */
function by_product_id(\PDO $db): array
{
    $map = [];
    foreach (all($db) as $row) if ($row['whmcs_product_id'] !== null) $map[(int)$row['whmcs_product_id']] = $row;
    return $map;
}

/**
 * Preview how existing rows' sandbox_price_tnai map onto the canonical base-unit format, for a safe,
 * reviewable migration. Read-only. Conservative and never floating point:
 *   - a pure-integer value is assumed to be base units already ('ok', unchanged);
 *   - a value with a decimal point (the legacy human amount that the old code stored verbatim, which
 *     the Store then could not charge) is converted exactly ('convert', with from/to);
 *   - anything that will not convert is flagged ('invalid') for manual re-entry.
 * Returns a list of ['id','feature_key','from','to','status'].
 */
function preview_sandbox_migration(\PDO $db): array
{
    $out = [];
    foreach (all($db) as $row) {
        $v = $row['sandbox_price_tnai'];
        if ($v === null || $v === '') continue;
        if (preg_match('/^[0-9]+$/', (string)$v)) { $status = 'ok'; $to = (string)$v; }
        else { $to = tnai_to_base_units((string)$v); $status = $to === null ? 'invalid' : 'convert'; }
        $out[] = ['id' => (int)$row['id'], 'feature_key' => $row['feature_key'], 'from' => (string)$v, 'to' => $to, 'status' => $status];
    }
    return $out;
}

/** Apply the 'convert' rows from preview_sandbox_migration (decimals -> base units). Returns #changed. */
function apply_sandbox_migration(\PDO $db, int $now): int
{
    $n = 0;
    foreach (preview_sandbox_migration($db) as $r) {
        if ($r['status'] !== 'convert') continue;
        $db->prepare('UPDATE mod_terminow_store_catalog SET sandbox_price_tnai = ?, updated_at = ? WHERE id = ?')->execute([$r['to'], $now, $r['id']]);
        $n++;
    }
    return $n;
}

/** All rows except the one with this id, keyed by feature_key, for validation. */
function others(\PDO $db, ?int $exceptId): array
{
    $map = [];
    foreach (all($db) as $row) {
        if ($exceptId !== null && (int)$row['id'] === $exceptId) continue;
        $map[$row['feature_key']] = $row;
    }
    return $map;
}

/**
 * Create or update a catalog entry from raw admin input. Throws Invalid with the list of errors on
 * a rejected mapping. Returns the stored row.
 */
function upsert(\PDO $db, array $input, int $now, ?int $id = null): array
{
    $e = normalize($input);
    $errors = validate($e, others($db, $id));
    if ($errors !== []) throw new Invalid($errors);

    // Store the canonical integer base-unit string (validate() has proven it converts exactly).
    if ($e['sandbox_price_tnai'] !== null) $e['sandbox_price_tnai'] = tnai_to_base_units((string)$e['sandbox_price_tnai']);

    if ($id === null) {
        $cols = array_keys($e);
        $db->prepare('INSERT INTO mod_terminow_store_catalog (' . implode(', ', $cols) . ', created_at, updated_at) VALUES ('
            . implode(', ', array_fill(0, count($cols), '?')) . ', ?, ?)')
            ->execute(array_merge(array_values($e), [$now, $now]));
        $id = (int)$db->lastInsertId();
    } else {
        $sets = implode(', ', array_map(fn($c) => "$c = ?", array_keys($e)));
        $db->prepare("UPDATE mod_terminow_store_catalog SET $sets, updated_at = ? WHERE id = ?")
            ->execute(array_merge(array_values($e), [$now, $id]));
    }
    return get($db, $id);
}

/** Delete an entry. Refuses if another entry still requires it as a parent feature. */
function remove(\PDO $db, int $id): void
{
    $row = get($db, $id);
    if ($row === null) return;
    $children = one($db, 'SELECT feature_key FROM mod_terminow_store_catalog WHERE parent_feature_key = ?', [$row['feature_key']]);
    if ($children !== null) throw new Invalid(['Add-on "' . $children['feature_key'] . '" still requires this feature as its parent; change it first']);
    $db->prepare('DELETE FROM mod_terminow_store_catalog WHERE id = ?')->execute([$id]);
}

/**
 * The catalog the Store consumes. Native WHMCS price and billing cycle come from $priceLookup
 * (so edits here never duplicate or override WHMCS pricing); the Store sees the current mapping,
 * the localized display text and the sandbox TNAI price. An entry whose WHMCS pricing cannot be
 * read is returned with available=false and a reason, so the Store can show it as unavailable.
 *
 * @param callable $priceLookup fn(array $row): ?array  => ['amount','currency','billingCycle'] or null
 */
function catalog_for_store(\PDO $db, callable $priceLookup, ?string $locale = null): array
{
    $items = [];
    $version = 0;
    foreach (all($db) as $row) {
        $version = max($version, (int)$row['updated_at']);
        if (!(int)$row['store_visible']) continue;
        $price = $priceLookup($row);
        $items[] = [
            'featureKey' => $row['feature_key'],
            'type' => $row['type'],
            'parentFeatureKey' => $row['parent_feature_key'],
            'durationDays' => $row['duration_days'] !== null ? (int)$row['duration_days'] : null,
            'whmcsProductId' => $row['whmcs_product_id'] !== null ? (int)$row['whmcs_product_id'] : null,
            'whmcsAddonId' => $row['whmcs_addon_id'] !== null ? (int)$row['whmcs_addon_id'] : null,
            'displayName' => localized($row['display_name'], $row['display_name_i18n'], $locale),
            'description' => localized($row['description'], $row['description_i18n'], $locale),
            'price' => is_array($price) ? $price : null,
            'sandboxPrice' => $row['sandbox_price_tnai'] !== null ? ['amount' => $row['sandbox_price_tnai'], 'currency' => 'TNAI'] : null,
            'available' => is_array($price),
            'unavailableReason' => is_array($price) ? null : 'WHMCS pricing is unavailable for this item',
        ];
    }
    return ['catalogVersion' => $version, 'generatedAt' => time(), 'items' => $items];
}

/** The localized string for a locale, falling back to the default when the locale is missing. */
function localized(?string $default, ?string $i18nJson, ?string $locale): ?string
{
    if ($locale !== null && $i18nJson !== null) {
        $map = json_decode($i18nJson, true);
        if (is_array($map)) {
            if (isset($map[$locale]) && is_string($map[$locale])) return $map[$locale];
            $base = explode('-', $locale)[0];
            if (isset($map[$base]) && is_string($map[$base])) return $map[$base];
        }
    }
    return $default;
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $st = $db->prepare($sql);
    $st->execute($args);
    $row = $st->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}
