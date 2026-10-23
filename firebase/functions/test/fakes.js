'use strict';

const { TermiNowError } = require('../src/errors');
const { accountIdFor } = require('../src/subscription');

const DAY = 24 * 60 * 60 * 1000;
const PRODUCT_MAP = { terminow_premium: { monthly: { tier: 'premium', whmcsProductId: 7 }, yearly: { tier: 'premium', whmcsProductId: 7 } } };

/** A SubscriptionPurchaseV2 like Google Play returns it. */
function subscription({ uid = 'alice', state = 'SUBSCRIPTION_STATE_ACTIVE', expiry, basePlanId = 'monthly', productId = 'terminow_premium',
  acknowledged = false, autoRenew = true, test = false, linked = null, accountId } = {}) {
  const resource = {
    kind: 'androidpublisher#subscriptionPurchaseV2',
    subscriptionState: state,
    acknowledgementState: acknowledged ? 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' : 'ACKNOWLEDGEMENT_STATE_PENDING',
    externalAccountIdentifiers: { obfuscatedExternalAccountId: accountId !== undefined ? accountId : accountIdFor(uid) },
    lineItems: [{
      productId,
      expiryTime: new Date(expiry).toISOString(),
      autoRenewingPlan: { autoRenewEnabled: autoRenew },
      offerDetails: { basePlanId },
    }],
  };
  if (test) resource.testPurchase = {};
  if (linked) resource.linkedPurchaseToken = linked;
  return resource;
}

/** Google Play with purchases set by the test, and acknowledgements that can fail. */
class FakePlay {
  constructor() {
    this.purchases = new Map();
    this.acknowledged = [];
    this.ackFailures = 0;
    this.down = false;
    this.calls = 0;
  }

  set(token, resource) {
    this.purchases.set(token, resource);
  }

  async getSubscription(token) {
    this.calls++;
    if (this.down) throw new TermiNowError('PLAY_UNAVAILABLE', 'Google Play could not be reached', true);
    const resource = this.purchases.get(token);
    if (!resource) throw new TermiNowError('PURCHASE_NOT_FOUND', 'Google Play does not know this purchase for TermiNow');
    return JSON.parse(JSON.stringify(resource));
  }

  async acknowledge(productId, token) {
    if (this.ackFailures > 0) {
      this.ackFailures--;
      throw new TermiNowError('PLAY_UNAVAILABLE', 'Google Play could not be reached', true);
    }
    this.acknowledged.push(token);
    const resource = this.purchases.get(token);
    if (resource) resource.acknowledgementState = 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
  }
}

/** WHMCS that records syncs, and can be down. */
class FakeWhmcs {
  constructor() {
    this.syncs = [];
    this.down = false;
    this.services = new Map();
  }

  async sync(state) {
    if (this.down) throw new TermiNowError('WHMCS_UNAVAILABLE', 'WHMCS could not be reached', true);
    this.syncs.push(state);
    // Like play_sync.php: one service per uid, whatever the number of retries
    if (!this.services.has(state.uid)) this.services.set(state.uid, 100 + this.services.size);
    return { clientId: 1, serviceId: this.services.get(state.uid), status: state.premium ? 'Active' : 'Cancelled' };
  }

  async redeemLink({ code }) {
    if (code !== 'ABCD2345') throw new TermiNowError('INVALID_LINK_CODE', 'The code is wrong or expired');
    return { clientId: 42 };
  }
}

const silentLog = { info() {}, warn() {}, error() {} };

module.exports = { DAY, PRODUCT_MAP, subscription, FakePlay, FakeWhmcs, silentLog };
