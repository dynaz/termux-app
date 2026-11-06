'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHmac } = require('node:crypto');
const { verifySignature, parseDelivery, verifyLink, applyWhmcsEvent, makeHandler } = require('../../src/entitlement');

const SECRET = 's'.repeat(40);
const NOW = 1800000000000; // ms

// A minimal transactional Firestore fake: documents keyed by "collection/id", with runTransaction.
function fakeDb(seed = {}) {
  const store = new Map(Object.entries(seed));
  const ref = (col, id) => ({ _key: col + '/' + id });
  const snap = (key) => ({ exists: store.has(key), data: () => store.get(key) });
  return {
    store,
    collection: (col) => ({ doc: (id) => ref(col, id) }),
    async runTransaction(fn) {
      const tx = {
        async get(r) { return snap(r._key); },
        set(r, data) { store.set(r._key, data); },
        update(r, data) { store.set(r._key, { ...(store.get(r._key) || {}), ...data }); },
      };
      return fn(tx);
    },
  };
}

function delivery({ uid = 'alice', clientId = 42, featureKey = 'terminow-premium', active = true, expiresAt = null, type = 'premium', state = 'Active', seq = 1 } = {}) {
  return { event: { uid, clientId, source: 'whmcs', featureKey, active, expiresAt, type, state }, seq, idempotencyKey: 'idem-' + uid + '-' + featureKey + '-' + seq };
}

function signed(body, secret = SECRET, nowMs = NOW) {
  const raw = JSON.stringify(body);
  const timestamp = Math.floor(nowMs / 1000).toString();
  const signature = createHmac('sha256', secret).update(timestamp + '.' + raw).digest('hex');
  return { method: 'POST', headers: { 'x-terminow-timestamp': timestamp, 'x-terminow-signature': signature }, rawBody: raw };
}

test('verifySignature accepts a good signature and rejects tampering/old/short-secret', () => {
  const raw = '{"a":1}';
  const ts = Math.floor(NOW / 1000).toString();
  const sig = createHmac('sha256', SECRET).update(ts + '.' + raw).digest('hex');
  assert.doesNotThrow(() => verifySignature(SECRET, ts, sig, raw, NOW));
  assert.throws(() => verifySignature(SECRET, ts, sig, raw + 'x', NOW), /signature/i);
  assert.throws(() => verifySignature(SECRET, (Math.floor(NOW / 1000) - 1000).toString(), sig, raw, NOW), /too old/i);
  assert.throws(() => verifySignature('short', ts, sig, raw, NOW), /not set/i);
});

test('parseDelivery enforces the whmcs source and a valid shape', () => {
  assert.equal(parseDelivery(delivery()).featureKey, 'terminow-premium');
  assert.throws(() => parseDelivery({ ...delivery(), event: { ...delivery().event, source: 'google_play' } }), /whmcs source/);
  assert.throws(() => parseDelivery({ ...delivery(), event: { ...delivery().event, uid: 'bad uid' } }), /uid/);
  assert.throws(() => parseDelivery({ ...delivery(), event: { ...delivery().event, clientId: 0 } }), /clientId/);
  assert.throws(() => parseDelivery({ ...delivery(), seq: 0 }), /seq/);
});

test('verifyLink requires the clientId to match the authoritative link', () => {
  assert.equal(verifyLink({ clientId: 42 }, { clientId: 42 }), true);
  assert.equal(verifyLink({ clientId: 7 }, { clientId: 42 }), false);
  assert.equal(verifyLink(null, { clientId: 42 }), false);
});

test('applyWhmcsEvent merges only sources.whmcs and preserves other sources', () => {
  const base = { premium: true, sources: { google_play: { premium: true, source: 'google_play' }, aivora_devnet: { premium: false, source: 'aivora_devnet' } } };
  const event = parseDelivery(delivery({ active: true }));
  const out = applyWhmcsEvent({ entitlement: base, event, now: NOW, grantsPremium: false });
  assert.equal(out.outcome, 'applied');
  assert.ok(out.entitlement.sources.google_play, 'google_play preserved');
  assert.ok(out.entitlement.sources.aivora_devnet, 'store source preserved');
  assert.equal(out.entitlement.sources.whmcs.features['terminow-premium'].active, true);
  assert.equal(out.entitlement.sources.whmcs.premium, false, 'gated off: whmcs does not grant premium');
  assert.equal(out.entitlement.premium, true, 'top-level premium still true from google_play');
});

test('applyWhmcsEvent flips premium when grants are enabled; expiry makes a feature inactive', () => {
  const on = applyWhmcsEvent({ entitlement: { premium: false }, event: parseDelivery(delivery()), now: NOW, grantsPremium: true });
  assert.equal(on.entitlement.sources.whmcs.premium, true);
  assert.equal(on.entitlement.premium, true);
  const expired = applyWhmcsEvent({ entitlement: { premium: false }, event: parseDelivery(delivery({ expiresAt: NOW - 1 })), now: NOW, grantsPremium: true });
  assert.equal(expired.entitlement.sources.whmcs.features['terminow-premium'].active, false, 'past expiry is inactive');
  assert.equal(expired.entitlement.premium, false);
});

test('applyWhmcsEvent ignores a stale/duplicate seq', () => {
  let ent = { premium: false };
  ent = applyWhmcsEvent({ entitlement: ent, event: parseDelivery(delivery({ seq: 2, active: true })), now: NOW, grantsPremium: true }).entitlement;
  const older = applyWhmcsEvent({ entitlement: ent, event: parseDelivery(delivery({ seq: 1, active: false })), now: NOW, grantsPremium: true });
  assert.equal(older.outcome, 'stale');
  assert.equal(older.entitlement.sources.whmcs.features['terminow-premium'].active, true, 'older event did not overwrite');
});

// ---- Full transactional handler ----
test('handler rejects a bad signature and a non-POST', async () => {
  const db = fakeDb();
  const handler = makeHandler({ db, secret: SECRET, now: () => NOW });
  const bad = signed(delivery());
  bad.headers['x-terminow-signature'] = 'deadbeef';
  assert.equal((await handler(bad)).status, 401);
  assert.equal((await handler({ ...signed(delivery()), method: 'GET' })).status, 405);
});

test('handler holds (409) when there is no verified client<->uid link, and writes nothing', async () => {
  const db = fakeDb();                                   // no whmcsLinks
  const handler = makeHandler({ db, secret: SECRET, now: () => NOW });
  const res = await handler(signed(delivery()));
  assert.equal(res.status, 409);
  assert.equal(res.body.held, true);
  assert.equal(db.store.has('entitlements/alice'), false, 'nothing written while held');
  assert.equal(db.store.has('whmcsDeliveries/idem-alice-terminow-premium-1'), false, 'no delivery record while held, so a retry reprocesses');
});

test('handler applies a linked event, is idempotent, and preserves google_play', async () => {
  const db = fakeDb({
    'whmcsLinks/alice': { clientId: 42 },
    'entitlements/alice': { premium: true, sources: { google_play: { premium: true, source: 'google_play' } } },
  });
  const handler = makeHandler({ db, secret: SECRET, now: () => NOW });
  const first = await handler(signed(delivery()));
  assert.equal(first.body.outcome, 'applied');
  const ent = db.store.get('entitlements/alice');
  assert.ok(ent.sources.google_play, 'google_play preserved');
  assert.equal(ent.sources.whmcs.features['terminow-premium'].active, true);
  assert.equal(ent.premium, true);
  // resend the same delivery -> duplicate, no change
  const again = await handler(signed(delivery()));
  assert.equal(again.body.duplicate, true);
});

test('handler rejects a link whose clientId does not match', async () => {
  const db = fakeDb({ 'whmcsLinks/alice': { clientId: 999 } });
  const handler = makeHandler({ db, secret: SECRET, now: () => NOW });
  const res = await handler(signed(delivery({ clientId: 42 })));
  assert.equal(res.status, 409);
});

test('handler orders events: a later seq applied, then an older seq is a stale no-op', async () => {
  const db = fakeDb({ 'whmcsLinks/bob': { clientId: 42 }, 'entitlements/bob': { premium: false } });
  const handler = makeHandler({ db, secret: SECRET, grantsPremium: true, now: () => NOW });
  await handler(signed(delivery({ uid: 'bob', seq: 2, active: true })));
  const res = await handler(signed(delivery({ uid: 'bob', seq: 1, active: false })));
  assert.equal(res.body.outcome, 'stale');
  assert.equal(db.store.get('entitlements/bob').sources.whmcs.features['terminow-premium'].active, true);
});

// --- Rollback semantics of WHMCS_GRANTS_PREMIUM (documented, verified behavior) -------------------
// Disabling the gate is a FORWARD control: it stops future grants. It does NOT retroactively rewrite
// entitlement docs. An already-granted account keeps whmcs.premium=true until the NEXT applied event
// recomputes it with the gate off. A duplicate/stale delivery never rewrites. Other sources are never
// touched by this endpoint. These assertions pin that contract so the rollback docs cannot drift.
test('gate off does not rewrite an already-granted doc until the next applied event', () => {
  // 1) Granted while the gate was ON, alongside a Google Play grant.
  const seeded = { premium: true, sources: { google_play: { premium: true } } };
  const onEvent = { uid: 'al', clientId: 1, source: 'whmcs', featureKey: 'terminow-premium', active: true, expiresAt: NOW + 1e9, state: 'Active', type: 'premium' };
  const granted = applyWhmcsEvent({ entitlement: seeded, event: { ...onEvent, seq: 1 }, now: NOW, grantsPremium: true }).entitlement;
  assert.equal(granted.sources.whmcs.premium, true);
  assert.equal(granted.premium, true);

  // 2) A stale/duplicate delivery after the gate flips OFF: unchanged (no retroactive revoke).
  const stale = applyWhmcsEvent({ entitlement: granted, event: { ...onEvent, seq: 1 }, now: NOW + 10, grantsPremium: false });
  assert.equal(stale.outcome, 'stale');
  assert.equal(stale.entitlement.sources.whmcs.premium, true, 'gate-off alone does not revoke the existing grant');

  // 3) The NEXT applied event (higher seq) with the gate OFF re-evaluates: whmcs stops granting, but a
  //    still-active WHMCS service is recorded, and Google Play is preserved and still carries Premium.
  const next = applyWhmcsEvent({ entitlement: granted, event: { ...onEvent, seq: 2 }, now: NOW + 20, grantsPremium: false }).entitlement;
  assert.equal(next.sources.whmcs.premium, false, 'gate off: the next applied event does not grant Premium');
  assert.equal(next.sources.whmcs.features['terminow-premium'].active, true, 'the WHMCS source is still recorded (visible)');
  assert.equal(next.sources.google_play.premium, true, 'Google Play is preserved');
  assert.equal(next.premium, true, 'top-level Premium still true via Google Play');
});

test('gate off: the only source losing its grant drops top-level premium', () => {
  const granted = applyWhmcsEvent({ entitlement: { premium: false }, event: { uid: 'solo', clientId: 2, source: 'whmcs', featureKey: 'terminow-premium', active: true, expiresAt: NOW + 1e9, state: 'Active', type: 'premium', seq: 1 }, now: NOW, grantsPremium: true }).entitlement;
  assert.equal(granted.premium, true);
  const off = applyWhmcsEvent({ entitlement: granted, event: { uid: 'solo', clientId: 2, source: 'whmcs', featureKey: 'terminow-premium', active: true, expiresAt: NOW + 1e9, state: 'Active', type: 'premium', seq: 2 }, now: NOW + 10, grantsPremium: false }).entitlement;
  assert.equal(off.sources.whmcs.premium, false);
  assert.equal(off.premium, false, 'no other source -> premium drops on the next applied event');
});
