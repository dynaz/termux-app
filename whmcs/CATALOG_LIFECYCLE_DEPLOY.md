# TermiNow WHMCS catalog + entitlement lifecycle — deploy & rollback

Phase 2 of the Store↔WHMCS integration (after `STORE_SYNC_DEPLOY.md`). It makes the WHMCS addon the
central authority for TermiNow packages and add-ons, feeds that catalog to the Store, and pushes WHMCS
service lifecycle changes to Firebase so the app's rights follow WHMCS. **Billing is unchanged:** no
new invoice, service or payment is created by any of this; the Store's Devnet sandbox stays
tracking-only.

## What is in this change
New (additive):
- `terminow/catalog_lib.php` — catalog model + validation + the Store feed composition (no WHMCS needed).
- `terminow/catalog.php` — authenticated server-to-server catalog feed at `/terminow/catalog.php`.
- `terminow/entitlement_lib.php` — the WHMCS → Firebase outbox (compute, enqueue, deliver, reconcile).
- `modules/addons/terminow_play/hooks.php` — lifecycle + daily-cron hooks that drive the outbox.
- `tests/test_catalog.php`, `tests/test_entitlement.php` — run with `php` + `pdo_sqlite`.

Edited (additive; existing flows unchanged):
- `modules/addons/terminow_play/terminow_play.php` — three admin tabs (Overview / Catalog /
  Entitlements), three new settings (`catalogSecret`, `entitlementSyncUrl`, `entitlementSyncSecret`),
  and migrations for the new tables.
- `terminow/store_sync_lib.php` — four additive snapshot columns on `mod_terminow_store_sync`
  (`price_snapshot`, `price_currency`, `duration_days`, `catalog_version`), captured once at purchase.

New tables, all created automatically by the addon on save (no manual SQL, nothing altered destructively):
`mod_terminow_store_catalog`, `mod_terminow_entitlement_sync`.

## Settings to configure
- **Catalog feed secret** (`catalogSecret`, ≥32 chars): the Store signs its catalog request with it.
  Set the same value as `WHMCS_CATALOG_SECRET` on the Store, and point `WHMCS_CATALOG_URL` at
  `https://cp.accmove.com/terminow/catalog.php`.
- **Entitlement sync URL** + **secret** (`entitlementSyncUrl`, `entitlementSyncSecret`): the HTTPS
  Firebase endpoint that writes `entitlements/{uid}` and its ≥32-char HMAC secret. **Leave blank to
  start** — lifecycle events queue as `held` and are visible in the Entitlements tab until configured.

## Catalog authority (tab "Catalog")
Map each WHMCS product / product-addon to a Store feature: feature key, premium vs add-on, parent
requirement, visibility, display text (with i18n), duration, and a Devnet sandbox TNAI price. Native
WHMCS price and billing cycle are **read live** by the feed and never duplicated here. Invalid mappings
(both/neither id set, a premium pointing at an add-on, a missing/non-premium parent, a visible item
with no name) are rejected with a precise message.

## Firebase entitlement writer (receives the lifecycle deliveries)
The deliveries land at the Cloud Function **`whmcsEntitlement`** (HTTP) in `firebase/functions`
(`src/entitlement.js` + its export in `index.js`). Point the addon's **Entitlement sync URL** at that
function's URL and set both the addon secret and the function's Secret-Manager secret
**`WHMCS_ENTITLEMENT_SECRET`** to the same ≥32-char value. The function:
- verifies the HMAC and a 5-minute window; POST only;
- verifies the WHMCS client ↔ uid mapping against the authoritative `whmcsLinks/{uid}` doc — a
  mismatch/absent link returns **409 (held)**, so the WHMCS outbox retries and nothing is written;
- is idempotent on the delivery's `idempotencyKey` and ordered per `(uid, featureKey)` by `seq`;
- merges **only** `sources.whmcs` and recomputes top-level `premium`, preserving `sources.google_play`
  and any Store source;
- **gates real Premium OFF** unless `WHMCS_GRANTS_PREMIUM=1`: by default it records the whmcs source
  visibly but does not flip `premium` (premium.php polling stays the compatibility path). Turning the
  flag on is the reviewed go-live step.

Deploy the function with `firebase deploy --only functions:whmcsEntitlement` **to a staging project
first** (see "Staging verification" at the end). Do not enable `WHMCS_GRANTS_PREMIUM` yet.

## App consumption
`TerminowEntitlement.get()` now folds every Premium-granting source in the merged `entitlements/{uid}`
doc (Google Play, the WHMCS lifecycle source once enabled, and any Store source) into the Premium
decision, while **`premium.php` polling remains the compatibility fallback**. No app change is needed
to consume WHMCS lifecycle rights once `WHMCS_GRANTS_PREMIUM` is on; until then premium.php carries
WHMCS Premium as today.

## Lifecycle (tab "Entitlements")
`hooks.php` registers on create/edit/suspend/unsuspend/terminate/cancel/change-package. On each, it
re-reads the authoritative WHMCS state for the client, and for every linked uid enqueues a delivery
when the state changed. Delivery is signed, idempotent (payload hash per `uid+feature+source`), ordered
(monotonic seq; a later event never overtakes an undelivered earlier one), and retried. The daily cron
delivers anything pending and reconciles drift. Sources are kept separate (`source="whmcs"`): removing
WHMCS rights never drops Google Play or Store rights. `premium.php` polling is untouched (compat path).

## Deploy (run as the site user; nothing destructive)
1. Upload to `/home/accmovecom/domains/cp.accmove.com/public_html/`:
   - `terminow/catalog_lib.php`, `terminow/catalog.php`, `terminow/entitlement_lib.php`
   - `modules/addons/terminow_play/hooks.php`
   - the updated `modules/addons/terminow_play/terminow_play.php` and `terminow/store_sync_lib.php`
   (owner `accmovecom:accmovecom`, mode `0644`).
2. In WHMCS admin → Addon Modules → TermiNow: set the catalog secret (and, when ready, the entitlement
   sync URL + secret) and save. Saving creates `mod_terminow_store_catalog` and
   `mod_terminow_entitlement_sync` and adds the snapshot columns.
3. Build the catalog on the **Catalog** tab. Point the Store at the feed (`WHMCS_CATALOG_URL` /
   `WHMCS_CATALOG_SECRET`).

## Verify (read-only / non-billing)
- `curl -s -o /dev/null -w '%{http_code}' https://cp.accmove.com/terminow/catalog.php` → **405** (GET).
- A correctly-signed POST to `/terminow/catalog.php` returns `{catalogVersion, items:[...]}`; an item
  whose WHMCS price cannot be read comes back `available:false`, never dropped.
- Change a mapped service's status in WHMCS; the **Entitlements** tab shows a new event (delivered, or
  `held` until Firebase delivery is configured). No invoice/service is created.
- `premium.php` answers exactly as before.

## Rollback (non-destructive; all sync + catalog data is preserved)
1. Remove `modules/addons/terminow_play/hooks.php` — lifecycle delivery stops immediately.
2. Delete `terminow/catalog.php` (stops the feed) and, if desired, `terminow/catalog_lib.php` /
   `terminow/entitlement_lib.php`.
3. Restore the previous `terminow_play.php` and `store_sync_lib.php` if you want the tabs/columns gone.

**Do NOT drop `mod_terminow_store_catalog` or `mod_terminow_entitlement_sync`, and do NOT drop the
snapshot columns, as part of rollback.** They hold the mapping, the delivery history and purchase-time
order snapshots, and are kept by default so a re-enable resumes without data loss. Once the files are
removed nothing reads or writes them. Dropping them is a separate, explicit, operator-initiated step,
never part of a routine rollback. No WHMCS core table or file is modified by this change.

## Staging verification (before any production go-live)
Production WHMCS stays **read-only**: do not generate lifecycle events against it. To verify end to end:
1. Deploy the WHMCS files to a **staging** WHMCS (a copy of the module + a staging DB) and deploy
   `whmcsEntitlement` to a **test Firebase project** (not `linex-bf945`), with `WHMCS_GRANTS_PREMIUM`
   unset first, then `=1` to exercise the grant path.
2. In staging, **create/suspend/cancel a mapped service** (these are real test writes in staging) and
   confirm the Entitlements tab shows the event delivered, and `entitlements/{uid}.sources.whmcs`
   updates in the test project while `google_play`/Store sources are preserved.
3. Replay a delivery (duplicate) and send an out-of-order `seq`; confirm no-ops. Send an unlinked uid;
   confirm a held (409) retry with no write.
4. Only after staging passes, deploy to production and enable `WHMCS_GRANTS_PREMIUM=1` as the reviewed
   go-live.

The repo includes a cross-component contract test (`firebase/functions/test/unit/whmcs-contract.test.js`)
that feeds a **real PHP-signed delivery** through the actual writer, proving the PHP↔Node signing and
payload shape agree without touching any server.
