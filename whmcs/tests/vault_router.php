<?php
// The vault server for the tests of the app: `php -S 127.0.0.1:PORT whmcs/tests/vault_router.php`
// with TERMINOW_TEST_DB set to an SQLite file. The bearer token is "test-<email>" instead of a
// Firebase ID token, and every email that does not start with "free" has Premium.

require __DIR__ . '/../terminow/vault_lib.php';

header('Content-Type: application/json');
$header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
if (!preg_match('/^Bearer test-(.+)$/', $header, $m)) {
    http_response_code(401);
    echo json_encode(['error' => 'Sign in first']);
    return;
}
$email = $m[1];
$db = new PDO('sqlite:' . getenv('TERMINOW_TEST_DB'));
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
\TermiNow\Vault\migrate($db);
$request = json_decode((string)file_get_contents('php://input'), true);
try {
    $body = \TermiNow\Vault\handle($db, ['email' => $email, 'sub' => 'uid-' . $email], is_array($request) ? $request : [],
        fn(string $e) => strpos($e, 'free') !== 0, time());
    echo json_encode($body);
} catch (\TermiNow\Vault\Failure $e) {
    if ($db->inTransaction()) $db->rollBack();
    http_response_code($e->status);
    echo json_encode(['error' => $e->getMessage()] + $e->extra);
}
