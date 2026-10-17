'use strict';

// The security rules, against the Firestore emulator: npm test
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, updateDoc, collection, getDocs } = require('firebase/firestore');

let env;

test.before(async () => {
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
  env = await initializeTestEnvironment({
    projectId: 'demo-terminow-rules',
    firestore: { host, port: Number(port), rules: fs.readFileSync(path.join(__dirname, '../../../firestore.rules'), 'utf8') },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, 'entitlements/alice'), { premium: true, sources: { google_play: { premium: true } } });
    await setDoc(doc(db, 'entitlements/bob'), { premium: false });
    await setDoc(doc(db, 'playPurchases/h1'), { uid: 'alice', purchaseToken: 'secret-token' });
    await setDoc(doc(db, 'whmcsOutbox/alice'), { uid: 'alice' });
  });
});

test.after(async () => {
  await env.cleanup();
});

test('users read only their own entitlement', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(getDoc(doc(alice, 'entitlements/alice')));
  await assertFails(getDoc(doc(alice, 'entitlements/bob')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'entitlements/alice')));
});

test('clients can not write Premium, expiry or state', async () => {
  const bob = env.authenticatedContext('bob').firestore();
  await assertFails(setDoc(doc(bob, 'entitlements/bob'), { premium: true }));
  await assertFails(updateDoc(doc(bob, 'entitlements/bob'), { 'sources.google_play.expiresAt': 9999999999999 }));
  await assertFails(setDoc(doc(bob, 'entitlements/alice'), { premium: false }));
});

test('backend collections are closed to clients', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertFails(getDoc(doc(alice, 'playPurchases/h1')));
  await assertFails(getDocs(collection(alice, 'playPurchases')));
  await assertFails(setDoc(doc(alice, 'playPurchases/h2'), { uid: 'alice', state: 'SUBSCRIPTION_STATE_ACTIVE' }));
  await assertFails(getDoc(doc(alice, 'whmcsOutbox/alice')));
  await assertFails(setDoc(doc(alice, 'playAccounts/x'), { uid: 'alice' }));
  await assertFails(setDoc(doc(alice, 'whmcsLinks/alice'), { clientId: 1 }));
  // The devices of an account are only changed by accountDevices, which checks the caller
  await assertFails(getDocs(collection(alice, 'devices/alice/sessions')));
  await assertFails(setDoc(doc(alice, 'devices/alice/sessions/aaaaaaaaaaaaaaaa'), { signedOut: false }));
});
