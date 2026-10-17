'use strict';

/**
 * The devices that are signed in to a TermiNow account, like the Devices page of Termius:
 * devices/{uid}/sessions/{deviceId}, written only here with the Admin SDK.
 *
 * A device id is made by the app at each sign in; the install id stays the same for the app on
 * a device, so that signing in again replaces the old entry of that device. "Sign out" of one
 * device marks it: that device signs itself out the next time it registers, which it does when
 * the app starts or comes back. "Sign out everywhere" also revokes every refresh token of the
 * account, which Firebase enforces for all devices, this one included.
 */

const { TermiNowError } = require('./errors');

const MAX_DEVICES = 50;
const ID = /^[A-Za-z0-9_-]{16,64}$/;
const KINDS = ['phone', 'tablet', 'desktop', 'other'];

function text(value, max) {
  if (typeof value !== 'string') return '';
  // No control characters, so a name is always one line
  return value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max);
}

function requireId(value, what) {
  if (typeof value !== 'string' || !ID.test(value)) throw new TermiNowError('INVALID_DEVICE', 'Invalid ' + what);
  return value;
}

class Devices {
  /** @param {{db: FirebaseFirestore.Firestore, auth: {revokeRefreshTokens(uid: string): Promise<void>}, now?: () => number}} deps */
  constructor({ db, auth, now }) {
    this.db = db;
    this.auth = auth;
    this.now = now || (() => Date.now());
  }

  sessions(uid) {
    return this.db.collection('devices').doc(uid).collection('sessions');
  }

  /**
   * Record that a device is signed in and active now. Answers {signedOut: true} for a device
   * that was signed out from another one, which then signs itself out.
   */
  async register(uid, data) {
    const deviceId = requireId(data && data.deviceId, 'device id');
    const installId = requireId(data && data.installId, 'install id');
    const name = text(data.name, 80) || 'Android device';
    const kind = KINDS.includes(data.kind) ? data.kind : 'other';
    const appVersion = text(data.appVersion, 40);
    const ref = this.sessions(uid).doc(deviceId);
    const now = this.now();
    const signedOut = await this.db.runTransaction(async (tx) => {
      const snapshot = await tx.get(ref);
      if (snapshot.exists && snapshot.get('signedOut')) return true;
      tx.set(ref, { name, kind, installId, appVersion, lastActive: now, created: snapshot.exists ? snapshot.get('created') : now, signedOut: false });
      return false;
    });
    if (signedOut) return { signedOut: true };

    // An older sign in of the same app, and the oldest devices beyond the limit, are removed
    const all = await this.sessions(uid).get();
    const others = all.docs.filter((d) => d.id !== deviceId);
    const batch = this.db.batch();
    let changed = false;
    for (const d of others) {
      if (d.get('installId') === installId) {
        batch.delete(d.ref);
        changed = true;
      }
    }
    const kept = others.filter((d) => d.get('installId') !== installId).sort((a, b) => (b.get('lastActive') || 0) - (a.get('lastActive') || 0));
    for (const d of kept.slice(MAX_DEVICES - 1)) {
      batch.delete(d.ref);
      changed = true;
    }
    if (changed) await batch.commit();
    return { signedOut: false };
  }

  /** The signed in devices, the most recently active first. */
  async list(uid, data) {
    const current = data && typeof data.deviceId === 'string' ? data.deviceId : '';
    const all = await this.sessions(uid).get();
    const devices = all.docs.filter((d) => !d.get('signedOut')).map((d) => ({
      id: d.id,
      name: d.get('name') || '',
      kind: d.get('kind') || 'other',
      appVersion: d.get('appVersion') || '',
      lastActive: d.get('lastActive') || 0,
      created: d.get('created') || 0,
      current: d.id === current,
    }));
    devices.sort((a, b) => (b.current - a.current) || (b.lastActive - a.lastActive));
    return { devices };
  }

  /** Sign out one device: it signs itself out when it registers next. */
  async signOut(uid, data) {
    const deviceId = requireId(data && data.deviceId, 'device id');
    const ref = this.sessions(uid).doc(deviceId);
    const snapshot = await ref.get();
    if (!snapshot.exists) return { signedOut: true };
    await ref.update({ signedOut: true, signedOutAt: this.now() });
    return { signedOut: true };
  }

  /** Remove the entry of this device, when it signs out by itself. */
  async forget(uid, data) {
    const deviceId = requireId(data && data.deviceId, 'device id');
    await this.sessions(uid).doc(deviceId).delete();
    return { forgotten: true };
  }

  /** Revoke every session of the account, this device included, and mark all devices signed out. */
  async signOutEverywhere(uid) {
    await this.auth.revokeRefreshTokens(uid);
    const all = await this.sessions(uid).get();
    const batch = this.db.batch();
    const now = this.now();
    for (const d of all.docs) batch.update(d.ref, { signedOut: true, signedOutAt: now });
    if (!all.empty) await batch.commit();
    return { signedOut: all.size };
  }

  async handle(uid, data) {
    switch (data && data.action) {
      case 'register': return this.register(uid, data);
      case 'list': return this.list(uid, data);
      case 'signOut': return this.signOut(uid, data);
      case 'forget': return this.forget(uid, data);
      case 'signOutEverywhere': return this.signOutEverywhere(uid);
      default: throw new TermiNowError('INVALID_ACTION', 'Unknown action');
    }
  }
}

module.exports = { Devices, MAX_DEVICES };
