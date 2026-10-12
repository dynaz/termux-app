<?php
// php whmcs/tests/test_vault.php — tests of the TermiNow vault server, with SQLite instead of WHMCS.

require __DIR__ . '/../terminow/vault_lib.php';

use TermiNow\Vault\Failure;
use function TermiNow\Vault\handle;
use function TermiNow\Vault\migrate;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}

$db = new PDO('sqlite::memory:');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
migrate($db);
migrate($db); // twice is fine

$premium = ['owner@example.com' => true];
$isPremium = fn(string $email) => $premium[$email] ?? false;
$now = 1800000000;

function as_user(string $email): array
{
    return ['email' => $email, 'sub' => 'uid-' . $email];
}

function call(string $email, array $request): array
{
    global $db, $isPremium, $now;
    return handle($db, as_user($email), $request, $isPremium, $now);
}

function fails(string $email, array $request, int $status, string $name): void
{
    try {
        call($email, $request);
        check(false, $name);
    } catch (Failure $e) {
        check($e->status === $status, "$name ($e->status {$e->getMessage()})");
    }
}

$account = ['publicKey' => base64_encode('pub-owner'), 'encryptedPrivateKey' => base64_encode('enc-priv'), 'salt' => base64_encode('salt'), 'iterations' => 310000];
$me = call('Owner@Example.com', ['action' => 'me']);
check($me['account'] === null && $me['vaults'] === [] && $me['premium'] === true, 'new user has no account, email is lower case');

fails('owner@example.com', ['action' => 'vault.create', 'id' => str_repeat('a', 24), 'kind' => 'personal', 'wrappedKey' => 'AA==', 'data' => 'AA=='], 409, 'no vault before the account');
call('owner@example.com', ['action' => 'account.create'] + $account);
fails('owner@example.com', ['action' => 'account.create'] + $account, 409, 'account keys are not replaced by create');
fails('owner@example.com', ['action' => 'account.create', 'iterations' => 10] + $account, 400, 'too few iterations');

$personal = str_repeat('1', 24);
call('owner@example.com', ['action' => 'vault.create', 'id' => $personal, 'kind' => 'personal', 'wrappedKey' => 'd3JhcA==', 'data' => 'ZGF0YTE=']);
fails('owner@example.com', ['action' => 'vault.create', 'id' => str_repeat('2', 24), 'kind' => 'personal', 'wrappedKey' => 'd3JhcA==', 'data' => 'ZGF0YTE='], 409, 'one personal vault');
fails('owner@example.com', ['action' => 'vault.create', 'id' => 'not-hex', 'kind' => 'team', 'name' => 'x', 'wrappedKey' => 'AA==', 'data' => 'AA=='], 400, 'id format');
fails('owner@example.com', ['action' => 'vault.create', 'id' => str_repeat('3', 24), 'kind' => 'team', 'name' => 'x', 'wrappedKey' => 'AA==', 'data' => 'not base64!'], 400, 'data must be base64');

$get = call('owner@example.com', ['action' => 'vault.get', 'id' => $personal]);
check($get['data'] === 'ZGF0YTE=' && $get['version'] === 1 && $get['wrappedKey'] === 'd3JhcA==', 'get the personal vault');
check(call('owner@example.com', ['action' => 'vault.put', 'id' => $personal, 'data' => 'ZGF0YTI=', 'baseVersion' => 1])['version'] === 2, 'put on the current version');
try {
    call('owner@example.com', ['action' => 'vault.put', 'id' => $personal, 'data' => 'ZGF0YTM=', 'baseVersion' => 1]);
    check(false, 'put on an old version is refused');
} catch (Failure $e) {
    check($e->status === 409 && $e->extra['version'] === 2, 'put on an old version is refused with the current version');
}

// Someone else can not read or tell that it exists
fails('stranger@example.com', ['action' => 'vault.get', 'id' => $personal], 404, 'stranger can not read a personal vault');
fails('stranger@example.com', ['action' => 'vault.get', 'id' => str_repeat('9', 24)], 404, 'same answer for a vault that does not exist');

// Teams
$team = str_repeat('b', 24);
fails('member@example.com', ['action' => 'vault.create', 'id' => str_repeat('c', 24), 'kind' => 'team', 'name' => 'T', 'wrappedKey' => 'AA==', 'data' => 'AA=='], 409, 'no account, no team');
call('member@example.com', ['action' => 'account.create', 'publicKey' => base64_encode('pub-member')] + $account);
fails('member@example.com', ['action' => 'vault.create', 'id' => str_repeat('c', 24), 'kind' => 'team', 'name' => 'T', 'wrappedKey' => 'AA==', 'data' => 'AA=='], 402, 'team needs Premium');
fails('member@example.com', ['action' => 'vault.create', 'id' => str_repeat('d', 24), 'kind' => 'personal', 'wrappedKey' => 'AA==', 'data' => 'AA=='], 402, 'personal sync needs Premium');
call('owner@example.com', ['action' => 'vault.create', 'id' => $team, 'kind' => 'team', 'name' => 'Ops team', 'wrappedKey' => 'b3duZXI=', 'data' => 'dGVhbTE=']);
fails('member@example.com', ['action' => 'vault.get', 'id' => $team], 404, 'not a member before the invite');
fails('member@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'x@example.com'], 404, 'a stranger can not invite');

call('owner@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'Member@Example.com']);
call('owner@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'later@example.com']);
fails('owner@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'member@example.com'], 409, 'no double invite');
fails('owner@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'not an email'], 400, 'invite needs an email');

$members = call('owner@example.com', ['action' => 'team.members', 'id' => $team])['members'];
$byEmail = array_column($members, null, 'email');
check($byEmail['owner@example.com']['status'] === 'active', 'owner is active');
check($byEmail['owner@example.com']['publicKey'] === base64_encode('pub-owner'), 'active members show their public key for re-keying');
check($byEmail['member@example.com']['status'] === 'waiting' && $byEmail['member@example.com']['publicKey'] === base64_encode('pub-member'), 'member with keys waits for the owner, with its public key');
check($byEmail['later@example.com']['status'] === 'invited' && $byEmail['later@example.com']['publicKey'] === null, 'invitee without keys is invited');

$memberView = call('member@example.com', ['action' => 'me'])['vaults'];
check(count($memberView) === 1 && $memberView[0]['wrappedKey'] === null && $memberView[0]['name'] === 'Ops team', 'member sees the team without a key yet');
fails('member@example.com', ['action' => 'vault.put', 'id' => $team, 'data' => 'AA==', 'baseVersion' => 1], 403, 'no writing before access is granted');
fails('member@example.com', ['action' => 'team.grant', 'id' => $team, 'email' => 'member@example.com', 'wrappedKey' => 'AA==', 'publicKey' => base64_encode('pub-member')], 403, 'member can not grant itself');
fails('owner@example.com', ['action' => 'team.grant', 'id' => $team, 'email' => 'member@example.com', 'wrappedKey' => 'AA==', 'publicKey' => base64_encode('other')], 409, 'grant for a public key that is not the member\'s');
call('owner@example.com', ['action' => 'team.grant', 'id' => $team, 'email' => 'member@example.com', 'wrappedKey' => 'bWVtYmVy', 'publicKey' => base64_encode('pub-member')]);
check(call('member@example.com', ['action' => 'vault.get', 'id' => $team])['wrappedKey'] === 'bWVtYmVy', 'member gets its wrapped key');
check(call('member@example.com', ['action' => 'vault.put', 'id' => $team, 'data' => 'dGVhbTI=', 'baseVersion' => 1])['version'] === 2, 'member can save the team vault');
fails('member@example.com', ['action' => 'team.remove', 'id' => $team, 'email' => 'owner@example.com'], 403, 'member can not remove the owner');
fails('member@example.com', ['action' => 'vault.delete', 'id' => $team], 403, 'member can not delete the team');

// Removing a member, then a new key for the others
call('owner@example.com', ['action' => 'team.remove', 'id' => $team, 'email' => 'member@example.com']);
fails('member@example.com', ['action' => 'vault.get', 'id' => $team], 404, 'removed member has no access');
fails('owner@example.com', ['action' => 'team.rotate', 'id' => $team, 'data' => 'AA==', 'baseVersion' => 1, 'wrappedKeys' => ['owner@example.com' => 'AA==']], 409, 'rotate on an old version');
check(call('owner@example.com', ['action' => 'team.rotate', 'id' => $team, 'data' => 'cm90YXRlZA==', 'baseVersion' => 2,
    'wrappedKeys' => ['owner@example.com' => 'bmV3']])['version'] === 3, 'rotate with a new key');
check(call('owner@example.com', ['action' => 'vault.get', 'id' => $team])['wrappedKey'] === 'bmV3', 'owner has the new key');
fails('owner@example.com', ['action' => 'team.remove', 'id' => $team, 'email' => 'owner@example.com'], 400, 'owner can not leave its own team');

// Leaving by oneself
call('owner@example.com', ['action' => 'team.invite', 'id' => $team, 'email' => 'member@example.com']);
call('member@example.com', ['action' => 'team.remove', 'id' => $team, 'email' => 'member@example.com']);
check(call('member@example.com', ['action' => 'me'])['vaults'] === [], 'member can leave');

// A forgotten vault password
call('owner@example.com', ['action' => 'account.reset', 'publicKey' => base64_encode('pub-new')] + $account);
$after = call('owner@example.com', ['action' => 'me']);
check($after['account']['publicKey'] === base64_encode('pub-new'), 'reset replaces the keys');
$kinds = array_column($after['vaults'], 'wrappedKey', 'kind');
check(!isset($kinds['personal']) && array_key_exists('team', $kinds) && $kinds['team'] === null, 'reset deletes the personal vault and drops old team keys');

// Password change keeps the public key
call('owner@example.com', ['action' => 'account.password', 'encryptedPrivateKey' => base64_encode('enc2'), 'salt' => base64_encode('s2'), 'iterations' => 400000]);
$changed = call('owner@example.com', ['action' => 'me'])['account'];
check($changed['publicKey'] === base64_encode('pub-new') && $changed['iterations'] === 400000, 'password change keeps the public key');

call('owner@example.com', ['action' => 'vault.delete', 'id' => $team]);
check(call('owner@example.com', ['action' => 'me'])['vaults'] === [], 'owner deletes the team');
fails('owner@example.com', ['action' => 'nothing'], 400, 'unknown action');

echo $failures === 0 ? "\nall passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
