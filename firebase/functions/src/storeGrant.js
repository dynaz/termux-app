'use strict';

const { TermiNowError } = require('./errors');

/**
 * The TermiNow Store (store.terminow.online) delivers verified purchases to the backend as entitlement
 * grants, keyed on the account's TermiNow identity (the Firebase UID) — never on an email. This module
 * is the pure logic that merges a Store grant into an account's entitlements/{uid} document, the same
 * multi-source document the Google Play flow writes (see service.js): {premium, sources, updatedAt},
 * where premium is true when ANY source grants it. The Store is one more source, kept apart from
 * google_play, so neither ever clobbers the other.
 *
 * SAFETY: a Store grant only sets its source's `premium` when the caller passes allowPremium === true.
 * For the Devnet sandbox (grant.sandbox === true) real Premium is kept DISABLED: the source is recorded
 * and visible, but it never flips the account to premium. Turning real grants on is a reviewed go-live
 * step on the Store (AIVORA_REAL_GRANTS=1) and the backend (STORE_GRANTS_PREMIUM=1) together.
 */

// Store feature keys that confer the Premium tier (vs. à-la-carte add-ons that do not).
const PREMIUM_FEATURES = new Set(['terminow-premium']);

function activeAt(feature, now) {
  if (!feature || feature.revoked) return false;
  return feature.expiresAt == null || feature.expiresAt > now;
}

/**
 * Validate and normalize a delivery body. Throws TermiNowError('INVALID_GRANT', …) on anything off.
 * @returns {{eventId,terminowRef,featureKey,kind,expires,source,sandbox}}
 */
function validateGrant(body) {
  const b = body && typeof body === 'object' ? body : {};
  const bad = (m) => { throw new TermiNowError('INVALID_GRANT', m); };
  if (typeof b.eventId !== 'string' || !/^[A-Za-z0-9:_.-]{1,200}$/.test(b.eventId)) bad('eventId');
  if (typeof b.terminowRef !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(b.terminowRef)) bad('terminowRef');
  if (typeof b.featureKey !== 'string' || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(b.featureKey)) bad('featureKey');
  if (b.kind !== 'grant' && b.kind !== 'revoke') bad('kind');
  if (!(b.expires == null || (Number.isInteger(b.expires) && b.expires > 0))) bad('expires');
  const source = typeof b.source === 'string' && /^[a-z0-9][a-z0-9_]{0,39}$/.test(b.source) ? b.source : 'aivora_devnet';
  return { eventId: b.eventId, terminowRef: b.terminowRef, featureKey: b.featureKey, kind: b.kind, expires: b.expires == null ? null : b.expires, source, sandbox: b.sandbox === true };
}

/**
 * Merge one Store grant into an entitlements/{uid} document.
 * @param {object|null} entitlement The existing document data, or null/empty for a new account.
 * @param {object} grant A validated grant (see validateGrant).
 * @param {{now?:number, allowPremium?:boolean}} opts
 * @returns {object} The new document data to store: {premium, sources, updatedAt}.
 */
function applyStoreSource(entitlement, grant, { now = Date.now(), allowPremium = false } = {}) {
  const ent = entitlement && typeof entitlement === 'object' ? entitlement : {};
  const sources = { ...(ent.sources || {}) };
  const prev = sources[grant.source] && typeof sources[grant.source] === 'object' ? sources[grant.source] : { source: grant.source, features: {} };
  const features = { ...(prev.features || {}) };

  if (grant.kind === 'revoke') {
    if (features[grant.featureKey]) features[grant.featureKey] = { ...features[grant.featureKey], revoked: true, updatedAt: now };
  } else {
    features[grant.featureKey] = {
      tier: PREMIUM_FEATURES.has(grant.featureKey) ? 'premium' : 'addon',
      expiresAt: grant.expires == null ? null : grant.expires,
      sandbox: grant.sandbox === true,
      revoked: false,
      updatedAt: now,
    };
  }

  // The Store source grants Premium only when real grants are allowed AND a premium-tier feature is
  // currently active. In the sandbox (allowPremium false) this is always false, so real Premium is never
  // flipped — exactly the disabled posture the project requires.
  const premiumActive = Object.entries(features).some(([key, f]) => PREMIUM_FEATURES.has(key) && activeAt(f, now));
  const sourcePremium = allowPremium === true && premiumActive;
  sources[grant.source] = { source: grant.source, sandbox: grant.sandbox === true, premium: sourcePremium, features, updatedAt: now };

  const premium = Object.values(sources).some((s) => s && s.premium === true);
  return { ...ent, premium, sources, updatedAt: now };
}

module.exports = { PREMIUM_FEATURES, activeAt, validateGrant, applyStoreSource };
