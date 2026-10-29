<?php
// Staging test of the multi-provider DB migration against a REAL MySQL/MariaDB (not production, not
// SQLite — the migration uses MySQL-only DDL). It replicates the exact production schema, then checks:
// dry-run, apply, idempotency, the NULL/empty-provider guard, data preservation, and rollback.
//
//   TERMINOW_TEST_MYSQL_SOCKET=/path/mysqld.sock php whmcs/tests/test_devops_migration.php
// (or TERMINOW_TEST_MYSQL_DSN=... with TERMINOW_TEST_MYSQL_USER). It creates/uses a throwaway database.

require __DIR__ . '/../terminow/devops_lib.php';

use function TermiNow\DevOps\migrate_multiprovider;

$failures = 0;
function check(bool $ok, string $name): void { global $failures; echo ($ok ? "ok   " : "FAIL ") . $name . "\n"; if (!$ok) $failures++; }

$sock = getenv('TERMINOW_TEST_MYSQL_SOCKET');
$dsnBase = getenv('TERMINOW_TEST_MYSQL_DSN') ?: ($sock ? "mysql:unix_socket=$sock" : 'mysql:host=127.0.0.1');
$user = getenv('TERMINOW_TEST_MYSQL_USER') ?: 'root';
$pass = getenv('TERMINOW_TEST_MYSQL_PASS') ?: '';
$dbname = getenv('TERMINOW_TEST_MYSQL_DB') ?: 'terminow_stage';

try {
    $root = new PDO($dsnBase, $user, $pass, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
} catch (\Throwable $e) {
    fwrite(STDERR, "SKIP: cannot connect to staging MySQL ($dsnBase): " . $e->getMessage() . "\n");
    exit(0);
}
$root->exec("CREATE DATABASE IF NOT EXISTS `$dbname`");
$db = new PDO($dsnBase . ";dbname=$dbname", $user, $pass, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

// Reset to the EXACT production (GitHub-era) schema captured from SHOW CREATE TABLE on cp.accmove.com.
$db->exec("DROP TABLE IF EXISTS mod_terminow_devops_conn");
$db->exec("DROP TABLE IF EXISTS mod_terminow_devops_state");
foreach ($db->query("SHOW TABLES LIKE 'mod_terminow_devops_conn_bak_%'") as $r) $db->exec("DROP TABLE `{$r[0]}`");
$db->exec("CREATE TABLE mod_terminow_devops_conn (
    uid varchar(128) NOT NULL, email varchar(191) NOT NULL, provider varchar(16) NOT NULL,
    enc_token text NOT NULL, enc_refresh text, scopes varchar(255) NOT NULL, login varchar(100) NOT NULL,
    status varchar(16) NOT NULL, created_at int NOT NULL, updated_at int NOT NULL,
    PRIMARY KEY (uid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3");
$db->exec("CREATE TABLE mod_terminow_devops_state (
    state varchar(64) NOT NULL, uid varchar(128) NOT NULL, email varchar(191) NOT NULL,
    created_at int NOT NULL, expires_at int NOT NULL, used int NOT NULL DEFAULT 0,
    PRIMARY KEY (state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3");
$db->exec("INSERT INTO mod_terminow_devops_conn VALUES
    ('uidGH','u@e.com','github','ENC_TOKEN_OPAQUE',NULL,'read:user','octocat','connected',1000,1000)");

// 1) Dry run reports the plan + guards, changes nothing.
$r = migrate_multiprovider($db, true);
check($r['pk'] === ['uid'], 'dry: current PK is (uid)');
check($r['needExpires'] && $r['needStateProv'] && $r['needPk'], 'dry: all three changes needed');
check($r['bad'] === 0 && $r['dups'] === 0, 'dry: guards pass');
check(!$r['applied'] && $r['ok'], 'dry: nothing applied, safe');
$pkNow = $db->query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$dbname' AND TABLE_NAME='mod_terminow_devops_conn' AND CONSTRAINT_NAME='PRIMARY'")->fetchAll(PDO::FETCH_COLUMN);
check($pkNow === ['uid'], 'dry: PK unchanged on disk');

// 2) Real run applies, preserves data, makes a backup.
$r = migrate_multiprovider($db, false);
check($r['applied'] && $r['ok'], 'apply: migration ok');
check($r['pk'] === ['uid', 'provider'], 'apply: PK is now (uid,provider)');
check((int)$db->query("SELECT COUNT(*) FROM mod_terminow_devops_conn")->fetchColumn() === 1, 'apply: the github row is preserved');
check((string)$db->query("SELECT enc_token FROM mod_terminow_devops_conn")->fetchColumn() === 'ENC_TOKEN_OPAQUE', 'apply: token value untouched');
$cols = $db->query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$dbname' AND TABLE_NAME='mod_terminow_devops_conn'")->fetchAll(PDO::FETCH_COLUMN);
check(in_array('expires_at', $cols, true), 'apply: expires_at added');
$scols = $db->query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$dbname' AND TABLE_NAME='mod_terminow_devops_state'")->fetchAll(PDO::FETCH_COLUMN);
check(in_array('provider', $scols, true), 'apply: state.provider added');
$bak = $r['backup'];
check($bak && (int)$db->query("SELECT COUNT(*) FROM `$bak`")->fetchColumn() === 1, 'apply: backup copy has the row');

// 3) Idempotent: a second run detects nothing to do.
$r2 = migrate_multiprovider($db, false);
check(!$r2['needExpires'] && !$r2['needStateProv'] && !$r2['needPk'], 're-run: nothing needed');
check(!$r2['applied'] && $r2['ok'] && $r2['message'] === 'already migrated', 're-run: no-op, already migrated');

// 4) Guard: a NULL/empty provider on the (uid)-PK shape aborts the real run with no change.
$db->exec("DROP TABLE mod_terminow_devops_conn");
$db->exec("CREATE TABLE mod_terminow_devops_conn (
    uid varchar(128) NOT NULL, email varchar(191) NOT NULL, provider varchar(16) NOT NULL,
    enc_token text NOT NULL, enc_refresh text, scopes varchar(255) NOT NULL, login varchar(100) NOT NULL,
    status varchar(16) NOT NULL, created_at int NOT NULL, updated_at int NOT NULL,
    PRIMARY KEY (uid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3");
$db->exec("INSERT INTO mod_terminow_devops_conn VALUES ('uidX','u@e.com','','ENC',NULL,'s','l','connected',1,1)");
$rg = migrate_multiprovider($db, false);
check(!$rg['applied'] && !$rg['ok'] && strpos($rg['message'], 'NULL/empty provider') !== false, 'guard: empty provider aborts, no change');
$pkStill = $db->query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$dbname' AND TABLE_NAME='mod_terminow_devops_conn' AND CONSTRAINT_NAME='PRIMARY'")->fetchAll(PDO::FETCH_COLUMN);
check($pkStill === ['uid'], 'guard: PK left as (uid) after abort');

// 5) Rollback restores the (uid) PK and the data from the backup copy.
$db->exec("UPDATE mod_terminow_devops_conn SET provider='github' WHERE provider=''");
$r5 = migrate_multiprovider($db, false);
check($r5['ok'] && $r5['pk'] === ['uid', 'provider'], 'rollback-setup: migrated again ok');
foreach (explode(';', $r5['rollback']) as $stmt) { $stmt = trim($stmt); if ($stmt !== '') $db->exec($stmt); }
$pkBack = $db->query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='$dbname' AND TABLE_NAME='mod_terminow_devops_conn' AND CONSTRAINT_NAME='PRIMARY'")->fetchAll(PDO::FETCH_COLUMN);
check($pkBack === ['uid'], 'rollback: PK restored to (uid)');
check((int)$db->query("SELECT COUNT(*) FROM mod_terminow_devops_conn")->fetchColumn() === 1, 'rollback: data preserved');

// Clean up throwaway tables.
foreach ($db->query("SHOW TABLES LIKE 'mod_terminow_devops_conn_bak_%'") as $r) $db->exec("DROP TABLE `{$r[0]}`");

echo "\n" . ($failures === 0 ? "ALL PASS" : "$failures FAILED") . "\n";
exit($failures === 0 ? 0 : 1);
