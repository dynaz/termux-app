<?php
/**
 * Link an existing WHMCS client to a TermiNow account: the old address of
 * https://cp.accmove.com/index.php?m=terminow_play, the client area page of the TermiNow addon.
 *
 * The client logs in to the client area and gets a one-time code here, valid for 10 minutes,
 * which they enter in TermiNow. Firebase redeems it for the signed-in TermiNow account. So both
 * sides are proven by a login, never by an email address that matches.
 */

// The page is now index.php?m=terminow_play of the TermiNow addon, where WHMCS asks for the login
// and checks the form token; a page of its own failed with "Invalid CSRF Protection Token"
header('Cache-Control: no-store');
header('Location: ../index.php?m=terminow_play', true, 302);
