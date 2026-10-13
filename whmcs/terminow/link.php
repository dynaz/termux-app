<?php
/**
 * Link an existing WHMCS client to a TermiNow account, at https://cp.accmove.com/terminow/link.php
 *
 * The client logs in to the client area and gets a one-time code here, valid for 10 minutes,
 * which they enter in TermiNow. Firebase redeems it for the signed-in TermiNow account. So both
 * sides are proven by a login, never by an email address that matches.
 */

require __DIR__ . '/play_sync_lib.php';
require dirname(__DIR__) . '/init.php';

header('Cache-Control: no-store');
header('X-Frame-Options: DENY');

$clientId = 0;
if (class_exists('\WHMCS\Authentication\CurrentUser')) {
    $client = (new \WHMCS\Authentication\CurrentUser())->client();
    if ($client) $clientId = (int)$client->id;
} elseif (!empty($_SESSION['uid'])) {
    $clientId = (int)$_SESSION['uid'];
}

$code = null;
$error = null;
if ($clientId > 0 && ($_SERVER['REQUEST_METHOD'] ?? '') === 'POST') {
    if (!function_exists('check_token') || !check_token('WHMCS.default', false)) {
        $error = 'The page expired, open it again.';
    } else {
        $db = \Illuminate\Database\Capsule\Manager::connection()->getPdo();
        $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        \TermiNow\PlaySync\migrate($db);
        $code = \TermiNow\PlaySync\create_link_code($db, $clientId, time());
    }
}
$h = fn($text) => htmlspecialchars((string)$text, ENT_QUOTES, 'UTF-8');
?><!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Link TermiNow</title>
<style>
body { font-family: system-ui, sans-serif; max-width: 460px; margin: 40px auto; padding: 0 16px; color: #222; }
.code { font: bold 34px/1.4 monospace; letter-spacing: 6px; padding: 14px; border: 1px solid #ccc; border-radius: 10px; text-align: center; }
button { font-size: 16px; padding: 10px 18px; }
.error { color: #b00020; }
</style>
</head>
<body>
<h1>Link TermiNow</h1>
<?php if ($clientId <= 0): ?>
  <p>Log in to the client area first, then open this page again.</p>
  <p><a href="../clientarea.php">Log in</a></p>
<?php elseif ($code !== null): ?>
  <p>Enter this code in TermiNow, in Settings → Account → Link existing account. It works once, for 10 minutes.</p>
  <p class="code"><?= $h($code) ?></p>
<?php else: ?>
  <?php if ($error): ?><p class="error"><?= $h($error) ?></p><?php endif; ?>
  <p>This gives you a one-time code that links this client account to your TermiNow account.</p>
  <form method="post">
    <input type="hidden" name="token" value="<?= $h(function_exists('generate_token') ? generate_token('plain') : '') ?>">
    <button type="submit">Get a code</button>
  </form>
<?php endif; ?>
</body>
</html>
