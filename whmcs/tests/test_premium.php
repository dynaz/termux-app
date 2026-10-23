<?php
// php whmcs/tests/test_premium.php — tests of the TermiNow Premium check, without WHMCS.

require __DIR__ . '/../terminow/lib.php';

use TermiNow\Premium\TokenException;
use function TermiNow\Premium\bearer_token;
use function TermiNow\Premium\decide;
use function TermiNow\Premium\verify_id_token;

$failures = 0;
function check(bool $ok, string $name): void
{
    global $failures;
    echo ($ok ? "ok   " : "FAIL ") . $name . "\n";
    if (!$ok) $failures++;
}

function b64(string $data): string
{
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

// A key and certificate like Google's, made here
$key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
$csr = openssl_csr_new(['commonName' => 'test'], $key);
openssl_x509_export(openssl_csr_sign($csr, null, $key, 1), $cert);
$certs = ['kid1' => $cert];
$other = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);

$now = 1800000000;
function token(array $claims, $key, array $header = ['alg' => 'RS256', 'kid' => 'kid1']): string
{
    $input = b64(json_encode($header)) . '.' . b64(json_encode($claims));
    openssl_sign($input, $signature, $key, OPENSSL_ALGO_SHA256);
    return $input . '.' . b64($signature);
}
$good = [
    'iss' => 'https://securetoken.google.com/linex-bf945', 'aud' => 'linex-bf945', 'sub' => 'uid123',
    'iat' => $now - 100, 'exp' => $now + 3000, 'auth_time' => $now - 200,
    'email' => 'member@example.com', 'email_verified' => true,
];

function rejects(string $token, array $certs, int $now, string $name): void
{
    try {
        verify_id_token($token, $certs, $now);
        check(false, $name);
    } catch (TokenException $e) {
        check(true, $name . ' (' . $e->getMessage() . ')');
    }
}

$claims = verify_id_token(token($good, $key), $certs, $now);
check($claims['email'] === 'member@example.com', 'valid token is accepted');

rejects(token($good, $other), $certs, $now, 'signature of another key');
rejects(token(['email_verified' => false] + $good, $key), $certs, $now, 'unverified email');
rejects(token(array_diff_key($good, ['email_verified' => 1]), $key), $certs, $now, 'missing email_verified');
rejects(token(['aud' => 'other-project'] + $good, $key), $certs, $now, 'other Firebase project');
rejects(token(['iss' => 'https://evil.example'] + $good, $key), $certs, $now, 'wrong issuer');
rejects(token(['exp' => $now - 3600] + $good, $key), $certs, $now, 'expired');
rejects(token(['iat' => $now + 3600] + $good, $key), $certs, $now, 'issued in the future');
rejects(token(['sub' => ''] + $good, $key), $certs, $now, 'empty subject');
rejects(token($good, $key, ['alg' => 'RS256', 'kid' => 'unknown']), $certs, $now, 'unknown key id');
rejects(b64(json_encode(['alg' => 'none', 'kid' => 'kid1'])) . '.' . b64(json_encode($good)) . '.', $certs, $now, 'alg none');
$hs = b64(json_encode(['alg' => 'HS256', 'kid' => 'kid1'])) . '.' . b64(json_encode($good));
rejects($hs . '.' . b64(hash_hmac('sha256', $hs, $cert, true)), $certs, $now, 'HS256 with the certificate as secret');
rejects('not.a.token', $certs, $now, 'garbage');

check(bearer_token(['HTTP_AUTHORIZATION' => 'Bearer aa.bb.cc']) === 'aa.bb.cc', 'bearer header');
check(bearer_token(['HTTP_AUTHORIZATION' => 'Basic abc']) === null, 'not bearer');
check(bearer_token([]) === null, 'no header');

$products = [
    ['pid' => 5, 'name' => 'Web Hosting', 'status' => 'Active', 'nextduedate' => '2027-01-01'],
    ['pid' => 9, 'name' => 'TermiNow Premium Monthly', 'status' => 'Suspended', 'nextduedate' => '2026-11-01'],
];
check(decide($products, [])['premium'] === false, 'suspended TermiNow and other products are not Premium');
$products[] = ['pid' => 10, 'name' => 'TermiNow Premium Yearly', 'status' => 'Active', 'nextduedate' => '2027-10-12'];
$decision = decide($products, []);
check($decision['premium'] === true && $decision['nextDueDate'] === '2027-10-12', 'active TermiNow service is Premium');
check(decide($products, [9])['premium'] === true, 'TermiNow products count with configured ids too');
$mobiclaude = [['pid' => 90, 'name' => 'MobiClaude Premium', 'status' => 'Active', 'nextduedate' => '2026-12-01'],
    ['pid' => 5, 'name' => 'Web Hosting', 'status' => 'Active', 'nextduedate' => '2027-01-01']];
check(decide($mobiclaude, [90])['premium'] === true && decide($mobiclaude, [90])['plan'] === 'MobiClaude Premium',
    'configured product id that is active');
check(decide($mobiclaude, [])['premium'] === false, 'MobiClaude Premium only counts when configured');
check(decide([['pid' => 90, 'name' => 'MobiClaude Premium', 'status' => 'Suspended']], [90])['premium'] === false,
    'suspended configured product');
check(decide([['pid' => 3, 'name' => 'Lifetime', 'groupname' => 'TermiNow', 'status' => 'Active', 'nextduedate' => '0000-00-00']], [])['nextDueDate'] === null,
    'one-time service has no due date');
check(decide([], [])['premium'] === false, 'no services');

echo $failures === 0 ? "\nAll passed\n" : "\n$failures failed\n";
exit($failures === 0 ? 0 : 1);
