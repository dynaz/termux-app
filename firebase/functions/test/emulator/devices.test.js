'use strict';

// Runs against the Firestore emulator: npm test (firebase emulators:exec)
const test = require('node:test');
const assert = require('node:assert');
const { initializeApp, getApps } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { Devices, MAX_DEVICES } = require('../../src/devices');

const PROJECT = 'demo-terminow';
if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run with the Firestore emulator: npm test');
if (getApps().length === 0) initializeApp({ projectId: PROJECT });
const db = getFirestore();

const A = 'device-aaaaaaaaaaaaaaaa';
const B = 'device-bbbbbbbbbbbbbbbb';
const INSTALL_A = 'install-aaaaaaaaaaaaaa';
const INSTALL_B = 'install-bbbbbbbbbbbbbb';

async function clear() {
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: 'DELETE' });
}

function setup() {
  const clock = { now: Date.UTC(2026, 9, 17) };
  const revoked = [];
  const devices = new Devices({ db, auth: { revokeRefreshTokens: async (uid) => { revoked.push(uid); } }, now: () => clock.now });
  return { clock, revoked, devices };
}

test.beforeEach(clear);

test('devices register, list with this one first, and update their last activity', async () => {
  const { clock, devices } = setup();
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Samsung SM-F946B', kind: 'phone', appVersion: '0.118.0' });
  clock.now += 1000;
  await devices.handle('alice', { action: 'register', deviceId: B, installId: INSTALL_B, name: 'Galaxy Tab', kind: 'tablet' });
  clock.now += 1000;
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Samsung SM-F946B', kind: 'phone' });
  const { devices: list } = await devices.handle('alice', { action: 'list', deviceId: B });
  assert.deepStrictEqual(list.map((d) => [d.id, d.current]), [[B, true], [A, false]]);
  assert.strictEqual(list[1].lastActive, clock.now);
  assert.strictEqual(list[1].created, clock.now - 2000);
  // Another account sees none of them
  assert.deepStrictEqual((await devices.handle('bob', { action: 'list' })).devices, []);
});

test('a new sign in of the same app replaces its old entry', async () => {
  const { devices } = setup();
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Phone' });
  await devices.handle('alice', { action: 'register', deviceId: B, installId: INSTALL_A, name: 'Phone' });
  const { devices: list } = await devices.handle('alice', { action: 'list' });
  assert.deepStrictEqual(list.map((d) => d.id), [B]);
});

test('a device signed out from another one is told so and is no longer listed', async () => {
  const { devices } = setup();
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Phone' });
  await devices.handle('alice', { action: 'register', deviceId: B, installId: INSTALL_B, name: 'Tablet' });
  await devices.handle('alice', { action: 'signOut', deviceId: B });
  assert.deepStrictEqual((await devices.handle('alice', { action: 'register', deviceId: B, installId: INSTALL_B, name: 'Tablet' })), { signedOut: true });
  assert.deepStrictEqual((await devices.handle('alice', { action: 'list' })).devices.map((d) => d.id), [A]);
  // Signing in again on it makes a new device id, which works
  const C = 'device-cccccccccccccccc';
  assert.deepStrictEqual(await devices.handle('alice', { action: 'register', deviceId: C, installId: INSTALL_B, name: 'Tablet' }), { signedOut: false });
  assert.deepStrictEqual((await devices.handle('alice', { action: 'list' })).devices.map((d) => d.id).sort(), [A, C].sort());
});

test('signing out everywhere revokes the tokens and marks every device', async () => {
  const { devices, revoked } = setup();
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Phone' });
  await devices.handle('alice', { action: 'register', deviceId: B, installId: INSTALL_B, name: 'Tablet' });
  await devices.handle('bob', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Phone of Bob' });
  assert.deepStrictEqual(await devices.handle('alice', { action: 'signOutEverywhere' }), { signedOut: 2 });
  assert.deepStrictEqual(revoked, ['alice']);
  assert.deepStrictEqual((await devices.handle('alice', { action: 'list' })).devices, []);
  assert.strictEqual((await devices.handle('bob', { action: 'list' })).devices.length, 1);
});

test('forget removes the entry of this device', async () => {
  const { devices } = setup();
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'Phone' });
  await devices.handle('alice', { action: 'forget', deviceId: A });
  assert.deepStrictEqual((await devices.handle('alice', { action: 'list' })).devices, []);
});

test('invalid input is refused, names are one line and limited', async () => {
  const { devices } = setup();
  await assert.rejects(devices.handle('alice', { action: 'register', deviceId: 'short', installId: INSTALL_A }), { code: 'INVALID_DEVICE' });
  await assert.rejects(devices.handle('alice', { action: 'register', deviceId: '../../entitlements/x', installId: INSTALL_A }), { code: 'INVALID_DEVICE' });
  await assert.rejects(devices.handle('alice', { action: 'drop' }), { code: 'INVALID_ACTION' });
  await devices.handle('alice', { action: 'register', deviceId: A, installId: INSTALL_A, name: 'a\nb'.padEnd(200, 'x'), kind: 'toaster' });
  const [device] = (await devices.handle('alice', { action: 'list' })).devices;
  assert.strictEqual(device.name.length, 80);
  assert.ok(!device.name.includes('\n'));
  assert.strictEqual(device.kind, 'other');
});

test('at most MAX_DEVICES are kept, the oldest go first', async () => {
  const { clock, devices } = setup();
  for (let i = 0; i < MAX_DEVICES + 2; i++) {
    clock.now += 1000;
    const n = String(i).padStart(4, '0');
    await devices.handle('alice', { action: 'register', deviceId: 'device-' + n + 'aaaaaaaaaaaa', installId: 'install-' + n + 'aaaaaaaaaa', name: 'D' + i });
  }
  const { devices: list } = await devices.handle('alice', { action: 'list' });
  assert.strictEqual(list.length, MAX_DEVICES);
  assert.ok(!list.some((d) => d.name === 'D0' || d.name === 'D1'));
});
