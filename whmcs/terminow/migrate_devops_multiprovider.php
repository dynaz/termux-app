<?php
/**
 * One-time, idempotent, data-preserving migration of mod_terminow_devops_conn / _state to the
 * multi-provider shape. The logic lives in TermiNow\DevOps\migrate_multiprovider() (devops_lib.php) so it
 * is unit-tested on a staging MySQL; this entry script only bootstraps the WHMCS DB and prints.
 *
 * Run via the wrapper:   /root/migrate_devops_multiprovider.sh  [--dry-run]
 * It makes a full backup copy table before any change and aborts without change if a guard fails.
 * Rollback (printed below on a real run): restore from the _bak_<ts> table.
 */
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE);
require dirname(getcwd()) . '/init.php';
require getcwd() . '/whmcs_glue.php';
require getcwd() . '/devops_lib.php';

$dry = in_array('dry', array_slice($argv, 1), true) || in_array('--dry-run', array_slice($argv, 1), true);
$db = terminow_db();
$r = \TermiNow\DevOps\migrate_multiprovider($db, $dry);

echo ($dry ? "DRY RUN (no changes)\n" : "") . 'conn PK = (' . implode(',', $r['pk']) . ")\n";
echo 'plan: expires_at=' . ($r['needExpires'] ? 'YES' : 'no')
   . '  state.provider=' . ($r['needStateProv'] ? 'YES' : 'no')
   . '  change PK=' . ($r['needPk'] ? 'YES' : 'no') . "\n";
echo "guard: null/empty provider = {$r['bad']} (must be 0)\n";
echo "guard: duplicate (uid,provider) = {$r['dups']} (must be 0)\n";
if ($r['backup']) echo "backup table: {$r['backup']}\n";
echo strtoupper($r['message']) . "\n";
if ($r['rollback']) echo "Rollback (only if needed; preserves data):\n  {$r['rollback']}\n";
exit($r['ok'] ? 0 : 1);
