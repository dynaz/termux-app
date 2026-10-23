'use strict';

const test = require('node:test');
const assert = require('node:assert');
const sub = require('../../src/subscription');
const config = require('../../src/config');
const { redact } = require('../../src/errors');
const { WhmcsClient } = require('../../src/whmcs');
const { DAY, PRODUCT_MAP, subscription } = require('../fakes');

const now = Date.UTC(2026, 9, 14);

test('active, cancelled and grace period give access until the verified expiry', () => {
  for (const state of ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_CANCELED', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD']) {
    assert.equal(sub.evaluate(subscription({ state, expiry: now + DAY }), PRODUCT_MAP, now).entitled, true, state);
    assert.equal(sub.evaluate(subscription({ state, expiry: now - 1 }), PRODUCT_MAP, now).entitled, false, state + ' past expiry');
  }
});

test('hold, pause, pending, expired and revoked give no access', () => {
  for (const state of ['SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED', 'SUBSCRIPTION_STATE_PENDING',
    'SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED'])
    assert.equal(sub.evaluate(subscription({ state, expiry: now + DAY }), PRODUCT_MAP, now).entitled, false, state);
});

test('auto renew off alone does not end access', () => {
  const facts = sub.evaluate(subscription({ state: 'SUBSCRIPTION_STATE_CANCELED', autoRenew: false, expiry: now + 5 * DAY }), PRODUCT_MAP, now);
  assert.equal(facts.entitled, true);
  assert.equal(facts.autoRenew, false);
});

test('unknown products and base plans are rejected', () => {
  assert.throws(() => sub.evaluate(subscription({ productId: 'other', expiry: now + DAY }), PRODUCT_MAP, now), { code: 'PRODUCT_NOT_ALLOWED' });
  assert.throws(() => sub.evaluate(subscription({ basePlanId: 'weekly', expiry: now + DAY }), PRODUCT_MAP, now), { code: 'PRODUCT_NOT_ALLOWED' });
});

test('pending purchases are never acknowledged, entitled ones once', () => {
  const pending = sub.evaluate(subscription({ state: 'SUBSCRIPTION_STATE_PENDING', expiry: now + DAY }), PRODUCT_MAP, now);
  assert.equal(sub.needsAcknowledgement(pending), false);
  assert.equal(sub.needsAcknowledgement(sub.evaluate(subscription({ expiry: now + DAY }), PRODUCT_MAP, now)), true);
  assert.equal(sub.needsAcknowledgement(sub.evaluate(subscription({ expiry: now + DAY, acknowledged: true }), PRODUCT_MAP, now)), false);
});

test('test purchases come from Google data and are not revenue', () => {
  const facts = sub.evaluate(subscription({ expiry: now + 5 * 60 * 1000, test: true }), PRODUCT_MAP, now);
  assert.equal(facts.test, true);
  assert.equal(facts.entitled, true);
  assert.equal(sub.isRevenue({ ...facts }), false);
  assert.equal(sub.isRevenue(sub.evaluate(subscription({ expiry: now + DAY }), PRODUCT_MAP, now)), true);
});

test('the entitlement takes the longest entitled purchase and skips replaced ones', () => {
  const base = { tier: 'premium', productId: 'terminow_premium', basePlanId: 'monthly', lastVerifiedAt: now };
  const e = sub.entitlementFrom([
    { ...base, state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTime: now + 400 * DAY, supersededBy: 'x' },
    { ...base, state: 'SUBSCRIPTION_STATE_ACTIVE', expiryTime: now + 30 * DAY },
    { ...base, state: 'SUBSCRIPTION_STATE_EXPIRED', expiryTime: now - DAY },
  ], now);
  assert.equal(e.premium, true);
  assert.equal(e.expiresAt, now + 30 * DAY);
  assert.deepEqual(sub.entitlementFrom([], now), { premium: false, subscriptionState: 'NONE' });
});

test('account ids are hashes of the uid, not the uid or an email', () => {
  const id = sub.accountIdFor('alice');
  assert.match(id, /^[0-9a-f]{64}$/);
  assert.equal(id.includes('alice'), false);
  // The Android app computes the same, see TerminowBilling.accountId
  assert.equal(id, '51a095186e84d968360b1725b7502d99a0364cfe794c20c09f64c723dd254c22');
});

test('configuration is checked and the product map validated', () => {
  const good = config.load({ PLAY_PACKAGE_NAME: 'com.terminow', PLAY_PRODUCT_MAP: JSON.stringify(PRODUCT_MAP) });
  assert.deepEqual(good.problems, []);
  assert.doesNotThrow(() => config.requireUsable(good));
  const bad = config.load({ PLAY_PACKAGE_NAME: '', PLAY_PRODUCT_MAP: '{"x":{}}', WHMCS_SYNC_URL: 'https://example.com/sync', WHMCS_SYNC_SECRET: 'short' });
  assert.deepEqual(bad.problems.map((p) => p.code).sort(), ['CONFIG_PLAY_PACKAGE', 'CONFIG_PRODUCT_MAP', 'CONFIG_WHMCS_SECRET']);
  assert.throws(() => config.requireUsable(bad), { code: 'BACKEND_NOT_CONFIGURED' });
  assert.throws(() => config.validateProductMap({ p: { monthly: { tier: 'premium', whmcsProductId: 0 } } }), { code: 'CONFIG_PRODUCT_MAP' });
});

test('tokens and secrets are redacted from logged text', () => {
  const token = 'abcdefghijklmnopqrstuvwxyz.AO-J1OyabcdefghijklmnopQRSTUVWXYZ0123456789';
  assert.equal(redact('failed for ' + token).includes(token), false);
  assert.equal(redact('Authorization: Bearer ya29.a0AfH6SMBxyz123456').includes('ya29'), false);
  assert.equal(redact('{"private_key":"-----BEGIN"}').includes('BEGIN'), false);
});

test('WHMCS requests are signed with the shared secret', () => {
  const signature = WhmcsClient.sign('s'.repeat(32), '1700000000', '{"a":1}');
  assert.match(signature, /^[0-9a-f]{64}$/);
  assert.notEqual(signature, WhmcsClient.sign('t'.repeat(32), '1700000000', '{"a":1}'));
});
