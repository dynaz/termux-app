'use strict';

/**
 * Play Integrity, observed only: the app sends a token of the standard API bound to the account
 * and a nonce, and this records what Google Play said about it in
 * integrityObservations/{uid}/events. Nothing here grants, refuses or takes away Premium, and no
 * answer to the app depends on a verdict: there is no policy until there is enough data for one.
 * A rooted phone, developer options or Ubuntu are recorded like any other device, never blocked.
 *
 * The token itself is never kept or logged, only the verdict fields and what the checks found.
 */

const crypto = require('crypto');
const { GoogleAuth } = require('google-auth-library');
const { TermiNowError } = require('./errors');

const API = 'https://playintegrity.googleapis.com/v1/';
const NONCE = /^[0-9a-f]{32}$/;
const ERROR = /^[A-Z_]{1,40}$/;
/** How old a token may be, and how far ahead of the server clock. */
const MAX_AGE_MS = 5 * 60 * 1000;
const MAX_AHEAD_MS = 60 * 1000;
/** A nonce is remembered this long, longer than a token can be accepted. */
const NONCE_TTL_MS = 24 * 60 * 60 * 1000;

/** The request hash of the app: sha256 hex of "terminow-entitlement:<uid>:<nonce>". */
function requestHash(uid, nonce) {
  return crypto.createHash('sha256').update('terminow-entitlement:' + uid + ':' + nonce, 'utf8').digest('hex');
}

/** decodeIntegrityToken with the identity of the runtime service account of the functions. */
class IntegrityClient {
  constructor(packageName, auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/playintegrity'] })) {
    this.packageName = packageName;
    this.auth = auth;
  }

  async decode(token) {
    let client;
    try {
      client = await this.auth.getClient();
    } catch (e) {
      throw new TermiNowError('INTEGRITY_AUTH', 'The server could not sign in to Play Integrity', true);
    }
    let response;
    try {
      response = await client.request({
        url: API + encodeURIComponent(this.packageName) + ':decodeIntegrityToken',
        method: 'POST',
        data: { integrityToken: token },
        validateStatus: () => true,
        timeout: 15000,
      });
    } catch (e) {
      throw new TermiNowError('INTEGRITY_UNAVAILABLE', 'Play Integrity could not be reached', true);
    }
    if (response.status >= 200 && response.status < 300) return (response.data || {}).tokenPayloadExternal || {};
    if (response.status === 400) throw new TermiNowError('INTEGRITY_INVALID_TOKEN', 'Play Integrity refused the token');
    if (response.status === 401 || response.status === 403)
      throw new TermiNowError('INTEGRITY_PERMISSION', 'The Play Integrity API is not enabled or linked for TermiNow', true);
    throw new TermiNowError('INTEGRITY_UNAVAILABLE', `Play Integrity answered with HTTP ${response.status}`, true);
  }
}

/** What the checks of the server found in a decoded payload, for one account and nonce. */
function check(payload, { uid, nonce, packageName, now }) {
  const request = payload.requestDetails || {};
  const app = payload.appIntegrity || {};
  const timestamp = Number(request.timestampMillis);
  const age = Number.isFinite(timestamp) ? now - timestamp : null;
  return {
    requestHashMatches: request.requestHash === requestHash(uid, nonce),
    packageMatches: request.requestPackageName === packageName && (!app.packageName || app.packageName === packageName),
    fresh: age !== null && age <= MAX_AGE_MS && age >= -MAX_AHEAD_MS,
    ageMs: age,
  };
}

/** Only the verdict fields, so nothing that identifies the device more than needed is kept. */
function verdicts(payload) {
  const app = payload.appIntegrity || {};
  const device = payload.deviceIntegrity || {};
  const account = payload.accountDetails || {};
  return {
    app: app.appRecognitionVerdict || null,
    versionCode: app.versionCode != null ? String(app.versionCode) : null,
    device: Array.isArray(device.deviceRecognitionVerdict) ? device.deviceRecognitionVerdict.slice(0, 5) : [],
    licensing: account.appLicensingVerdict || null,
  };
}

class IntegrityObserver {
  /** @param {{db: FirebaseFirestore.Firestore, client: {decode(token: string): Promise<object>}, packageName: string, now?: () => number, log?: object}} deps */
  constructor({ db, client, packageName, now, log }) {
    this.db = db;
    this.client = client;
    this.packageName = packageName;
    this.now = now || (() => Date.now());
    this.log = log;
  }

  /**
   * Record one request of the app. Answers {signed: null}: no entitlement is signed yet, which
   * the app takes as before, so a failure here never changes Premium.
   */
  async observe(uid, data) {
    const nonce = data && data.nonce;
    if (typeof nonce !== 'string' || !NONCE.test(nonce)) throw new TermiNowError('INVALID_NONCE', 'Invalid request');
    const now = this.now();
    const event = {
      at: now,
      distribution: data.distribution === 'play' ? 'play' : 'full',
      appError: typeof data.integrityError === 'string' && ERROR.test(data.integrityError) ? data.integrityError : null,
    };
    // Each nonce once, so a recorded token can not be sent again as a new observation
    const nonceRef = this.db.collection('integrityNonces').doc(uid + '_' + nonce);
    try {
      await nonceRef.create({ uid, at: now, expireAt: new Date(now + NONCE_TTL_MS) });
    } catch (e) {
      if (e && (e.code === 6 || e.code === 'already-exists')) {
        await this.record(uid, { ...event, result: 'REPLAYED_NONCE' });
        return { signed: null };
      }
      throw e;
    }
    const token = data.integrityToken;
    if (typeof token !== 'string' || token.length === 0 || token.length > 8192) {
      await this.record(uid, { ...event, result: event.appError ? 'NO_TOKEN' : 'MISSING_TOKEN' });
      return { signed: null };
    }
    let payload;
    try {
      payload = await this.client.decode(token);
    } catch (e) {
      await this.record(uid, { ...event, result: e instanceof TermiNowError ? e.code : 'DECODE_FAILED' });
      return { signed: null };
    }
    const checks = check(payload, { uid, nonce, packageName: this.packageName, now });
    const ok = checks.requestHashMatches && checks.packageMatches && checks.fresh;
    await this.record(uid, { ...event, result: ok ? 'DECODED' : 'CHECK_FAILED', checks, verdicts: verdicts(payload) });
    return { signed: null };
  }

  async record(uid, event) {
    try {
      await this.db.collection('integrityObservations').doc(uid).collection('events').add(event);
    } catch (e) {
      // An observation that is lost changes nothing for the user
      if (this.log) this.log.warn('Integrity observation not recorded: ' + (e && e.code));
    }
  }
}

module.exports = { requestHash, check, verdicts, IntegrityClient, IntegrityObserver, MAX_AGE_MS };
