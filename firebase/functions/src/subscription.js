'use strict';

const crypto = require('crypto');
const { TermiNowError } = require('./errors');

/**
 * What a subscription of purchases.subscriptionsv2.get means for TermiNow. Pure functions, so
 * the rules can be tested without Google.
 *
 * Access follows the Google Play guidance for subscriptions: ACTIVE and IN_GRACE_PERIOD give
 * access, CANCELED gives access until the expiry time (auto renew is off, the period was paid),
 * and ON_HOLD, PAUSED, EXPIRED, PENDING and PENDING_PURCHASE_CANCELED do not. A revoked
 * (refunded) subscription is EXPIRED. Access never goes past the verified expiry time.
 */

const ENTITLED_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
  'SUBSCRIPTION_STATE_CANCELED',
]);

/** The obfuscated account id that the app sets on a purchase: a hash of the uid, never the uid or email. */
function accountIdFor(uid) {
  return crypto.createHash('sha256').update('terminow:' + uid).digest('hex');
}

/** The key of a purchase token in Firestore: a hash, so the raw token is only in its record. */
function tokenHash(token) {
  return crypto.createHash('sha256').update(String(token)).digest('hex');
}

function isPlausibleToken(token) {
  return typeof token === 'string' && token.length >= 20 && token.length <= 2048 && /^[A-Za-z0-9._-]+$/.test(token);
}

/**
 * @param {object} sub The SubscriptionPurchaseV2 resource.
 * @param {object} productMap From the configuration.
 * @param {number} now Milliseconds.
 * @returns {object} The facts kept for the purchase.
 * @throws {TermiNowError} PRODUCT_NOT_ALLOWED for a product or base plan that is not mapped.
 */
function evaluate(sub, productMap, now) {
  const items = Array.isArray(sub && sub.lineItems) ? sub.lineItems : [];
  let match = null;
  for (const item of items) {
    const plans = productMap[item && item.productId];
    if (!plans) continue;
    const basePlanId = (item.offerDetails && item.offerDetails.basePlanId) || '';
    const plan = plans[basePlanId];
    if (!plan) continue;
    const expiry = Date.parse(item.expiryTime || '');
    if (!match || (Number.isFinite(expiry) && expiry > match.expiryTime))
      match = {
        productId: item.productId,
        basePlanId,
        tier: plan.tier,
        whmcsProductId: plan.whmcsProductId || null,
        expiryTime: Number.isFinite(expiry) ? expiry : 0,
        autoRenew: Boolean(item.autoRenewingPlan && item.autoRenewingPlan.autoRenewEnabled),
      };
  }
  if (!match) throw new TermiNowError('PRODUCT_NOT_ALLOWED', 'This purchase is not a TermiNow subscription that the server accepts');

  const state = String(sub.subscriptionState || 'SUBSCRIPTION_STATE_UNSPECIFIED');
  const ids = sub.externalAccountIdentifiers || {};
  return {
    ...match,
    state,
    entitled: ENTITLED_STATES.has(state) && match.expiryTime > now,
    pending: state === 'SUBSCRIPTION_STATE_PENDING',
    acknowledged: sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    // testPurchase is only present for license testers, and can not be set by the app
    test: Boolean(sub.testPurchase),
    accountId: typeof ids.obfuscatedExternalAccountId === 'string' ? ids.obfuscatedExternalAccountId : '',
    linkedPurchaseToken: typeof sub.linkedPurchaseToken === 'string' ? sub.linkedPurchaseToken : '',
  };
}

/** Whether a purchase must be acknowledged now: once it gives access, never while pending. */
function needsAcknowledgement(facts) {
  return !facts.acknowledged && !facts.pending && facts.entitled;
}

/**
 * The entitlement of an account from its Google Play purchases: the entitled purchase that
 * lasts longest, ignoring purchases that a plan change replaced.
 */
function entitlementFrom(purchases, now) {
  let best = null;
  let latest = null;
  for (const p of purchases) {
    if (p.supersededBy) continue;
    const entitled = ENTITLED_STATES.has(p.state) && p.expiryTime > now;
    if (entitled && (!best || p.expiryTime > best.expiryTime)) best = p;
    if (!latest || (p.lastVerifiedAt || 0) > (latest.lastVerifiedAt || 0)) latest = p;
  }
  const shown = best || latest;
  if (!shown) return { premium: false, subscriptionState: 'NONE' };
  return {
    premium: Boolean(best),
    tier: best ? best.tier : null,
    subscriptionState: shown.state,
    expiresAt: shown.expiryTime || null,
    autoRenewEnabled: Boolean(shown.autoRenew),
    lastVerifiedAt: shown.lastVerifiedAt || null,
    test: Boolean(shown.test),
    productId: shown.productId,
    basePlanId: shown.basePlanId,
  };
}

/** When a purchase should be checked with Google again, if no notification comes first. */
function nextCheckAt(facts, now) {
  const day = 24 * 60 * 60 * 1000;
  if (facts.pending) return now + 15 * 60 * 1000;
  if (facts.expiryTime > now) return Math.min(facts.expiryTime + 60 * 1000, now + day);
  // Expired: a subscription on hold can come back, check daily for 60 days
  if (now - facts.expiryTime < 60 * day) return now + day;
  return null;
}

/** Whether a purchase counts as revenue: test purchases of license testers never do. */
function isRevenue(record) {
  return !record.test && record.state !== 'SUBSCRIPTION_STATE_PENDING' && record.state !== 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED';
}

module.exports = { ENTITLED_STATES, accountIdFor, tokenHash, isPlausibleToken, evaluate, needsAcknowledgement, entitlementFrom, nextCheckAt, isRevenue };
