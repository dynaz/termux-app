# TermiNow Store ↔ WHMCS integration — extension design

**Status:** draft (not implemented). Written from an inspection of the existing module; pending the
live WHMCS core version and SSH access to `cp.accmove.com`.

This describes how the TermiNow **Store** (Devnet sandbox storefront, `store.terminow.online`, repo
`dynaz/terminowai`) connects to the existing WHMCS install at `cp.accmove.com`, by **extending** the
current `whmcs/terminow/` module — not replacing it and not building a parallel catalog.

---

## 1. Goals and non-goals

**Goals**
- WHMCS is the authoritative source for the **production** catalog, pricing and billing.
- The Store carries a **mapping** from each Store product to its WHMCS catalog object and the app
  **feature key** it unlocks (already built: `whmcs_product_map`, see §4).
- A Store purchase can be **mirrored** into WHMCS as a client/service record — the same way Google
  Play purchases already are — keeping sources separate and reconcilable.
- Reuse the existing signed-endpoint + `mod_terminow_*` + `localAPI` pattern, so there is one
  integration, one set of feature keys, and one place the app reads entitlements from.

**Non-goals / hard constraints**
- **No WHMCS core-table writes.** Every write goes through `localAPI` (`AddClient`, `AddOrder`,
  `AcceptOrder`, `UpdateClientProduct`), exactly as `play_sync_lib.php` does today.
- **No parallel catalog.** The Store does not become a second source of products/prices.
- **Devnet sandbox stays sandbox.** A Store sandbox grant must **never** count as real Premium and
  must **never** mark a production invoice paid. Mainnet stays disabled.
- **Never merge accounts by email.** uid ↔ WHMCS client is proven by a login + one-time code, as now.

---

## 2. What exists today (recap)

| Component | Direction | Auth | Purpose |
|---|---|---|---|
| `terminow/premium.php` + `whmcs_glue.php` | app → WHMCS (read) | Firebase ID token (`linex-bf945`) | Answers `{premium, plan, nextDueDate, source:'accmove'}` via `localAPI`. Premium = an **Active** service of a product whose name/group contains "TermiNow" or id in `TERMINOW_PREMIUM_PRODUCT_IDS=[90]`. |
| `terminow/play_sync.php` + `play_sync_lib.php` | Firebase → WHMCS (write) | HMAC-SHA256, `syncSecret`, 300 s window | Mirrors Google Play subscribers as a WHMCS client + **free, non-invoiced** service. State in `mod_terminow_play_sync` (PK = Firebase uid). Actions `sync`, `redeem_link`. |
| `modules/addons/terminow_play/` + `terminow/link.php` | client-area | WHMCS login + form token | Mints the **8-char** one-time code the app's "Link existing account" dialog redeems (uid ↔ client). |

Key invariants already enforced, which the extension inherits:
- Play-managed services are **excluded** from the Premium decision (`play_service_ids()` filters them
  out in `terminow_whmcs_premium()`), so a mirrored service is not self-referentially "Premium".
- Idempotency via a `version` counter + `status` + a hashed **marker** in the service admin notes, so
  a retry finds the existing service instead of ordering again.
- A colliding email is **held** (`needs_link`), never merged.

---

## 3. Authority model (the key decision)

Firebase (`entitlements/{uid}`) **remains the entitlement authority** the app reads. WHMCS keeps a
**mirror** client/service record for reporting/billing continuity, exactly as it does for Play.

```
Devnet payment verified (Store server, cluster-checked)
      │
      ▼
storeEntitlement  (Firebase Cloud Function, already deployed)
      │  writes entitlements/{uid}.sources.aivora_devnet = { premium:false (sandbox), ... }
      ▼
syncWhmcs  (Firebase onWrite whmcsOutbox/{uid}, already exists for Play)
      │  POST (HMAC-signed)  → cp.accmove.com/terminow/store_sync.php   [NEW]
      ▼
store_sync  (WHMCS, NEW action in play_sync_lib.php)
      │  localAPI only: AddClient? / AddOrder(free,noinvoice) / UpdateClientProduct
      ▼
mod_terminow_store_sync   (NEW table, separate ledger)   +   service marked SANDBOX
```

The Store's `whmcs_product_map` is the **Store-side equivalent of `PLAY_PRODUCT_MAP`**: it resolves a
Store product → `whmcs_product_id`/`whmcs_addon_id` + `feature_key` + type + sandbox price. The Store
passes the resolved WHMCS id through to Firebase in the `storeEntitlement` payload, and Firebase
forwards it to `store_sync` — the same shape Play already uses (`whmcsProductId`). WHMCS therefore
does **not** need to call the Store, and the correspondence stays admin-maintained in one place.

---

## 4. The Store-side mapping (already built — `dynaz/terminowai`)

Table `whmcs_product_map` (migration `007-whmcs-product-map`), model `src/whmcs-map.mjs`, admin UI in
`src/admin-page.mjs` behind `admins.isAdmin`:

| column | meaning |
|---|---|
| `store_product_id` (PK) | the Store's own product id |
| `whmcs_product_id` **XOR** `whmcs_addon_id` | the one WHMCS catalog object (unique-indexed both ways) |
| `feature_key` | the app entitlement key it unlocks |
| `display_name`, `description` | admin labels |
| `type` (`premium`\|`addon`) | entitlement kind |
| `duration_days` (NULL = permanent) | expiry |
| `parent_service_required` | add-on needs an active parent Premium service |
| `store_visible` | shown in the sandbox storefront (requires a sandbox price) |
| `sandbox_amount` | Devnet sandbox price, raw SPL base units |

This is metadata only today; §5–§7 describe the code that will *consume* it.

---

## 5. WHMCS-side changes (new, to implement)

All additive; no existing file's behaviour changes except small, isolated hooks noted below.

### 5.1 New endpoint `terminow/store_sync.php`
A near-copy of `play_sync.php`: POST only, read the addon settings, verify the HMAC signature, load
`init.php`, dispatch to a new `store_sync()` in `play_sync_lib.php`. It may reuse `syncSecret`, or use
a **separate `storeSyncSecret`** addon setting (recommended, so Store and Play rotate independently).

### 5.2 New action `store_sync()` in `play_sync_lib.php`
Mirrors `sync()` but writes to a **separate table** `mod_terminow_store_sync` so the Store ledger is
distinct from Play (spec: keep sources separate). Request shape from Firebase:

```json
{ "action": "store_sync", "uid": "...", "version": 3, "email": "...",
  "featureKey": "terminow-premium", "whmcsProductId": 91, "whmcsAddonId": null,
  "type": "premium", "sandbox": true, "expiresAt": 1764547200000, "eventId": "..." }
```

Behaviour:
- Resolve/attach the WHMCS client by uid (reuse `mod_terminow_play_sync` link, or a shared link table
  — see §6); if only an email is known and a client already has it, **hold `needs_link`**.
- Create/update a **free, non-invoiced** service via `localAPI` for the mapped `whmcsProductId`
  (same free-paytype guard as Play), **or** record an add-on against the parent service when
  `whmcsAddonId` is set and `parent_service_required`.
- **Gate sandbox:** while `sandbox:true`, the mirrored service MUST NOT be counted as Premium. Two
  independent guards (belt and suspenders):
  1. Map sandbox grants to products **not** in `TERMINOW_PREMIUM_PRODUCT_IDS` and whose name/group
     does **not** contain "TermiNow"; and
  2. exclude `mod_terminow_store_sync.service_id` from the Premium decision (new
     `store_service_ids()`, mirroring `play_service_ids()`), see §5.4.
- Idempotent via `version` + hashed marker in notes + Firebase-side `storeDeliveries/{eventId}`.
- Return `{status, clientId, serviceId}`; a WHMCS error → 502 so Firebase retries.

### 5.3 New table `mod_terminow_store_sync`
Same spirit as `mod_terminow_play_sync`: PK `uid`, plus `feature_key`, `whmcs_product_id`,
`whmcs_addon_id`, `type`, `sandbox`, `expires_at`, `status`, `last_error`, `version`, timestamps.
Created in `migrate()` (extend it) so the addon activate path provisions it.

### 5.4 Premium-decision exclusion (one isolated edit to `whmcs_glue.php`)
`terminow_whmcs_premium()` already filters out Play services. Add the Store-sandbox services to the
same filter so a sandbox mirror can never satisfy `decide()`:
```php
$exclude = array_merge(\TermiNow\PlaySync\play_service_ids($db), \TermiNow\StoreSync\store_service_ids($db));
```
This is the only change to an existing read path, and it only **removes** rows from consideration —
it can never turn a non-Premium account Premium.

### 5.5 Admin dashboard
Add a Store section to the `terminow_play` addon output (or a sibling addon) listing
`mod_terminow_store_sync` rows with the same Retry-sync affordance.

---

## 6. Account association
Reuse the existing proof-of-ownership: a WHMCS client is bound to a Firebase **uid** by the 8-char
one-time code from the client area (`redeem_link`). Store Google sign-ins already resolve to the same
uid automatically (shared project `linex-bf945`), so no new linking UX is needed for them; local Store
accounts use the Store's own one-time code to obtain a uid first. **Never** merge by email.
Open item (§9): whether Store and Play share one uid↔client link table or keep separate ones.

---

## 7. Idempotency, retry, reconciliation
- **Firebase → WHMCS** carries a monotonic `version` and an `eventId`; `store_sync()` ignores an older
  or already-applied version and is safe to replay (marker-in-notes finds the existing service).
- **Delivery** reuses the existing outbox/retry machinery (`whmcsOutbox` + `retryWhmcsSync`); a 502
  from WHMCS is retried, Premium never depends on it succeeding.
- **Reconciliation:** a periodic job (mirror of `reconcilePlayPurchases`) compares
  `entitlements/{uid}.sources.aivora_devnet` against `mod_terminow_store_sync` and re-enqueues drift.

---

## 8. Security
- Server-side HMAC secret only (`storeSyncSecret`), ≥32 chars, 300 s replay window, `hash_equals`.
- Least privilege: `localAPI` only; no external API credentials; no WHMCS core-table writes.
- **No logging** of tokens, secrets, link codes, or the sandbox signature. Errors are sanitized
  (`sanitize()`), as today.
- Devnet signatures are never used to mark production invoices paid; sandbox services are free and
  non-invoiced.

---

## 9. Open items (need server access / a decision)
1. **WHMCS core version** — confirm before coding (admin → Help → About, or `tblconfiguration` `Version`
   row) to pin `localAPI` param shapes (`GetProducts`/`AddOrder`/`UpdateClientProduct`).
2. **Which free product id** backs sandbox Store mirrors (must be pay-type Free, and deliberately
   **not** a Premium-counting product).
3. **Separate `storeSyncSecret`** vs reuse `syncSecret` (recommend separate).
4. **Shared vs separate uid↔client link table** for Store and Play.
5. **Do we mirror sandbox to WHMCS at all**, or keep sandbox purely in Firebase until real grants are
   enabled? Recommend making the WHMCS mirror **flag-gated** (off by default); turn it on only when a
   real (non-sandbox) Store grant path is approved, alongside `STORE_GRANTS_PREMIUM` /
   `AIVORA_REAL_GRANTS`.

---

## 10. Rollout
1. Enable `cp.accmove.com` as a bridge host; read the WHMCS version; inspect the live module matches
   this repo.
2. Land §5 behind an addon setting that is **off** by default (no behaviour change when off).
3. Dry-run with a linked test account; verify a sandbox grant produces a **non-Premium** mirror and
   `premium.php` still answers `false` for it.
4. Only after review, and only for a real (non-sandbox) path, consider counting a Store grant as
   Premium — a separate, explicitly-flagged change. Mainnet remains out of scope.
