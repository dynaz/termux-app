'use strict';

// Cross-component contract test: a delivery SIGNED by the WHMCS side (whmcs/terminow/entitlement_lib.php
// -> hooks.php envelope) must be accepted by this Firebase writer unchanged. The fixture below was
// produced by the real PHP sign() over the exact envelope hooks.php POSTs, so this proves the HMAC
// scheme and the payload shape agree byte-for-byte across PHP and Node — not a mock of either side.
//
// Regenerate the fixture with (in the WHMCS repo):
//   php -r 'require "terminow/entitlement_lib.php"; use function TermiNow\Entitlement\sign;
//     $s=str_repeat("k",40); $e=["uid"=>"alice","clientId"=>42,"source"=>"whmcs","featureKey"=>"terminow-premium",
//     "active"=>true,"expiresAt"=>null,"state"=>"Active","type"=>"premium"];
//     $b=json_encode(["event"=>$e,"idempotencyKey"=>hash("sha256","alice|terminow-premium|whmcs|1"),"seq"=>1]);
//     echo json_encode(["secret"=>$s,"timestamp"=>"1800000000","signature"=>sign($s,"1800000000",$b),"body"=>$b]);'

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHmac } = require('node:crypto');
const { makeHandler, verifySignature } = require('../../src/entitlement');

// Produced by the PHP side (see header). Do not edit by hand.
const FIXTURE = {
  secret: 'kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk',
  timestamp: '1800000000',
  signature: 'a2661c27e66e763009681a04fb02781efb577ef78baf480698e903c95149a42e',
  body: '{"event":{"uid":"alice","clientId":42,"source":"whmcs","featureKey":"terminow-premium","active":true,"expiresAt":null,"state":"Active","type":"premium"},"idempotencyKey":"edb7594f53ce4d42e03994ab72e26151e3d1485526e200416287bc0198ba56f5","seq":1}',
};
const NOW_MS = Number(FIXTURE.timestamp) * 1000;

function fakeDb(seed = {}) {
  const store = new Map(Object.entries(seed));
  const ref = (col, id) => ({ _key: col + '/' + id });
  const snap = (key) => ({ exists: store.has(key), data: () => store.get(key) });
  return {
    store,
    collection: (col) => ({ doc: (id) => ref(col, id) }),
    async runTransaction(fn) {
      return fn({ async get(r) { return snap(r._key); }, set(r, d) { store.set(r._key, d); }, update(r, d) { store.set(r._key, { ...(store.get(r._key) || {}), ...d }); } });
    },
  };
}

test('PHP-signed delivery: the Node HMAC over the same body+secret equals the PHP signature', () => {
  const recomputed = createHmac('sha256', FIXTURE.secret).update(FIXTURE.timestamp + '.' + FIXTURE.body).digest('hex');
  assert.equal(recomputed, FIXTURE.signature, 'cross-language HMAC agreement');
  assert.doesNotThrow(() => verifySignature(FIXTURE.secret, FIXTURE.timestamp, FIXTURE.signature, FIXTURE.body, NOW_MS));
});

test('PHP-signed delivery is accepted by the writer and applied to a linked account', async () => {
  const db = fakeDb({ 'whmcsLinks/alice': { clientId: 42 } });
  const handler = makeHandler({ db, secret: FIXTURE.secret, grantsPremium: true, now: () => NOW_MS });
  const res = await handler({
    method: 'POST',
    headers: { 'x-terminow-timestamp': FIXTURE.timestamp, 'x-terminow-signature': FIXTURE.signature },
    rawBody: FIXTURE.body,
  });
  assert.equal(res.status, 200);
  assert.equal(res.body.outcome, 'applied');
  const ent = db.store.get('entitlements/alice');
  assert.equal(ent.sources.whmcs.features['terminow-premium'].active, true);
  assert.equal(ent.sources.whmcs.premium, true, 'grantsPremium on -> whmcs grants');
  assert.equal(ent.premium, true);
});

test('tampering the PHP body breaks the signature (writer rejects)', async () => {
  const db = fakeDb({ 'whmcsLinks/alice': { clientId: 42 } });
  const handler = makeHandler({ db, secret: FIXTURE.secret, now: () => NOW_MS });
  const res = await handler({
    method: 'POST',
    headers: { 'x-terminow-timestamp': FIXTURE.timestamp, 'x-terminow-signature': FIXTURE.signature },
    rawBody: FIXTURE.body.replace('"active":true', '"active":false'),
  });
  assert.equal(res.status, 401);
});
