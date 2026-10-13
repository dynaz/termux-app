'use strict';

// Runs against the Firestore emulator: npm test (firebase emulators:exec)
const test = require('node:test');
const assert = require('node:assert');
const { initializeApp, getApps } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { Service } = require('../../src/service');
const { tokenHash } = require('../../src/subscription');
const { DAY, PRODUCT_MAP, subscription, FakePlay, FakeWhmcs, silentLog } = require('../fakes');

const PROJECT = 'demo-terminow';
if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run with the Firestore emulator: npm test');
if (getApps().length === 0) initializeApp({ projectId: PROJECT });
const db = getFirestore();

const T1 = 'token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.1111111111';
const T2 = 'token-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.2222222222';

async function clear() {
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: 'DELETE' });
}

function setup({ whmcs = new FakeWhmcs() } = {}) {
  const clock = { now: Date.UTC(2026, 9, 14) };
  const play = new FakePlay();
  const service = new Service({
    db, play, whmcs, now: () => clock.now, log: silentLog,
    config: { packageName: 'com.terminow', productMap: PRODUCT_MAP, problems: [] },
  });
  return { clock, play, whmcs, service };
}

async function entitlement(uid) {
  return (await db.collection('entitlements').doc(uid).get()).data();
}

async function purchase(token) {
  return (await db.collection('playPurchases').doc(tokenHash(token)).get()).data();
}

test.beforeEach(clear);

test('a valid purchase activates Premium and is acknowledged after it is recorded', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  const result = await service.verifyForUser('alice', 'alice@example.com', T1);
  assert.equal(result.status, 'ACTIVE');
  assert.equal(result.entitlement.premium, true);
  assert.equal((await entitlement('alice')).sources.google_play.source, 'google_play');
  assert.deepEqual(play.acknowledged, [T1]);
  assert.equal((await purchase(T1)).ackState, 'acknowledged');
  // The raw token is only in the backend record, keyed by its hash
  assert.equal(JSON.stringify(await entitlement('alice')).includes(T1), false);
  assert.equal(JSON.stringify(result).includes(T1), false);
});

test('a pending purchase does not activate Premium and is not acknowledged', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_PENDING', expiry: clock.now + 30 * DAY }));
  const result = await service.verifyForUser('alice', null, T1);
  assert.equal(result.status, 'PENDING');
  assert.equal(result.entitlement.premium, false);
  assert.deepEqual(play.acknowledged, []);
});

test('an unknown product or a token of another app is rejected', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', productId: 'some_other_app_sub', expiry: clock.now + DAY }));
  await assert.rejects(service.verifyForUser('alice', null, T1), { code: 'PRODUCT_NOT_ALLOWED' });
  // Google answers 404 for a token of another package name
  await assert.rejects(service.verifyForUser('alice', null, T2), { code: 'PURCHASE_NOT_FOUND' });
  assert.equal(await entitlement('alice'), undefined);
});

test('a token can not be replayed by another account', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  await service.verifyForUser('alice', null, T1);
  await assert.rejects(service.verifyForUser('mallory', null, T1), { code: 'PURCHASE_OF_ANOTHER_ACCOUNT' });
  assert.equal(await entitlement('mallory'), undefined);
  // Even a purchase without an account id is not given to whoever sends it first
  play.set(T2, subscription({ accountId: '', expiry: clock.now + 30 * DAY }));
  await assert.rejects(service.verifyForUser('mallory', null, T2), { code: 'PURCHASE_OF_ANOTHER_ACCOUNT' });
});

test('simultaneous verifications of the same purchase record it once', async () => {
  const { clock, play, service, whmcs } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  const results = await Promise.all([1, 2, 3].map(() => service.verifyForUser('alice', null, T1)));
  assert.ok(results.every((r) => r.entitlement.premium));
  const all = await db.collection('playPurchases').get();
  assert.equal(all.size, 1);
  await service.syncWhmcs('alice');
  await service.syncWhmcs('alice');
  assert.equal(whmcs.services.size, 1);
});

test('a failed acknowledgement is retried, now and by reconciliation', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  play.ackFailures = 5;
  const result = await service.verifyForUser('alice', null, T1);
  // Premium is recorded before the acknowledgement, which failed three times here
  assert.equal(result.entitlement.premium, true);
  assert.equal(result.acknowledged, false);
  assert.equal(play.ackFailures, 2);
  clock.now += 3 * 60 * 1000;
  play.ackFailures = 0;
  const done = await service.reconcile();
  assert.equal(done.acknowledged, 1);
  assert.equal((await purchase(T1)).ackState, 'acknowledged');
});

test('restore after a reinstall finds the same purchase', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  await service.verifyForUser('alice', null, T1);
  // The new install sends the token that Google Play has on the device again
  const restored = await service.verifyForUser('alice', null, T1);
  assert.equal(restored.status, 'ACTIVE');
  assert.equal(restored.acknowledged, true);
  assert.equal(play.acknowledged.length, 1);
});

test('cancellation keeps access until expiry, then it ends', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 3 * DAY }));
  await service.verifyForUser('alice', null, T1);
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_CANCELED', autoRenew: false, expiry: clock.now + 3 * DAY, acknowledged: true }));
  await service.refresh(T1);
  assert.equal((await entitlement('alice')).premium, true);
  clock.now += 4 * DAY;
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_EXPIRED', autoRenew: false, expiry: clock.now - DAY, acknowledged: true }));
  await service.reconcile();
  const e = await entitlement('alice');
  assert.equal(e.premium, false);
  assert.equal(e.sources.google_play.subscriptionState, 'SUBSCRIPTION_STATE_EXPIRED');
});

test('grace period keeps access, hold and revocation end it', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + DAY }));
  await service.verifyForUser('alice', null, T1);
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', expiry: clock.now + 7 * DAY, acknowledged: true }));
  await service.refresh(T1);
  assert.equal((await entitlement('alice')).premium, true);
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_ON_HOLD', expiry: clock.now - 1, acknowledged: true }));
  await service.refresh(T1);
  assert.equal((await entitlement('alice')).premium, false);
  // Revoked (refunded): Google reports it expired
  play.set(T1, subscription({ uid: 'alice', state: 'SUBSCRIPTION_STATE_EXPIRED', expiry: clock.now - 1, acknowledged: true }));
  await service.refresh(T1);
  assert.equal((await entitlement('alice')).premium, false);
});

test('a plan change replaces the linked purchase without a gap', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 10 * DAY }));
  await service.verifyForUser('alice', null, T1);
  play.set(T2, subscription({ uid: 'alice', basePlanId: 'yearly', expiry: clock.now + 365 * DAY, linked: T1 }));
  await service.verifyForUser('alice', null, T2);
  assert.equal((await purchase(T1)).supersededBy, tokenHash(T2));
  const e = (await entitlement('alice')).sources.google_play;
  assert.equal(e.basePlanId, 'yearly');
  assert.equal(e.expiresAt, clock.now + 365 * DAY);
});

test('notifications: duplicates, out of order, unknown owners and other packages', async () => {
  const { clock, play, service } = setup();
  const message = (token, type) => ({ version: '1.0', packageName: 'com.terminow', eventTimeMillis: String(clock.now),
    subscriptionNotification: { version: '1.0', notificationType: type, purchaseToken: token, subscriptionId: 'terminow_premium' } });

  // A purchase whose owner never opened the app since: kept without an owner
  play.set(T1, subscription({ uid: 'bob', expiry: clock.now + 30 * DAY }));
  assert.equal((await service.handleNotification(message(T1, 4), 'm1')).owner, 'unknown');
  assert.equal((await purchase(T1)).uid, null);
  assert.deepEqual(play.acknowledged, []);
  assert.equal((await service.handleNotification(message(T1, 4), 'm1')).handled, 'duplicate');

  // Once bob verifies in the app, the purchase is his
  await service.verifyForUser('bob', null, T1);
  assert.equal((await entitlement('bob')).premium, true);

  // A late "purchased" notification after the cancellation does not bring back the old state
  play.set(T1, subscription({ uid: 'bob', state: 'SUBSCRIPTION_STATE_EXPIRED', expiry: clock.now - 1, acknowledged: true }));
  await service.handleNotification(message(T1, 13), 'm2');
  await service.handleNotification(message(T1, 4), 'm3');
  assert.equal((await entitlement('bob')).premium, false);

  assert.equal((await service.handleNotification({ ...message(T1, 4), packageName: 'com.other' }, 'm4')).handled, 'ignored');
  assert.equal((await service.handleNotification({ version: '1.0', packageName: 'com.terminow', testNotification: { version: '1.0' } }, 'm5')).handled, 'test');
});

test('a notification finds the owner of a new purchase made in the app', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'carol', expiry: clock.now + 30 * DAY }));
  await service.verifyForUser('carol', null, T1);
  // A renewal purchase token that arrives by notification before the app sends it
  play.set(T2, subscription({ uid: 'carol', expiry: clock.now + 60 * DAY, linked: T1 }));
  await service.handleNotification({ packageName: 'com.terminow', subscriptionNotification: { notificationType: 2, purchaseToken: T2 } }, 'n1');
  assert.equal((await purchase(T2)).uid, 'carol');
  assert.equal((await entitlement('carol')).sources.google_play.expiresAt, clock.now + 60 * DAY);
});

test('WHMCS being down never blocks Premium, and retries do not duplicate services', async () => {
  const whmcs = new FakeWhmcs();
  whmcs.down = true;
  const { clock, play, service } = setup({ whmcs });
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  const result = await service.verifyForUser('alice', 'alice@example.com', T1);
  assert.equal(result.entitlement.premium, true);
  assert.equal(await service.syncWhmcs('alice'), 'retrying');
  let outbox = (await db.collection('whmcsOutbox').doc('alice').get()).data();
  assert.equal(outbox.attempts, 1);
  assert.ok(outbox.nextAttemptAt > clock.now);
  whmcs.down = false;
  clock.now += 2 * 60 * 1000;
  await service.syncDueWhmcs();
  await service.syncDueWhmcs();
  outbox = (await db.collection('whmcsOutbox').doc('alice').get()).data();
  assert.equal(outbox.status, 'synced');
  assert.equal(whmcs.syncs.length, 1);
  assert.equal(whmcs.services.size, 1);
  assert.equal(whmcs.syncs[0].email, 'alice@example.com');
  assert.equal(JSON.stringify(whmcs.syncs[0]).includes(T1), false);
});

test('WHMCS retries are bounded and the failure stays visible', async () => {
  const whmcs = new FakeWhmcs();
  whmcs.down = true;
  const { clock, play, service } = setup({ whmcs });
  play.set(T1, subscription({ uid: 'alice', expiry: clock.now + 30 * DAY }));
  await service.verifyForUser('alice', null, T1);
  let result;
  for (let i = 0; i < 12; i++) result = await service.syncWhmcs('alice');
  assert.equal(result, 'failed');
  const outbox = (await db.collection('whmcsOutbox').doc('alice').get()).data();
  assert.equal(outbox.status, 'failed');
  assert.equal(outbox.lastError.code, 'WHMCS_UNAVAILABLE');
  assert.equal((await entitlement('alice')).premium, true);
});

test('another source of Premium is kept when the Play subscription expires', async () => {
  const { clock, play, service } = setup();
  await db.collection('entitlements').doc('dave').set({ premium: true, sources: { accmove: { premium: true } } });
  play.set(T1, subscription({ uid: 'dave', state: 'SUBSCRIPTION_STATE_EXPIRED', expiry: clock.now - 1, acknowledged: true }));
  await service.verifyForUser('dave', null, T1);
  const e = await entitlement('dave');
  assert.equal(e.premium, true);
  assert.equal(e.sources.google_play.premium, false);
  assert.equal(e.sources.accmove.premium, true);
});

test('test purchases activate Premium and are marked as tests', async () => {
  const { clock, play, service } = setup();
  play.set(T1, subscription({ uid: 'tess', expiry: clock.now + 5 * 60 * 1000, test: true }));
  const result = await service.verifyForUser('tess', null, T1);
  assert.equal(result.test, true);
  assert.equal(result.entitlement.premium, true);
  assert.equal((await purchase(T1)).test, true);
  const outbox = (await db.collection('whmcsOutbox').doc('tess').get()).data();
  assert.equal(outbox.desired.test, true);
});

test('linking a WHMCS account needs a valid code', async () => {
  const { service } = setup();
  await assert.rejects(service.linkWhmcs('alice', 'nope'), { code: 'INVALID_LINK_CODE' });
  await assert.rejects(service.linkWhmcs('alice', 'ZZZZ9999'), { code: 'INVALID_LINK_CODE' });
  assert.deepEqual(await service.linkWhmcs('alice', 'abcd2345'), { linked: true });
  assert.equal((await db.collection('whmcsLinks').doc('alice').get()).data().clientId, 42);
});
