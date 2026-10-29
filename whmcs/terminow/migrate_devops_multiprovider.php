<?php
/**
 * One-time, idempotent, data-preserving migration of mod_terminow_devops_conn / _state from the
 * GitHub-only shape (PRIMARY KEY (uid)) to the multi-provider shape (PRIMARY KEY (uid, provider),
 * plus expires_at on conn and provider on state). Safe to run more than once: each change is applied
 * only if the schema does not already have it.
 *
 * It is run on the server with the WHMCS bootstrap, the same connection the app uses, so no DB
 * credentials are handled here. Before any structural change it makes a full copy of the connection
 * table (mod_terminow_devops_conn_bak_<ts>) as the rollback point — rows (including encrypted tokens)
 * are copied, never altered or dropped. Nothing is deleted.
 *
 * Run (as the server user, via the wrapper):  /root/migrate_devops_multiprovider.sh
 * Rollback (only if needed): restore from the printed _bak_<ts> table (command printed at the end).
 */
error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE);
require dirname(getcwd()) . '/init.php';
require getcwd() . '/whmcs_glue.php';
$db = terminow_db();
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

$dbn = $db->query('SELECT DATABASE()')->fetchColumn();

$colExists = function (string $table, string $col) use ($db, $dbn): bool {
    $s = $db->prepare('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?');
    $s->execute([$dbn, $table, $col]);
    return (int)$s->fetchColumn() > 0;
};
$pkCols = function (string $table) use ($db, $dbn): array {
    $s = $db->prepare("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION");
    $s->execute([$dbn, $table]);
    return array_map('strval', $s->fetchAll(PDO::FETCH_COLUMN));
};

$dryRun = in_array('dry', array_slice($argv, 1), true) || in_array('--dry-run', array_slice($argv, 1), true);

$needExpires = !$colExists('mod_terminow_devops_conn', 'expires_at');
$needStateProv = !$colExists('mod_terminow_devops_state', 'provider');
$pk = $pkCols('mod_terminow_devops_conn');
$needPk = ($pk === ['uid']);

echo ($dryRun ? "DRY RUN (no changes)\n" : "") . "current conn PK = (" . implode(',', $pk) . ")\n";
echo "need: expires_at=" . ($needExpires ? 'yes' : 'no')
   . "  state.provider=" . ($needStateProv ? 'yes' : 'no')
   . "  pk->(uid,provider)=" . ($needPk ? 'yes' : 'no') . "\n";

if (!$needExpires && !$needStateProv && !$needPk) {
    if ($pk !== ['uid', 'provider'])
        echo "WARNING: conn PK is (" . implode(',', $pk) . "), not (uid,provider) — leaving unchanged.\n";
    echo "Already migrated. Nothing to do.\n";
    exit(0);
}

// Pre-flight guards for the PK change (always computed; a real run aborts WITHOUT any change if unsafe).
$bad = (int)$db->query("SELECT COUNT(*) FROM mod_terminow_devops_conn WHERE provider IS NULL OR provider=''")->fetchColumn();
$dups = (int)$db->query("SELECT COUNT(*) FROM (SELECT uid,provider,COUNT(*) c FROM mod_terminow_devops_conn GROUP BY uid,provider HAVING c>1) x")->fetchColumn();

if ($dryRun) {
    echo "plan: add expires_at=" . ($needExpires ? 'YES' : 'no')
       . "  add state.provider=" . ($needStateProv ? 'YES' : 'no')
       . "  change PK->(uid,provider)=" . ($needPk ? 'YES' : 'no') . "\n";
    echo "guard: null/empty provider = $bad (must be 0)\n";
    echo "guard: duplicate (uid,provider) = $dups (must be 0)\n";
    echo (($bad === 0 && $dups === 0) ? "GUARDS PASS — a real run would be safe\n" : "GUARDS FAIL — a real run would ABORT\n");
    echo "No changes made (dry run).\n";
    exit(0);
}

if ($needPk) {
    if ($bad > 0) { fwrite(STDERR, "ABORT: $bad rows have NULL/empty provider; fill them before the PK change.\n"); exit(2); }
    if ($dups > 0) { fwrite(STDERR, "ABORT: $dups duplicate (uid,provider) groups; resolve before the PK change.\n"); exit(2); }
}

// Backup copy (rollback point) — full structural + data copy, nothing destructive.
$ts = date('Ymd_His');
$bak = "mod_terminow_devops_conn_bak_$ts";
$db->exec("CREATE TABLE `$bak` LIKE mod_terminow_devops_conn");
$db->exec("INSERT INTO `$bak` SELECT * FROM mod_terminow_devops_conn");
$bakRows = (int)$db->query("SELECT COUNT(*) FROM `$bak`")->fetchColumn();
$liveRows = (int)$db->query("SELECT COUNT(*) FROM mod_terminow_devops_conn")->fetchColumn();
echo "backup table `$bak` created with $bakRows rows (live has $liveRows)\n";
if ($bakRows !== $liveRows) { fwrite(STDERR, "ABORT: backup row count mismatch; no changes applied.\n"); exit(3); }

// Apply, each guarded (idempotent).
if ($needExpires) { $db->exec("ALTER TABLE mod_terminow_devops_conn ADD COLUMN expires_at INT NOT NULL DEFAULT 0"); echo "added conn.expires_at\n"; }
if ($needStateProv) { $db->exec("ALTER TABLE mod_terminow_devops_state ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'github'"); echo "added state.provider\n"; }
if ($needPk) { $db->exec("ALTER TABLE mod_terminow_devops_conn DROP PRIMARY KEY, ADD PRIMARY KEY (uid, provider)"); echo "changed conn PK -> (uid,provider)\n"; }

// Verify.
$pk2 = $pkCols('mod_terminow_devops_conn');
$ok = ($pk2 === ['uid', 'provider'])
    && $colExists('mod_terminow_devops_conn', 'expires_at')
    && $colExists('mod_terminow_devops_state', 'provider')
    && ((int)$db->query('SELECT COUNT(*) FROM mod_terminow_devops_conn')->fetchColumn() === $liveRows);
echo "verify: conn PK=(" . implode(',', $pk2) . ") expires_at=" . ($colExists('mod_terminow_devops_conn', 'expires_at') ? 'yes' : 'no')
   . " state.provider=" . ($colExists('mod_terminow_devops_state', 'provider') ? 'yes' : 'no')
   . " conn rows=" . $db->query('SELECT COUNT(*) FROM mod_terminow_devops_conn')->fetchColumn() . "\n";
echo $ok ? "MIGRATION OK\n" : "MIGRATION INCOMPLETE — check above\n";
echo "Rollback (only if needed; preserves data):\n";
echo "  DROP TABLE mod_terminow_devops_conn; RENAME TABLE `$bak` TO mod_terminow_devops_conn;\n";
echo "  (then, if added: ALTER TABLE mod_terminow_devops_state DROP COLUMN provider;)\n";
exit($ok ? 0 : 1);
