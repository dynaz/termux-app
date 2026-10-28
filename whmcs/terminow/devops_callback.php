<?php
/**
 * GitHub OAuth callback, at https://cp.accmove.com/terminow/devops/callback/github
 * (route this path to this file). GitHub's redirect carries NO Firebase header, so the request is
 * authenticated by the single-use, short-lived `state` created in the `connect` action and bound to the
 * uid. Entitlement is re-checked by email before the connection is saved. The code/token are never
 * logged, and the token never leaves the server.
 */

require __DIR__ . '/lib.php';
require __DIR__ . '/devops_lib.php';
require __DIR__ . '/play_sync_lib.php';

header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

require dirname(__DIR__) . '/init.php';
require __DIR__ . '/whmcs_glue.php';

try {
    $db = terminow_db();
    \TermiNow\DevOps\migrate($db);
    $premiumByEmail = function (string $email) use (&$db): bool {
        try { return (bool)(terminow_whmcs_premium($db, $email)['premium'] ?? false); }
        catch (\Throwable $e) { return false; }   // fail closed
    };
    \TermiNow\DevOps\handle_callback($db, $_GET, $premiumByEmail, time());
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><title>TermiNow DevOps</title>'
       . '<p>GitHub is connected. You can return to the TermiNow app.</p>';
} catch (\TermiNow\DevOps\Failure $e) {
    // A simple, safe error page — no code/state/token echoed.
    http_response_code($e->status);
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><title>TermiNow DevOps</title>'
       . '<p>Could not finish connecting GitHub (' . htmlspecialchars($e->getMessage(), ENT_QUOTES) . '). '
       . 'Please try again from the app.</p>';
} catch (\Throwable $e) {
    http_response_code(503);
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><title>TermiNow DevOps</title><p>Try again later.</p>';
}
