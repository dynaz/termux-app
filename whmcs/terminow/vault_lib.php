<?php
/**
 * TermiNow vaults: the logic of vault.php, without WHMCS, so that it can be tested on its own.
 *
 * The server only ever sees ciphertext. The app encrypts each vault (hosts, groups, snippets,
 * port forwards) with a random vault key, and wraps that key with the RSA public key of each
 * member. A member's private key is stored here encrypted with a key derived from their vault
 * password, which never leaves the phone. Members are identified by the verified email of their
 * Firebase ID token.
 */

namespace TermiNow\Vault;

const MAX_DATA = 4 * 1024 * 1024;
const MAX_KEY = 8192;
const MAX_TEAMS_PER_OWNER = 20;
const MAX_MEMBERS = 50;
const MIN_ITERATIONS = 100000;
const MAX_ITERATIONS = 5000000;

class Failure extends \Exception
{
    public int $status;
    public array $extra;

    public function __construct(int $status, string $message, array $extra = [])
    {
        parent::__construct($message);
        $this->status = $status;
        $this->extra = $extra;
    }
}

/** Create the tables if they do not exist. The SQL works with MySQL and SQLite. */
function migrate(\PDO $db): void
{
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_accounts (
        email VARCHAR(191) NOT NULL PRIMARY KEY,
        uid VARCHAR(128) NOT NULL,
        public_key TEXT NOT NULL,
        private_key TEXT NOT NULL,
        salt VARCHAR(64) NOT NULL,
        iterations INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_vaults (
        id VARCHAR(32) NOT NULL PRIMARY KEY,
        kind VARCHAR(16) NOT NULL,
        name VARCHAR(100) NOT NULL,
        owner_email VARCHAR(191) NOT NULL,
        data MEDIUMTEXT NOT NULL,
        version INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)');
    $db->exec('CREATE TABLE IF NOT EXISTS mod_terminow_members (
        vault_id VARCHAR(32) NOT NULL,
        email VARCHAR(191) NOT NULL,
        role VARCHAR(16) NOT NULL,
        wrapped_key TEXT NULL,
        added_at INTEGER NOT NULL,
        PRIMARY KEY (vault_id, email))');
}

function normalize_email(string $email): string
{
    return strtolower(trim($email));
}

/** Base64 of the size limits, for keys, ciphertext and salts. */
function base64_field(array $request, string $name, int $max, bool $required = true): ?string
{
    $value = $request[$name] ?? null;
    if ($value === null && !$required) return null;
    if (!is_string($value) || $value === '' || strlen($value) > $max || !preg_match('/^[A-Za-z0-9+\/]+={0,2}$/', $value))
        throw new Failure(400, "Invalid $name");
    return $value;
}

function vault_id(array $request): string
{
    $id = $request['id'] ?? null;
    if (!is_string($id) || !preg_match('/^[0-9a-f]{24}$/', $id)) throw new Failure(400, 'Invalid id');
    return $id;
}

function name_field(array $request): string
{
    $name = trim((string)($request['name'] ?? ''));
    if (!preg_match('/^[^\x00-\x1f]{1,60}$/u', $name)) throw new Failure(400, 'Invalid name');
    return $name;
}

function email_field(array $request): string
{
    $email = normalize_email((string)($request['email'] ?? ''));
    if (strlen($email) > 191 || !filter_var($email, FILTER_VALIDATE_EMAIL)) throw new Failure(400, 'Invalid email');
    return $email;
}

function one(\PDO $db, string $sql, array $args): ?array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    $row = $statement->fetch(\PDO::FETCH_ASSOC);
    return $row === false ? null : $row;
}

function all(\PDO $db, string $sql, array $args): array
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    return $statement->fetchAll(\PDO::FETCH_ASSOC);
}

function run(\PDO $db, string $sql, array $args): int
{
    $statement = $db->prepare($sql);
    $statement->execute($args);
    return $statement->rowCount();
}

function account(\PDO $db, string $email): ?array
{
    return one($db, 'SELECT * FROM mod_terminow_accounts WHERE email = ?', [$email]);
}

/** The membership of the user in a vault, or a 404 that does not tell whether the vault exists. */
function membership(\PDO $db, string $id, string $email): array
{
    $row = one($db, 'SELECT v.id, v.kind, v.name, v.owner_email, v.version, m.role, m.wrapped_key
        FROM mod_terminow_vaults v JOIN mod_terminow_members m ON m.vault_id = v.id WHERE v.id = ? AND m.email = ?', [$id, $email]);
    if ($row === null) throw new Failure(404, 'No such vault');
    return $row;
}

function require_owner(array $membership): void
{
    if ($membership['role'] !== 'owner') throw new Failure(403, 'Only the owner can do this');
    if ($membership['kind'] !== 'team') throw new Failure(400, 'Not a team vault');
}

/**
 * Handle one request of a signed-in user.
 *
 * @param array $claims The verified claims of the Firebase ID token.
 * @param callable $isPremium fn(string $email): bool, whether the user has TermiNow Premium.
 * @return array The body of the answer.
 * @throws Failure
 */
function handle(\PDO $db, array $claims, array $request, callable $isPremium, int $now): array
{
    $email = normalize_email((string)$claims['email']);
    $uid = (string)$claims['sub'];
    $action = (string)($request['action'] ?? '');

    switch ($action) {
        case 'me': {
            $account = account($db, $email);
            $vaults = all($db, 'SELECT v.id, v.kind, v.name, v.owner_email, v.version, m.role, m.wrapped_key
                FROM mod_terminow_members m JOIN mod_terminow_vaults v ON v.id = m.vault_id WHERE m.email = ? ORDER BY v.kind DESC, v.name', [$email]);
            return [
                'email' => $email,
                'premium' => (bool)$isPremium($email),
                'account' => $account === null ? null : [
                    'publicKey' => $account['public_key'],
                    'encryptedPrivateKey' => $account['private_key'],
                    'salt' => $account['salt'],
                    'iterations' => (int)$account['iterations'],
                ],
                'vaults' => array_map(fn($v) => [
                    'id' => $v['id'], 'kind' => $v['kind'], 'name' => $v['name'], 'owner' => $v['owner_email'],
                    'role' => $v['role'], 'version' => (int)$v['version'], 'wrappedKey' => $v['wrapped_key'],
                ], $vaults),
            ];
        }

        case 'account.create':
        case 'account.reset': {
            $publicKey = base64_field($request, 'publicKey', MAX_KEY);
            $privateKey = base64_field($request, 'encryptedPrivateKey', MAX_KEY);
            $salt = base64_field($request, 'salt', 64);
            $iterations = (int)($request['iterations'] ?? 0);
            if ($iterations < MIN_ITERATIONS || $iterations > MAX_ITERATIONS) throw new Failure(400, 'Invalid iterations');
            $existing = account($db, $email);
            if ($action === 'account.create') {
                if ($existing !== null) throw new Failure(409, 'The account has keys already');
                run($db, 'INSERT INTO mod_terminow_accounts (email, uid, public_key, private_key, salt, iterations, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
                    [$email, $uid, $publicKey, $privateKey, $salt, $iterations, $now]);
                return ['ok' => true];
            }
            // A forgotten vault password: new keys, and every vault key wrapped for the old key is
            // useless now. The personal vault is deleted, teams need the owner to grant access again.
            if ($existing === null) throw new Failure(404, 'No account');
            $db->beginTransaction();
            run($db, 'UPDATE mod_terminow_accounts SET uid = ?, public_key = ?, private_key = ?, salt = ?, iterations = ?, updated_at = ? WHERE email = ?',
                [$uid, $publicKey, $privateKey, $salt, $iterations, $now, $email]);
            foreach (all($db, "SELECT id FROM mod_terminow_vaults WHERE owner_email = ? AND kind = 'personal'", [$email]) as $v) {
                run($db, 'DELETE FROM mod_terminow_members WHERE vault_id = ?', [$v['id']]);
                run($db, 'DELETE FROM mod_terminow_vaults WHERE id = ?', [$v['id']]);
            }
            run($db, 'UPDATE mod_terminow_members SET wrapped_key = NULL WHERE email = ?', [$email]);
            $db->commit();
            return ['ok' => true];
        }

        case 'account.password': {
            // The same keys, the private key encrypted with a new vault password
            $privateKey = base64_field($request, 'encryptedPrivateKey', MAX_KEY);
            $salt = base64_field($request, 'salt', 64);
            $iterations = (int)($request['iterations'] ?? 0);
            if ($iterations < MIN_ITERATIONS || $iterations > MAX_ITERATIONS) throw new Failure(400, 'Invalid iterations');
            if (run($db, 'UPDATE mod_terminow_accounts SET private_key = ?, salt = ?, iterations = ?, updated_at = ? WHERE email = ?',
                    [$privateKey, $salt, $iterations, $now, $email]) === 0 && account($db, $email) === null)
                throw new Failure(404, 'No account');
            return ['ok' => true];
        }

        case 'vault.create': {
            if (account($db, $email) === null) throw new Failure(409, 'Set a vault password first');
            $id = vault_id($request);
            $kind = (string)($request['kind'] ?? '');
            if ($kind !== 'personal' && $kind !== 'team') throw new Failure(400, 'Invalid kind');
            $name = $kind === 'personal' ? 'Personal' : name_field($request);
            $wrapped = base64_field($request, 'wrappedKey', MAX_KEY);
            $data = base64_field($request, 'data', MAX_DATA);
            // Syncing is part of TermiNow Premium, Personal and Team alike
            if (!$isPremium($email)) throw new Failure(402, 'Syncing vaults needs TermiNow Premium');
            if ($kind === 'personal') {
                if (one($db, "SELECT id FROM mod_terminow_vaults WHERE owner_email = ? AND kind = 'personal'", [$email]) !== null)
                    throw new Failure(409, 'The personal vault exists already');
            } else {
                $count = one($db, "SELECT COUNT(*) AS n FROM mod_terminow_vaults WHERE owner_email = ? AND kind = 'team'", [$email]);
                if ((int)$count['n'] >= MAX_TEAMS_PER_OWNER) throw new Failure(400, 'Too many team vaults');
            }
            if (one($db, 'SELECT id FROM mod_terminow_vaults WHERE id = ?', [$id]) !== null) throw new Failure(409, 'The id is taken');
            $db->beginTransaction();
            run($db, 'INSERT INTO mod_terminow_vaults (id, kind, name, owner_email, data, version, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?)',
                [$id, $kind, $name, $email, $data, $now]);
            run($db, "INSERT INTO mod_terminow_members (vault_id, email, role, wrapped_key, added_at) VALUES (?, ?, 'owner', ?, ?)",
                [$id, $email, $wrapped, $now]);
            $db->commit();
            return ['id' => $id, 'version' => 1];
        }

        case 'vault.get': {
            $m = membership($db, vault_id($request), $email);
            $row = one($db, 'SELECT data, version FROM mod_terminow_vaults WHERE id = ?', [$m['id']]);
            return ['id' => $m['id'], 'version' => (int)$row['version'], 'data' => $row['data'], 'wrappedKey' => $m['wrapped_key'], 'role' => $m['role']];
        }

        case 'vault.put': {
            $m = membership($db, vault_id($request), $email);
            if ($m['wrapped_key'] === null) throw new Failure(403, 'No access to the vault key yet');
            // A team stays usable for its members, the own vault needs Premium to keep syncing
            if ($m['kind'] === 'personal' && !$isPremium($email)) throw new Failure(402, 'Syncing vaults needs TermiNow Premium');
            $data = base64_field($request, 'data', MAX_DATA);
            $base = (int)($request['baseVersion'] ?? -1);
            // Only if nobody else saved since the version that the change was made on
            if (run($db, 'UPDATE mod_terminow_vaults SET data = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?',
                    [$data, $now, $m['id'], $base]) === 0) {
                $current = one($db, 'SELECT version FROM mod_terminow_vaults WHERE id = ?', [$m['id']]);
                throw new Failure(409, 'The vault changed', ['version' => (int)$current['version']]);
            }
            return ['version' => $base + 1];
        }

        case 'vault.delete': {
            $m = membership($db, vault_id($request), $email);
            if ($m['role'] !== 'owner') throw new Failure(403, 'Only the owner can do this');
            $db->beginTransaction();
            run($db, 'DELETE FROM mod_terminow_members WHERE vault_id = ?', [$m['id']]);
            run($db, 'DELETE FROM mod_terminow_vaults WHERE id = ?', [$m['id']]);
            $db->commit();
            return ['ok' => true];
        }

        case 'team.members': {
            $m = membership($db, vault_id($request), $email);
            if ($m['kind'] !== 'team') throw new Failure(400, 'Not a team vault');
            $rows = all($db, 'SELECT m.email, m.role, m.wrapped_key, a.public_key FROM mod_terminow_members m
                LEFT JOIN mod_terminow_accounts a ON a.email = m.email WHERE m.vault_id = ? ORDER BY m.role DESC, m.email', [$m['id']]);
            return ['members' => array_map(fn($r) => [
                'email' => $r['email'],
                'role' => $r['role'],
                // invited: no TermiNow vault password yet; waiting: ready for the owner to grant
                'status' => $r['wrapped_key'] !== null ? 'active' : ($r['public_key'] === null ? 'invited' : 'waiting'),
                // For giving access, and for wrapping a new key after a member was removed
                'publicKey' => $r['public_key'],
            ], $rows)];
        }

        case 'team.invite': {
            $m = membership($db, vault_id($request), $email);
            require_owner($m);
            if (!$isPremium($email)) throw new Failure(402, 'Team vaults need TermiNow Premium');
            $invitee = email_field($request);
            $count = one($db, 'SELECT COUNT(*) AS n FROM mod_terminow_members WHERE vault_id = ?', [$m['id']]);
            if ((int)$count['n'] >= MAX_MEMBERS) throw new Failure(400, 'Too many members');
            if (one($db, 'SELECT email FROM mod_terminow_members WHERE vault_id = ? AND email = ?', [$m['id'], $invitee]) !== null)
                throw new Failure(409, 'Already a member');
            run($db, "INSERT INTO mod_terminow_members (vault_id, email, role, wrapped_key, added_at) VALUES (?, ?, 'member', NULL, ?)",
                [$m['id'], $invitee, $now]);
            return ['ok' => true];
        }

        case 'team.grant': {
            $m = membership($db, vault_id($request), $email);
            require_owner($m);
            $member = email_field($request);
            $wrapped = base64_field($request, 'wrappedKey', MAX_KEY);
            // Wrapped for the public key that the owner was shown, not one that changed since
            $publicKey = base64_field($request, 'publicKey', MAX_KEY);
            $account = account($db, $member);
            if ($account === null || !hash_equals($account['public_key'], $publicKey)) throw new Failure(409, 'The member\'s key changed, refresh');
            if (run($db, 'UPDATE mod_terminow_members SET wrapped_key = ? WHERE vault_id = ? AND email = ?', [$wrapped, $m['id'], $member]) === 0)
                throw new Failure(404, 'Not a member');
            return ['ok' => true];
        }

        case 'team.remove': {
            $m = membership($db, vault_id($request), $email);
            if ($m['kind'] !== 'team') throw new Failure(400, 'Not a team vault');
            $member = email_field($request);
            if ($member === $email) {
                if ($m['role'] === 'owner') throw new Failure(400, 'The owner can delete the vault instead');
            } else {
                require_owner($m);
            }
            run($db, "DELETE FROM mod_terminow_members WHERE vault_id = ? AND email = ? AND role <> 'owner'", [$m['id'], $member]);
            return ['ok' => true];
        }

        case 'team.rotate': {
            // After a member was removed: a new vault key, the data encrypted with it, and the key
            // wrapped for each remaining member. Members that are not listed lose access.
            $m = membership($db, vault_id($request), $email);
            require_owner($m);
            $data = base64_field($request, 'data', MAX_DATA);
            $base = (int)($request['baseVersion'] ?? -1);
            $keys = $request['wrappedKeys'] ?? null;
            if (!is_array($keys) || !isset($keys[$email])) throw new Failure(400, 'Invalid wrappedKeys');
            $db->beginTransaction();
            if (run($db, 'UPDATE mod_terminow_vaults SET data = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?',
                    [$data, $now, $m['id'], $base]) === 0) {
                $db->rollBack();
                $current = one($db, 'SELECT version FROM mod_terminow_vaults WHERE id = ?', [$m['id']]);
                throw new Failure(409, 'The vault changed', ['version' => (int)$current['version']]);
            }
            run($db, 'UPDATE mod_terminow_members SET wrapped_key = NULL WHERE vault_id = ?', [$m['id']]);
            foreach ($keys as $memberEmail => $wrapped) {
                $memberEmail = normalize_email((string)$memberEmail);
                $wrapped = base64_field(['k' => $wrapped], 'k', MAX_KEY);
                run($db, 'UPDATE mod_terminow_members SET wrapped_key = ? WHERE vault_id = ? AND email = ?', [$wrapped, $m['id'], $memberEmail]);
            }
            $db->commit();
            return ['version' => $base + 1];
        }

        case 'team.rename': {
            $m = membership($db, vault_id($request), $email);
            require_owner($m);
            run($db, 'UPDATE mod_terminow_vaults SET name = ? WHERE id = ?', [name_field($request), $m['id']]);
            return ['ok' => true];
        }
    }
    throw new Failure(400, 'Unknown action');
}
