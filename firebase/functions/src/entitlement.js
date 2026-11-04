'use strict';

/**
 * WHMCS -> Firebase entitlement writer.
 *
 * Receives the signed WHMCS service-lifecycle events that whmcs/terminow/entitlement_lib.php delivers
 * (one per feature, per account) and reflects them into entitlements/{uid}. It:
 *
 *   - authenticates the request (HMAC-SHA256 of "<timestamp>.<rawBody>", same scheme as the other
 *     TermiNow endpoints) and rejects anything outside a 5-minute window,
 *   - verifies the WHMCS client <-> Firebase uid mapping SERVER-SIDE against the authoritative
 *     whmcsLinks/{uid} document (written only by linkWhmcsAccount); a mismatch or missing link is
 *     HELD (409), never applied, so a stale or spoofed uid can never write another account,
 *   - is idempotent on the delivery's idempotencyKey (whmcsDeliveries/{key}) and ordered per
 *     (uid, featureKey) by a monotonic seq (a late/duplicate event is a no-op),
 *   - updates ONLY sources.whmcs and recomputes top-level premium, so Google Play
 *     (sources.google_play) and the Store sandbox (sources.aivora_devnet) rights are preserved,
 *   - honours expiry and runs inside a Firestore transaction (safe under concurrent updates).
 *
 * Real Premium from WHMCS is GATED OFF by default: the whmcs source records each feature visibly but
 * flips top-level `premium` only when grantsPremium is true (WHMCS_GRANTS_PREMIUM=1) — a deliberate,
 * reviewed go-live step. Until then premium.php polling remains the compatibility path for WHMCS
 * Premium. This writer never grants from a Store sandbox event; it only ever touches sources.whmcs.
 */

const { createHmac, timingSafeEqual } = require('node:crypto');

const SIGNATURE_WINDOW_SEC = 300;
const UID = /^[A-Za-z0-9_-]{1,128}$/;
const FEATURE = /^[a-z0-9][a-z0-9-]{1,63}$/;

class WriterError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Verify the HMAC signature of a raw request body. Throws WriterError(401) on any problem. */
function verifySignature(secret, timestamp, signature, rawBody, nowMs) {
  if (typeof secret !== 'string' || secret.length < 32) throw new WriterError(503, 'NOT_CONFIGURED', 'Entitlement writer secret is not set');
  if (!/^[0-9]+$/.test(String(timestamp || '')) || Math.abs(Math.floor(nowMs / 1000) - Number(timestamp)) > SIGNATURE_WINDOW_SEC)
    throw new WriterError(401, 'BAD_SIGNATURE', 'The request is too old or has no timestamp');
  const expected = createHmac('sha256', secret).update(timestamp + '.' + rawBody).digest('hex');
  const got = String(signature || '').toLowerCase();
  if (got.length !== expected.length || !timingSafeEqual(Buffer.from(got), Buffer.from(expected)))
    throw new WriterError(401, 'BAD_SIGNATURE', 'The signature is wrong');
}

/** Validate and normalize a delivery body. Throws WriterError(400) on a malformed event. */
function parseDelivery(body) {
  if (!body || typeof body !== 'object') throw new WriterError(400, 'BAD_REQUEST', 'Body must be JSON');
  const e = body.event;
  const idempotencyKey = body.idempotencyKey;
  const seq = body.seq;
  if (!e || typeof e !== 'object') throw new WriterError(400, 'BAD_REQUEST', 'Missing event');
  if (e.source !== 'whmcs') throw new WriterError(400, 'BAD_REQUEST', 'This writer handles only the whmcs source');
  if (typeof e.uid !== 'string' || !UID.test(e.uid)) throw new WriterError(400, 'BAD_REQUEST', 'Invalid uid');
  if (!Number.isInteger(e.clientId) || e.clientId <= 0) throw new WriterError(400, 'BAD_REQUEST', 'Invalid clientId');
  if (typeof e.featureKey !== 'string' || !FEATURE.test(e.featureKey)) throw new WriterError(400, 'BAD_REQUEST', 'Invalid featureKey');
  if (typeof e.active !== 'boolean') throw new WriterError(400, 'BAD_REQUEST', 'active must be boolean');
  if (e.expiresAt != null && !Number.isInteger(e.expiresAt)) throw new WriterError(400, 'BAD_REQUEST', 'expiresAt must be ms or null');
  if (!Number.isInteger(seq) || seq <= 0) throw new WriterError(400, 'BAD_REQUEST', 'seq must be a positive integer');
  if (typeof idempotencyKey !== 'string' || idempotencyKey.length < 8 || idempotencyKey.length > 128) throw new WriterError(400, 'BAD_REQUEST', 'Invalid idempotencyKey');
  return {
    uid: e.uid,
    clientId: e.clientId,
    source: 'whmcs',
    featureKey: e.featureKey,
    active: e.active,
    expiresAt: e.expiresAt == null ? null : e.expiresAt,
    state: typeof e.state === 'string' ? e.state.slice(0, 32) : null,
    type: e.type === 'premium' ? 'premium' : 'addon',
    seq,
    idempotencyKey,
  };
}

/** Whether the event's clientId matches the authoritative Firebase-side link for the uid. */
function verifyLink(linkData, event) {
  return !!linkData && Number(linkData.clientId) === Number(event.clientId);
}

/**
 * Pure merge: fold one WHMCS event into an entitlement document. Returns { outcome, entitlement }.
 * outcome is 'applied' (the doc changed) or 'stale' (an older/duplicate seq — the doc is unchanged).
 * Only sources.whmcs is touched; other sources and the top-level premium are recomputed, never cleared.
 */
function applyWhmcsEvent({ entitlement, event, now, grantsPremium }) {
  const current = entitlement && typeof entitlement === 'object' ? entitlement : {};
  const sources = { ...(current.sources || {}) };
  const whmcs = sources.whmcs && typeof sources.whmcs === 'object' ? sources.whmcs : { source: 'whmcs', premium: false, features: {} };
  const features = { ...(whmcs.features || {}) };
  const prev = features[event.featureKey];
  if (prev && Number(event.seq) <= Number(prev.seq)) return { outcome: 'stale', entitlement: current };

  const tier = event.type === 'premium' ? 'premium' : 'addon';
  const active = !!event.active && (event.expiresAt == null || Number(event.expiresAt) > now);
  features[event.featureKey] = { active, tier, expiresAt: event.expiresAt == null ? null : Number(event.expiresAt), state: event.state || null, seq: Number(event.seq), updatedAt: now };

  // Only a premium-tier, active feature can grant Premium, and only when real grants are enabled.
  const whmcsPremium = !!grantsPremium && Object.values(features).some((f) => f && f.active && f.tier === 'premium');
  sources.whmcs = { source: 'whmcs', premium: whmcsPremium, features, updatedAt: now };
  const premium = Object.values(sources).some((s) => s && s.premium === true);
  return { outcome: 'applied', entitlement: { ...current, premium, sources, updatedAt: now } };
}

/**
 * Build the transactional HTTP handler. `db` is a Firestore-like store with runTransaction + collection.
 * Returns async ({ method, headers, rawBody }) => { status, body }.
 */
function makeHandler({ db, secret, grantsPremium = false, now = () => Date.now(), windowSec = SIGNATURE_WINDOW_SEC }) {
  void windowSec;
  return async ({ method, headers, rawBody }) => {
    if (method !== 'POST') return { status: 405, body: { ok: false, code: 'BAD_REQUEST', error: 'POST only' } };
    const nowMs = now();
    try {
      verifySignature(secret, headers['x-terminow-timestamp'], headers['x-terminow-signature'], rawBody, nowMs);
    } catch (e) {
      return { status: e.status || 401, body: { ok: false, code: e.code || 'BAD_SIGNATURE', error: e.message } };
    }
    let event;
    try {
      event = parseDelivery(JSON.parse(rawBody));
    } catch (e) {
      return { status: e instanceof WriterError ? e.status : 400, body: { ok: false, code: e instanceof WriterError ? e.code : 'BAD_REQUEST', error: 'Invalid delivery' } };
    }

    return db.runTransaction(async (tx) => {
      const delRef = db.collection('whmcsDeliveries').doc(event.idempotencyKey);
      const delSnap = await tx.get(delRef);
      if (delSnap.exists) return { status: 200, body: { ok: true, duplicate: true } };

      const linkSnap = await tx.get(db.collection('whmcsLinks').doc(event.uid));
      if (!verifyLink(linkSnap.exists ? linkSnap.data() : null, event))
        // Held (not failed): the uid is not yet linked to this client on the Firebase side. The WHMCS
        // outbox retries; no delivery record is written so a later attempt reprocesses cleanly.
        return { status: 409, body: { ok: false, held: true, code: 'HELD', reason: 'no_verified_link' } };

      const entRef = db.collection('entitlements').doc(event.uid);
      const entSnap = await tx.get(entRef);
      const current = entSnap.exists ? entSnap.data() : { premium: false };
      const { outcome, entitlement } = applyWhmcsEvent({ entitlement: current, event, now: nowMs, grantsPremium });

      // Record the delivery (applied or stale) so a resend is a no-op; only write the doc when applied.
      tx.set(delRef, { uid: event.uid, clientId: event.clientId, featureKey: event.featureKey, seq: event.seq, outcome, at: nowMs });
      if (outcome === 'applied') tx.set(entRef, entitlement);
      return { status: 200, body: { ok: true, outcome } };
    });
  };
}

module.exports = { WriterError, verifySignature, parseDelivery, verifyLink, applyWhmcsEvent, makeHandler, SIGNATURE_WINDOW_SEC };
