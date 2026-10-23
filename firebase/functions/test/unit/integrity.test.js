'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { requestHash, check, IntegrityObserver, MAX_AGE_MS } = require('../../src/integrity');
const { TermiNowError } = require('../../src/errors');

const NOW = Date.UTC(2026, 9, 21);
const NONCE = '00112233445566778899aabbccddeeff';
const PACKAGE = 'com.terminow';

/** Firestore with create() that refuses a document that exists, like the real one. */
function fakeDb() {
  const docs = new Map();
  const events = [];
  return {
    docs, events,
    collection(name) {
      return {
        doc(id) {
          return {
            async create(data) {
              const key = name + '/' + id;
              if (docs.has(key)) throw Object.assign(new Error('exists'), { code: 6 });
              docs.set(key, data);
            },
            collection() {
              return { async add(event) { events.push({ uid: id, ...event }); } };
            },
          };
        },
      };
    },
  };
}

function payload({ uid = 'alice', nonce = NONCE, at = NOW - 1000, pkg = PACKAGE } = {}) {
  return {
    requestDetails: { requestPackageName: pkg, requestHash: requestHash(uid, nonce), timestampMillis: String(at) },
    appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: pkg, versionCode: '197', certificateSha256Digest: ['x'] },
    deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
    accountDetails: { appLicensingVerdict: 'LICENSED' },
  };
}

function observer(decoded) {
  const db = fakeDb();
  const client = { decode: async () => { if (decoded instanceof Error) throw decoded; return decoded; } };
  return { db, observer: new IntegrityObserver({ db, client, packageName: PACKAGE, now: () => NOW }) };
}

test('the request hash is the one of the app', () => {
  // The same value is in TerminowSignedEntitlementTest of the app
  assert.strictEqual(requestHash('alice', NONCE), '5b380f59549e17b56ae5d2b795ebf3f243a24379327ab26fa8d1f0470d6b57f6');
});

test('a token of this account, package and time is recorded with its verdicts only', async () => {
  const { db, observer: o } = observer(payload());
  assert.deepStrictEqual(await o.observe('alice', { nonce: NONCE, integrityToken: 'token-of-play', distribution: 'play' }), { signed: null });
  assert.strictEqual(db.events.length, 1);
  const event = db.events[0];
  assert.strictEqual(event.result, 'DECODED');
  assert.deepStrictEqual(event.verdicts, { app: 'PLAY_RECOGNIZED', versionCode: '197', device: ['MEETS_DEVICE_INTEGRITY'], licensing: 'LICENSED' });
  assert.ok(!JSON.stringify(event).includes('token-of-play'), 'the token is never kept');
});

test('another account, another package or an old token fails its check but is still only recorded', () => {
  const at = { uid: 'alice', nonce: NONCE, packageName: PACKAGE, now: NOW };
  assert.strictEqual(check(payload({ uid: 'bob' }), at).requestHashMatches, false);
  assert.strictEqual(check(payload({ pkg: 'com.other' }), at).packageMatches, false);
  assert.strictEqual(check(payload({ at: NOW - MAX_AGE_MS - 1 }), at).fresh, false);
  assert.strictEqual(check(payload({ at: NOW + 10 * 60 * 1000 }), at).fresh, false);
  assert.strictEqual(check({}, at).fresh, false);
});

test('a nonce is accepted once', async () => {
  const { db, observer: o } = observer(payload());
  await o.observe('alice', { nonce: NONCE, integrityToken: 't' });
  assert.deepStrictEqual(await o.observe('alice', { nonce: NONCE, integrityToken: 't' }), { signed: null });
  assert.deepStrictEqual(db.events.map((e) => e.result), ['DECODED', 'REPLAYED_NONCE']);
});

test('no token, a refused token or Play Integrity being down never fails the request', async () => {
  let o = observer(payload());
  assert.deepStrictEqual(await o.observer.observe('alice', { nonce: NONCE, integrityError: 'UNSUPPORTED', distribution: 'full' }), { signed: null });
  assert.strictEqual(o.db.events[0].result, 'NO_TOKEN');
  assert.strictEqual(o.db.events[0].appError, 'UNSUPPORTED');
  o = observer(new TermiNowError('INTEGRITY_UNAVAILABLE', 'down', true));
  assert.deepStrictEqual(await o.observer.observe('alice', { nonce: NONCE, integrityToken: 't' }), { signed: null });
  assert.strictEqual(o.db.events[0].result, 'INTEGRITY_UNAVAILABLE');
  o = observer(new Error('boom'));
  await o.observer.observe('alice', { nonce: NONCE, integrityToken: 't' });
  assert.strictEqual(o.db.events[0].result, 'DECODE_FAILED');
});

test('a malformed nonce or app error is refused or dropped', async () => {
  const { observer: o, db } = observer(payload());
  await assert.rejects(o.observe('alice', { nonce: '../x' }), { code: 'INVALID_NONCE' });
  await o.observe('alice', { nonce: NONCE, integrityError: 'x'.repeat(100) });
  assert.strictEqual(db.events[0].appError, null);
});
