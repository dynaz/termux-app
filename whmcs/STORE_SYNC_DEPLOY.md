# TermiNow Store → WHMCS sync — deploy & rollback

Phase 1 of the Store↔WHMCS integration (see `STORE_INTEGRATION_DESIGN.md`). It mirrors a TermiNow
**Store (Devnet sandbox)** entitlement into WHMCS as a tracking record associated to the verified
WHMCS client. **It never bills and never creates or activates a WHMCS service.** Google Play sync and
the Premium decision are untouched.

## Files in this change
New (additive):
- `terminow/store_sync_lib.php` — logic (namespace `TermiNow\StoreSync`), testable without WHMCS.
- `terminow/store_sync.php` — HTTP endpoint at `https://cp.accmove.com/terminow/store_sync.php`.
- `tests/test_store_sync.php` — runs with plain `php` + `pdo_sqlite`.

Edited (additive only, no change to the Play-sync or Premium flows):
- `modules/addons/terminow_play/terminow_play.php` — adds the `storeSyncSecret` addon setting and a
  **TermiNow Store (Devnet sandbox)** section to the addon admin page that lists the mirror rows,
  flags held/failed ones, and offers a per-row **Retry sync** (re-resolves the client association
  after linking; still sandbox-only — no bill, no service).

The `mod_terminow_store_sync` table is created automatically by `migrate()` on first request and on
addon save; no manual SQL is required and no existing table is altered.

## Guarantees (enforced by code + tests)
- Only `GetClientsDetails` (read) may hit WHMCS. **No** `AddClient` / `AddOrder` / `AcceptOrder` /
  `UpdateClientProduct` / invoice call is ever issued. `test_store_sync.php` asserts zero mutations.
- Devnet `sandbox:false` is **forced to sandbox** unless `allowRealGrants` is true, and the endpoint
  hard-codes `allowRealGrants => false`. No real WHMCS activation is possible in this phase.
- Clients are associated only via the existing verified uid↔client link (the Play addon's one-time
  client-area code); never merged by email. Unlinked accounts are held (`needs_link`/`needs_client`).
- Idempotent/retryable per `(uid, feature_key)` version; held rows re-resolve after linking.

## Prerequisites
- The **TermiNow Google Play** addon is activated (provides `tbladdonmodules` + the shared link table).
- Set the **Store sync secret** (addon setting `storeSyncSecret`, ≥32 chars) to the same value the
  Firebase Store-sync caller signs with. Keep it different from the Play `syncSecret`.

## Deploy (run as the site user; nothing destructive)
1. Upload to `/home/accmovecom/domains/cp.accmove.com/public_html/`:
   - `terminow/store_sync_lib.php`
   - `terminow/store_sync.php`
   - the updated `modules/addons/terminow_play/terminow_play.php`
   (owner `accmovecom:accmovecom`, mode `0644`, matching the neighbouring files).
2. In WHMCS admin → System Settings → Addon Modules → TermiNow Google Play, set **Store sync secret**
   and save (this also creates `mod_terminow_store_sync`).
3. Point the Firebase Store-sync delivery at `https://cp.accmove.com/terminow/store_sync.php` with the
   same secret.

## Verify (read-only)
- `curl -s -o /dev/null -w '%{http_code}' -X GET  https://cp.accmove.com/terminow/store_sync.php` → **405**.
- `curl -s -X POST https://cp.accmove.com/terminow/store_sync.php` (no signature) → **401** `BAD_SIGNATURE`
  (or **503** `WHMCS_NOT_CONFIGURED` if the secret is not set yet).
- A correctly-signed `store_sync` for an **unlinked** uid → `{"status":"needs_client"|"needs_link",...}`
  and **no** new client/service/invoice in WHMCS.
- A correctly-signed `store_sync` for a **linked** uid → `{"status":"recorded","clientId":N,"serviceId":null,"sandbox":true}`.
- Confirm a signed-in user's `premium.php` answer is **unchanged** (the Store mirror does not affect it).
- In WHMCS admin → Addons → TermiNow Google Play, the new **TermiNow Store (Devnet sandbox)** section
  lists mirror rows; held/failed rows show in bold and a **Retry sync** button re-resolves them.

## Rollback (non-destructive; sync records are preserved)
Nothing else references the Store files in this phase, so removal is safe and reversible:
1. Delete `terminow/store_sync.php` and `terminow/store_sync_lib.php` (stops ingestion immediately).
2. Restore the previous `modules/addons/terminow_play/terminow_play.php` (removes the `storeSyncSecret`
   field); Play sync and Premium are unaffected either way.
3. Optionally clear the `storeSyncSecret` value in the addon settings.

**Do NOT drop `mod_terminow_store_sync` as part of rollback.** The table holds the sync records
(associations, lifecycle state, held/needs_link rows) and is kept by default so a later re-enable
resumes without data loss. Leaving it in place is harmless — once `store_sync.php` is removed nothing
reads or writes it. Dropping it is a separate, explicit, operator-initiated decision, never part of a
routine rollback, and only after confirming the records are no longer needed:
`DROP TABLE mod_terminow_store_sync;` (manual, last resort).

No WHMCS core table or file is modified by this change, so there is nothing else to revert.
