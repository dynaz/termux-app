'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { validateGrant, applyStoreSource } = require('../../src/storeGrant');

const now = Date.UTC(2026, 10, 3);
const DAY = 24 * 60 * 60 * 1000;
const grant = (over = {}) => ({ eventId: 'order:1', terminowRef: 'FBUID1', featureKey: 'terminow-premium', kind: 'grant', expires: now + 30 * DAY, source: 'aivora_devnet', sandbox: true, ...over });

test('validateGrant accepts a well-formed grant and defaults the source', () => {
  const g = validateGrant({ eventId: 'order:9', terminowRef: 'FBUID1', featureKey: 'addon-network-scan', kind: 'grant', expires: null, sandbox: true });
  assert.equal(g.source, 'aivora_devnet');
  assert.equal(g.expires, null);
  assert.equal(g.sandbox, true);
});

test('validateGrant rejects malformed fields', () => {
  for (const over of [{ eventId: '' }, { terminowRef: 'bad ref!' }, { featureKey: 'BadKey' }, { kind: 'nope' }, { expires: -5 }, { expires: 1.5 }]) {
    assert.throws(() => validateGrant(grant(over)), { code: 'INVALID_GRANT' }, JSON.stringify(over));
  }
});

test('a sandbox grant records a VISIBLE source but never flips real premium', () => {
  const doc = applyStoreSource(null, grant(), { now, allowPremium: false });
  assert.equal(doc.premium, false);                                   // real Premium stays disabled
  assert.equal(doc.sources.aivora_devnet.sandbox, true);
  assert.equal(doc.sources.aivora_devnet.premium, false);
  assert.equal(doc.sources.aivora_devnet.features['terminow-premium'].tier, 'premium');
});

test('with real grants allowed, an active premium feature sets premium true', () => {
  const doc = applyStoreSource(null, grant({ sandbox: false }), { now, allowPremium: true });
  assert.equal(doc.premium, true);
  assert.equal(doc.sources.aivora_devnet.premium, true);
});

test('an add-on never confers premium, even with real grants allowed', () => {
  const doc = applyStoreSource(null, grant({ featureKey: 'addon-network-scan', expires: null, sandbox: false }), { now, allowPremium: true });
  assert.equal(doc.premium, false);
  assert.equal(doc.sources.aivora_devnet.features['addon-network-scan'].tier, 'addon');
  assert.equal(doc.sources.aivora_devnet.features['addon-network-scan'].expiresAt, null);
});

test('the Store source never clobbers an existing google_play premium source', () => {
  const existing = { premium: true, sources: { google_play: { source: 'google_play', premium: true, tier: 'premium' } } };
  const doc = applyStoreSource(existing, grant(), { now, allowPremium: false });
  assert.equal(doc.sources.google_play.premium, true);               // preserved
  assert.equal(doc.premium, true);                                    // still premium via google_play
  assert.ok(doc.sources.aivora_devnet);                              // store source added alongside
});

test('two features coexist under one source; a revoke recomputes premium', () => {
  let doc = applyStoreSource(null, grant({ sandbox: false }), { now, allowPremium: true });
  doc = applyStoreSource(doc, grant({ eventId: 'order:2', featureKey: 'addon-network-scan', expires: null, sandbox: false }), { now, allowPremium: true });
  assert.equal(Object.keys(doc.sources.aivora_devnet.features).length, 2);
  assert.equal(doc.premium, true);

  doc = applyStoreSource(doc, grant({ eventId: 'order:3', kind: 'revoke', sandbox: false }), { now: now + 1, allowPremium: true });
  assert.equal(doc.sources.aivora_devnet.features['terminow-premium'].revoked, true);
  assert.equal(doc.premium, false);                                   // premium feature revoked
});

test('an expired premium feature does not confer premium', () => {
  const doc = applyStoreSource(null, grant({ expires: now - 1, sandbox: false }), { now, allowPremium: true });
  assert.equal(doc.premium, false);
});
