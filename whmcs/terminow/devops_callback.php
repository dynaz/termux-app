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
    // "Open TermiNow" uses a deep link that carries NO token/code/secret; the app re-checks the
    // connection with the backend when it opens. A manual fallback is given if the link is blocked.
    echo '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
       . '<title>TermiNow DevOps</title></head><body style="font-family:system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1.25rem;text-align:center">'
       . '<h2>GitHub connected</h2>'
       . '<p>Your GitHub account is now connected to TermiNow DevOps.</p>'
       . '<p><a href="terminow://devops/connected" style="display:inline-block;background:#21C17A;color:#fff;text-decoration:none;padding:.75rem 1.5rem;border-radius:.5rem;font-weight:600">Open TermiNow</a></p>'
       . '<p style="color:#666;font-size:.9rem">If the app does not open automatically, switch back to TermiNow and open DevOps again.</p>'
       . '</body></html>';
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
