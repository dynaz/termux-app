<?php
/**
 * Migration preview/apply for the sandbox_price_tnai column (human decimal -> integer base units).
 * Read-only by default; pass `apply` to convert the legacy decimal rows. Operates on the real catalog
 * table via catalog_lib, so it is safe to run against a copy of the production module DB.
 *
 * Usage:  CATALOG_DB=/path/to/whmcs.sqlite php migrate-preview.php [apply]
 * For a live WHMCS (MySQL) deployment, call TermiNow\Catalog\preview_sandbox_migration()/
 * apply_sandbox_migration() with the WHMCS PDO instead; the admin "Catalog" tab also surfaces this.
 */
require dirname(__DIR__, 2) . '/terminow/catalog_lib.php';

$db = new PDO('sqlite:' . getenv('CATALOG_DB'));
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
\TermiNow\Catalog\migrate($db);

$rows = \TermiNow\Catalog\preview_sandbox_migration($db);
if ($rows === []) { echo "No sandbox prices set; nothing to migrate.\n"; exit(0); }
printf("%-24s %-14s %-16s %s\n", 'feature_key', 'stored', 'base_units', 'status');
foreach ($rows as $r) printf("%-24s %-14s %-16s %s\n", $r['feature_key'], $r['from'], $r['to'] ?? '-', $r['status']);

if (($argv[1] ?? '') === 'apply') {
    $n = \TermiNow\Catalog\apply_sandbox_migration($db, time());
    echo "\nApplied: converted $n legacy decimal price(s) to base units. Integer and invalid rows were left untouched.\n";
} else {
    echo "\nPreview only. Re-run with `apply` to convert the 'convert' rows. 'invalid' rows must be re-entered in the admin.\n";
}
