# TermiNow Premium check for WHMCS

The app asks `https://cp.accmove.com/terminow/premium.php` whether the signed-in
TermiNow account has Premium. The endpoint:

- verifies the Firebase ID token of the account (project `linex-bf945`) with the
  public keys of Google, and only trusts a **verified** email;
- looks up the WHMCS client with that email through the internal API of WHMCS
  (`localAPI`), so there are no API credentials anywhere;
- answers only `{"premium": true|false, "plan", "nextDueDate"}`, and never logs the token.

## Install

1. Upload the folder `terminow/` (both `premium.php` and `lib.php`) into the
   WHMCS directory, next to `init.php`:
   `/home/accmovecom/domains/cp.accmove.com/public_html/terminow/`
2. Premium is an Active service of a product whose name or group contains
   "TermiNow", or of a product id in `TERMINOW_PREMIUM_PRODUCT_IDS` at the top of
   `premium.php` (90, MobiClaude Premium, which includes TermiNow Premium).
3. Check it: `curl -X POST https://cp.accmove.com/terminow/premium.php` must
   answer `{"error":"Sign in first"}` with HTTP 401.

Only a service with the status **Active** is Premium; Pending, Suspended,
Terminated and Cancelled are not.

The server needs PHP with the openssl and curl extensions (WHMCS needs them too)
and outgoing HTTPS to www.googleapis.com.

## Tests

    php whmcs/tests/test_premium.php
