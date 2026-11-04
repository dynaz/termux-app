# Store ↔ WHMCS → Firebase — staging harness & runbook

A reproducible, **local** end-to-end exercise of the integration's code paths and contracts. It drives
the **real** library code (`catalog_lib.php`, `entitlement_lib.php`, the Firebase writer
`src/entitlement.js`, the real `hooks.php` sender logic) and the **real** HMAC signing, ordering,
idempotency, reconcile and retry — across process boundaries over HTTP.

## ⚠️ What this proves, and what it does NOT

| Layer | In this harness | Real? |
|---|---|---|
| Catalog model / validation / feed composition (`catalog_lib.php`) | real code | ✅ real |
| Outbox: sign / enqueue / order / idempotency / reconcile / retry (`entitlement_lib.php`) | real code | ✅ real |
| Delivery envelope + 409→HELD (`hooks.php` sender) | faithfully replicated in `outbox.php` | ✅ logic |
| Firebase writer (`src/entitlement.js`) | real handler over the Firestore **emulator** via Admin SDK | ⚠️ **EMULATED** |
| WHMCS runtime: `localAPI`/`GetClientsProducts`, `tblhosting`, hooks firing from a real admin action | **synthetic** `$api` fed from `fixtures/` | ❌ **MOCKED** |
| App merged-rights decision (`TerminowEntitlement`) | its JVM unit tests, against the writer's doc schema | ✅ logic |
| Store catalog import (`terminowai`) | real server, local sandbox mode (optional, `STORE_DIR`) | ⚠️ local |

**Green here means the code paths and the PHP↔Node↔Android contracts agree. It is NOT proof of live
integration.** No licensed WHMCS runs; no real Firebase project is written; nothing is deployed.

## Prerequisites
- **Node ≥ 22.13** on `PATH` (the Store and the writer use `node:sqlite` / `firebase-admin`).
- **php** with `pdo_sqlite` + `curl`.
- **Java 17+** (the Firestore emulator is a JVM).
- The **firebase CLI** resolvable at `firebase/functions/node_modules/.bin/firebase` or on `PATH`,
  with the Firestore emulator jar available (first run downloads it).
- **PR #12 present**: `firebase/functions/src/entitlement.js` (the writer). On a branch without it,
  set `TERMINOW_FUNCTIONS_DIR` to a checkout that has it.
- ⚠️ **Resource note:** the emulator JVM is heavy and can take 80s+ to bind in a constrained
  container; running it alongside the writer/feed/Store can exhaust a small host. Run on a machine
  with adequate RAM. (Observed to freeze a memory-constrained Android/proot host.)

## Run
```bash
cd whmcs/tests/staging
./run.sh                               # core path + lifecycle battery, gate OFF then ON, with assertions
STORE_DIR=/path/to/terminowai ./run.sh # also verify the catalog -> Store import
```
`run.sh` generates ephemeral HMAC secrets into `.work/run/` (0600, never printed), starts the
emulator + writer + feed, runs the battery with assertions, and tears everything down on exit. It
exits non-zero if any assertion fails. All run artifacts live under `.work/` (git-ignored).

### Scenarios asserted
Activation, idempotent re-sync, renewal, suspension, expiry (Active-past-due), cancellation;
duplicate `idempotencyKey`; out-of-order `seq` (stale no-op); unlinked uid → 409 held + no write, then
retry after linking; reconcile preview (no write) then apply; writer auth (bad signature → 401);
**gate OFF** records the source without granting; **gate ON** grants and drops for a sole source while
preserving Google Play; optional Store import (`imported:2, unavailable:1`).

## Files
- `run.sh` — orchestrator / runbook (assertions + teardown).
- `writer-staging.js` — the real writer wired to the emulator (mirrors `index.js`'s `onRequest`).
- `outbox.php` — drives the real outbox with a synthetic WHMCS `$api` from `fixtures/`.
- `catalog-feed.php` — the real catalog feed logic with synthetic native prices (no WHMCS init).
- `fb.js` — seed `whmcsLinks`/other sources, read `entitlements/{uid}`, reset (emulator helper).
- `send.js` — craft one raw signed delivery (for duplicate / out-of-order at the writer).
- `migrate-preview.php` — preview/apply the `sandbox_price_tnai` → base-units migration.
- `fixtures/` — sanitized synthetic WHMCS service states.

## Checks still BLOCKED by missing real staging (and exact prerequisites)
1. **Real WHMCS runtime** (admin lifecycle action → `hooks.php` → outbox, `localAPI`/`GetClientsProducts`,
   `tblhosting` resolution). *Prerequisite:* a non-production WHMCS install on a staging host
   (PHP+MySQL) with a **free WHMCS development license** (requested from the WHMCS portal for the owned
   license — no monetary cost). Deploy the `whmcs/` files there and perform real service lifecycle
   actions on synthetic clients.
2. **Deployed Firebase writer on a real test project** (deploy mechanics + Secret Manager wiring; the
   emulator exercises the same Admin SDK logic). *Prerequisite:* a throwaway Firebase project (free
   Spark tier), `firebase deploy --only functions:whmcsEntitlement`, `WHMCS_ENTITLEMENT_SECRET` in
   Secret Manager. **Not** `linex-bf945`.
3. **Store ↔ app ↔ writer with real Firebase Auth UIDs** end to end (this harness seeds `whmcsLinks`
   directly). *Prerequisite:* the test Firebase project above plus a test Google identity.
4. **Addon-level lifecycle**: delivery is product-keyed (`by_product_id`); product-addons feed the
   Store catalog but do **not** emit lifecycle entitlements. If addon rights must follow WHMCS
   lifecycle, that is a product decision + a change to `compute_client_entitlements`.
