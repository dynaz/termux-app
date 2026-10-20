# TermiNow: Google Play subscriptions with Firebase and WHMCS

TermiNow sells Premium as the Google Play subscription `terminow_premium` (base plans
`monthly` and `yearly`). This folder is the backend that verifies purchases and decides
Premium:

```
App (Play Billing) ── purchase token + Firebase ID token ──▶ verifyPlayPurchase (Cloud Function)
                                                                │  purchases.subscriptionsv2.get
                                                                ▼  (runtime service account)
                                Firestore: playPurchases (backend only), entitlements/{uid} (owner reads)
                                                                │
Google Play RTDN ─▶ Pub/Sub ─▶ onPlayNotification ──────────────┤
reconcilePlayPurchases (every 30 min) ──────────────────────────┤
                                                                ▼
                                whmcsOutbox/{uid} ─▶ syncWhmcs ─▶ cp.accmove.com/terminow/play_sync.php
                                                                   (signed, asynchronous, retried)
```

Premium is recorded in Firestore before the purchase is acknowledged, and neither waits for
WHMCS. The app reads `entitlements/{uid}`; WHMCS outages never take Premium away.

## Why purchases failed before (versionCode 155 to 158)

The app sent purchases to `whmcs/terminow/play.php`, which needs a Google service account key in
`/home/accmovecom/.terminow/play-key.json`. That key was never created: the directory is empty
on the server, so `terminow_google()` returned null and `play.php` answered every purchase with
HTTP 503 "Google Play purchases are not set up yet". `mod_terminow_play` has 0 rows: no purchase
was ever recorded or acknowledged by the server. Google Play cancels and refunds a purchase
that is not acknowledged within three days; purchases of license testers are never charged.
Which purchases were affected is only visible in Play Console (Order management). This backend
replaces `play.php`; it uses the identity of the functions instead of a key file.

## Configuration

| What | Where | Value |
|---|---|---|
| Firebase project | `.firebaserc` | `linex-bf945` |
| Android package | `PLAY_PACKAGE_NAME` in `functions/.env.linex-bf945` | `com.terminow` |
| Product and base plan mapping | `PLAY_PRODUCT_MAP` | `{"terminow_premium":{"monthly":{"tier":"premium","whmcsProductId":N},"yearly":{...}}}` |
| WHMCS product ids | `whmcsProductId` in `PLAY_PRODUCT_MAP` | **missing**: create the product first (see WHMCS) |
| Functions region | `FUNCTIONS_REGION`, and `functionsRegion` in `app/terminow-billing.properties` | **missing**: your choice, for example `asia-southeast1` |
| RTDN topic | `PLAY_RTDN_TOPIC` | **missing**: for example `play-rtdn` |
| Free members in WHMCS | `enrollWhmcsMembers`, every 60 minutes | every account with a verified email becomes a WHMCS client, without a service; license testers too |
| WHMCS endpoint | `WHMCS_SYNC_URL` | `https://cp.accmove.com/terminow/play_sync.php` |
| WHMCS secret | Secret Manager secret `WHMCS_SYNC_SECRET` | **missing**: 64 random characters, the same in the WHMCS addon |
| Runtime service account | the default compute service account of the project, or one you set | **missing**: its email is needed for Play Console |

Copy `functions/.env.example` to `functions/.env.linex-bf945` and fill it in. It is ignored by
git. No key file is used anywhere: the functions call Google Play as their runtime service
account.

Every function checks the configuration when it starts and logs `CONFIG_*` problems. Purchases
are refused with `BACKEND_NOT_CONFIGURED` until `PLAY_PACKAGE_NAME` and `PLAY_PRODUCT_MAP` are
valid.

## Setup checklist

Do these in order. None of them is done yet. Each step changes a production system or billing,
so do them yourself or allow them explicitly.

### Google Cloud and Firebase (project `linex-bf945`)

1. Upgrade the project to the Blaze plan (Cloud Functions need it).
2. Create the Firestore database (Native mode) if there is none.
3. Enable the APIs: Cloud Functions, Cloud Build, Artifact Registry, Eventarc, Pub/Sub, Cloud
   Scheduler, Secret Manager, and the **Google Play Android Developer API**.
4. Create the Pub/Sub topic: `gcloud pubsub topics create play-rtdn --project linex-bf945`
5. Let Google Play publish to it:
   `gcloud pubsub topics add-iam-policy-binding play-rtdn --project linex-bf945 --member=serviceAccount:google-play-developer-notifications@system.gserviceaccount.com --role=roles/pubsub.publisher`
6. Create the secret: `firebase functions:secrets:set WHMCS_SYNC_SECRET` (64 random characters,
   for example from `openssl rand -hex 32`).
7. Deploy the rules and indexes: `firebase deploy --only firestore`.
8. Deploy the functions: `firebase deploy --only functions:terminow`.
9. Note the runtime service account of the functions (Cloud Console → Cloud Run → a function →
   Security). By default it is `PROJECT_NUMBER-compute@developer.gserviceaccount.com`.

### Play Console (app `com.terminow`)

1. Users and permissions → Invite new users → the runtime service account email. App
   permissions for TermiNow only: **View financial data** and **Manage orders and
   subscriptions**. Nothing else.
2. Monetization setup → Real-time developer notifications → topic
   `projects/linex-bf945/topics/play-rtdn`, notifications for subscriptions and voided
   purchases. Press **Send test notification**: the function logs `RTDN test`.
3. Settings → License testing: add the Google accounts that test. Their purchases are test
   purchases: renewals every 5 minutes for a monthly plan, which is normal, and no charge.
   Leave the production base plans as they are.

### WHMCS (cp.accmove.com, 8.9)

1. Create a product group and a product "TermiNow Premium (Google Play)" with **Payment Type:
   Free**. The sync refuses any other payment type (`WHMCS_PRODUCT_NOT_FREE`), so WHMCS never
   creates an invoice, reminder or second charge for these services. Do not add it to the order
   form. Put its id into `whmcsProductId` of both plans.
2. Upload `whmcs/modules/addons/terminow_play/` to `modules/addons/terminow_play/`, and
   `play_sync_lib.php`, `play_sync.php`, `link.php` and the changed `premium.php`, `vault.php`,
   `whmcs_glue.php`, `lib.php` to `terminow/`. Remove `terminow/play.php` and
   `terminow/play_lib.php` from the server.
3. System Settings → Addon Modules → TermiNow Google Play → Activate. Set the sync secret (the
   same value as `WHMCS_SYNC_SECRET`) and an active payment gateway for the orders, and give
   the admin roles access.
4. Addons → TermiNow Google Play shows every account, its client and service, plan, state,
   expiry, test or live, last verification and sync, and the last error, with Retry sync.

### App

1. Set `functionsRegion` in `app/terminow-billing.properties`.
2. Build and upload a new versionCode, then test with a license tester account.

## End-to-end test with a license tester

1. Sign in to TermiNow with a verified account, Get Premium, buy monthly with the tester.
2. The app shows "verifying", then "Premium active (test purchase)".
3. Firestore: `playPurchases/<hash>` has `test: true`, `ackState: acknowledged`;
   `entitlements/<uid>` has `premium: true`.
4. WHMCS addon: the account is listed as TEST with status `test_only`; no client, service or
   invoice is created for test purchases.
5. Cancel in Google Play: Premium stays until the expiry, then ends (RTDN or reconciliation).
6. Reinstall the app, sign in, Restore purchases: Premium comes back.

Nothing here has been run against Google Play yet. Only the tests below have run.

## Error codes the app shows

| Code | Meaning | Transient |
|---|---|---|
| `BACKEND_NOT_CONFIGURED` | The app build has no functions region, or the functions have no product map | no |
| `BACKEND_NOT_DEPLOYED` | The functions URL answers 404 | yes |
| `SIGN_IN_REQUIRED` | No valid Firebase ID token | no |
| `INVALID_TOKEN` | Not a purchase token | no |
| `PURCHASE_NOT_FOUND` | Google Play does not know the token for `com.terminow` | no |
| `PRODUCT_NOT_ALLOWED` | The product or base plan is not in `PLAY_PRODUCT_MAP` | no |
| `PURCHASE_OF_ANOTHER_ACCOUNT` | The purchase was made for another TermiNow account | no |
| `PLAY_PERMISSION` | The runtime service account is not invited in Play Console | yes |
| `PLAY_AUTH`, `PLAY_UNAVAILABLE` | Google could not be reached | yes |
| `NETWORK` | The phone is offline | yes |

## Tests

```
cd firebase/functions
npm install
npm run test:unit          # pure logic
npm test                   # everything, with the Firestore emulator (needs Java 11+)
php ../../whmcs/tests/test_play_sync.php
```
