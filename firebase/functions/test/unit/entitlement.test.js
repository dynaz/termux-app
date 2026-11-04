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
