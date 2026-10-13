'use strict';

const { FieldValue } = require('firebase-admin/firestore');
const { TermiNowError, redact } = require('./errors');
const sub = require('./subscription');

/**
 * The Google Play subscriptions of TermiNow accounts, kept in Firestore:
 *
 * - playPurchases/{sha256(token)}: backend only. The raw token, its owner (uid, or null while
 *   the owner is unknown), the facts of the last verified Google Play state and the
 *   acknowledgement state.
 * - playAccounts/{obfuscated account id}: backend only. The uid that a purchase made in the
 *   app belongs to; the app sets sha256("terminow:" + uid) as the obfuscated account id.
 * - entitlements/{uid}: what the user may read. {premium, sources: {google_play: summary}}.
 * - whmcsOutbox/{uid}: backend only. The latest state for WHMCS, sent asynchronously.
 *
 * The owner of a purchase is decided once, in a transaction, and never changes, so one token
 * can not give Premium to two accounts. Every state comes from Google Play, never from the app.
 */
class Service {
  /**
   * @param {object} deps {db, play, config, whmcs, now(), log}
   */
  constructor({ db, play, config, whmcs = null, now = Date.now, log = console }) {
    this.db = db;
    this.play = play;
    this.config = config;
    this.whmcs = whmcs;
    this.now = now;
    this.log = log;
  }

  purchaseRef(hash) {
    return this.db.collection('playPurchases').doc(hash);
  }

  // ---------------------------------------------------------------------------------------
  // Verification from the app
  // ---------------------------------------------------------------------------------------

  /**
   * Verify a purchase that the signed-in user made in the app, record it for them and
   * acknowledge it. The uid and email come from the verified Firebase ID token.
   */
  async verifyForUser(uid, email, token) {
    if (!uid) throw new TermiNowError('SIGN_IN_REQUIRED', 'Sign in to TermiNow first');
    if (!sub.isPlausibleToken(token)) throw new TermiNowError('INVALID_TOKEN', 'This is not a Google Play purchase token');
    const fetchedAt = this.now();
    const facts = sub.evaluate(await this.play.getSubscription(token), this.config.productMap, fetchedAt);
    // The app sets the hash of the uid as the account of the purchase. A purchase without it, or
    // with the one of another account, is not this user's, even if they know its token.
    if (facts.accountId !== sub.accountIdFor(uid))
      throw new TermiNowError('PURCHASE_OF_ANOTHER_ACCOUNT', 'This Google Play purchase belongs to another TermiNow account');

    const record = await this.apply(token, facts, fetchedAt, { claimUid: uid, email });
    await this.acknowledgeIfNeeded(token, record);
    return this.resultFor(uid, record);
  }

  /** What the app is told: the state of this purchase and the entitlement of the account. */
  async resultFor(uid, record) {
    const entitlement = (await this.db.collection('entitlements').doc(uid).get()).data() || { premium: false };
    let status;
    if (record.state === 'SUBSCRIPTION_STATE_PENDING') status = 'PENDING';
    else if (record.supersededBy) status = 'REPLACED';
    else if (sub.ENTITLED_STATES.has(record.state) && record.expiryTime > this.now()) status = 'ACTIVE';
    else if (record.state === 'SUBSCRIPTION_STATE_ON_HOLD') status = 'ON_HOLD';
    else if (record.state === 'SUBSCRIPTION_STATE_PAUSED') status = 'PAUSED';
    else status = 'EXPIRED';
    return {
      status,
      test: Boolean(record.test),
      acknowledged: record.ackState !== 'pending',
      entitlement: publicEntitlement(entitlement),
    };
  }

  // ---------------------------------------------------------------------------------------
  // Notifications and reconciliation
  // ---------------------------------------------------------------------------------------

  /**
   * Check a purchase with Google Play again and record its current state. Used for
   * notifications, which may be duplicated, delayed or out of order: the state always comes
   * from Google, never from the notification.
   */
  async refresh(token) {
    const hash = sub.tokenHash(token);
    const fetchedAt = this.now();
    let facts;
    try {
      facts = sub.evaluate(await this.play.getSubscription(token), this.config.productMap, fetchedAt);
    } catch (e) {
      await this.recordCheckFailure(hash, e);
      throw e;
    }
    const record = await this.apply(token, facts, fetchedAt, {});
    await this.acknowledgeIfNeeded(token, record);
    return record;
  }

  /** Handle one Real-time Developer Notification, the decoded JSON of the Pub/Sub message. */
  async handleNotification(message, messageId) {
    if (!message || message.packageName !== this.config.packageName) {
      this.log.warn('RTDN for another package ignored');
      return { handled: 'ignored' };
    }
    if (message.testNotification) {
      this.log.info('RTDN test notification received');
      return { handled: 'test' };
    }
    const notification = message.subscriptionNotification || message.voidedPurchaseNotification;
    if (!notification || !notification.purchaseToken) return { handled: 'ignored' };
    // A voided one-time product is not a subscription of TermiNow
    if (message.voidedPurchaseNotification && message.voidedPurchaseNotification.productType !== 1) return { handled: 'ignored' };

    // Pub/Sub may deliver a message more than once
    if (messageId) {
      try {
        await this.db.collection('playNotifications').doc(String(messageId)).create({
          receivedAt: this.now(),
          type: message.subscriptionNotification ? message.subscriptionNotification.notificationType : 'voided',
          tokenHash: sub.tokenHash(notification.purchaseToken),
        });
      } catch (e) {
        if (e.code === 6 || /already exists/i.test(String(e.message))) return { handled: 'duplicate' };
        throw e;
      }
    }
    const record = await this.refresh(notification.purchaseToken);
    return { handled: 'refreshed', owner: record.uid ? 'known' : 'unknown' };
  }

  /**
   * Check again what is due: purchases at their expiry, after a failure or not checked for a
   * day, acknowledgements that failed, and purchases whose owner is not known yet.
   */
  async reconcile(limit = 200) {
    const now = this.now();
    const done = { checked: 0, failed: 0, acknowledged: 0 };
    const due = await this.db.collection('playPurchases').where('nextCheckAt', '<=', now).orderBy('nextCheckAt').limit(limit).get();
    for (const doc of due.docs) {
      try {
        await this.refresh(doc.data().purchaseToken);
        done.checked++;
      } catch (e) {
        done.failed++;
        this.log.warn('Reconciliation failed for purchase ' + doc.id.slice(0, 12) + ': ' + redact(e.code || e.message));
      }
    }
    const acks = await this.db.collection('playPurchases').where('ackState', '==', 'pending').where('nextAckAt', '<=', now).limit(limit).get();
    for (const doc of acks.docs) {
      const record = doc.data();
      if (await this.acknowledgeIfNeeded(record.purchaseToken, record)) done.acknowledged++;
    }
    return done;
  }

  // ---------------------------------------------------------------------------------------
  // The transaction that records a verified state
  // ---------------------------------------------------------------------------------------

  /**
   * Record the verified facts of a purchase, decide its owner, mark the purchase it replaces,
   * and update the entitlement and the WHMCS outbox of the owner. Idempotent: the same facts
   * give the same records. A state that was fetched before the one that is stored is dropped.
   */
  async apply(token, facts, fetchedAt, { claimUid = null, email = null }) {
    const hash = sub.tokenHash(token);
    const ref = this.purchaseRef(hash);
    const now = this.now();
    return this.db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const existing = snap.exists ? snap.data() : null;
      let owner = existing && existing.uid ? existing.uid : null;
      if (claimUid) {
        if (owner && owner !== claimUid)
          throw new TermiNowError('PURCHASE_OF_ANOTHER_ACCOUNT', 'This Google Play purchase belongs to another TermiNow account');
        owner = claimUid;
      } else if (!owner && facts.accountId) {
        // Found by a notification first: the owner is known if they used the app before
        const index = await tx.get(this.db.collection('playAccounts').doc(facts.accountId));
        if (index.exists) owner = index.data().uid;
      }
      // An owner must match the account of the purchase, also when found by the index
      if (owner && facts.accountId !== sub.accountIdFor(owner)) owner = null;

      const linkedHash = facts.linkedPurchaseToken ? sub.tokenHash(facts.linkedPurchaseToken) : null;
      const linkedSnap = linkedHash ? await tx.get(this.purchaseRef(linkedHash)) : null;
      const ownedSnap = owner ? await tx.get(this.db.collection('playPurchases').where('uid', '==', owner)) : null;
      const entitlementSnap = owner ? await tx.get(this.db.collection('entitlements').doc(owner)) : null;
      const outboxSnap = owner ? await tx.get(this.db.collection('whmcsOutbox').doc(owner)) : null;

      // A slower check that started before the stored one must not overwrite it
      if (existing && (existing.fetchedAt || 0) > fetchedAt && existing.uid === owner) return existing;

      const ackState = facts.acknowledged ? 'acknowledged' : (sub.needsAcknowledgement(facts) ? 'pending' : 'not_needed');
      const record = {
        purchaseToken: token,
        uid: owner,
        accountId: facts.accountId,
        packageName: this.config.packageName,
        productId: facts.productId,
        basePlanId: facts.basePlanId,
        tier: facts.tier,
        whmcsProductId: facts.whmcsProductId,
        state: facts.state,
        expiryTime: facts.expiryTime,
        autoRenew: facts.autoRenew,
        test: facts.test,
        ackState: existing && existing.ackState === 'acknowledged' ? 'acknowledged' : ackState,
        ackAttempts: existing ? existing.ackAttempts || 0 : 0,
        nextAckAt: ackState === 'pending' ? (existing && existing.nextAckAt) || now : null,
        linkedTokenHash: linkedHash,
        supersededBy: existing ? existing.supersededBy || null : null,
        fetchedAt,
        lastVerifiedAt: now,
        // An owner that is not known yet is looked for hourly, for 30 days
        nextCheckAt: owner ? sub.nextCheckAt(facts, now) :
          (now - (existing ? existing.createdAt : now) < 30 * 24 * 60 * 60 * 1000 ? now + 60 * 60 * 1000 : null),
        checkFailures: 0,
        lastError: null,
        createdAt: existing ? existing.createdAt : now,
        updatedAt: now,
      };
      tx.set(ref, record);

      // An upgrade, downgrade or resubscription replaces the old purchase of the same account
      if (linkedSnap && linkedSnap.exists) {
        const linked = linkedSnap.data();
        if (!linked.uid || linked.uid === owner) tx.update(linkedSnap.ref, { supersededBy: hash, updatedAt: now });
      }
      if (claimUid) tx.set(this.db.collection('playAccounts').doc(sub.accountIdFor(claimUid)), { uid: claimUid, updatedAt: now });

      if (owner) {
        const purchases = new Map();
        for (const doc of ownedSnap.docs) purchases.set(doc.id, doc.data());
        purchases.set(hash, record);
        if (linkedHash && purchases.has(linkedHash)) purchases.set(linkedHash, { ...purchases.get(linkedHash), supersededBy: hash });
        const summary = sub.entitlementFrom([...purchases.values()], now);
        const entitlement = entitlementSnap.exists ? entitlementSnap.data() : {};
        // Other sources of Premium are kept, an expired Play subscription does not erase them
        const sources = { ...(entitlement.sources || {}), google_play: { ...summary, source: 'google_play' } };
        tx.set(entitlementSnap.ref, {
          premium: Object.values(sources).some((s) => s && s.premium === true),
          sources,
          updatedAt: now,
        });
        const outbox = outboxSnap.exists ? outboxSnap.data() : {};
        tx.set(outboxSnap.ref, {
          uid: owner,
          email: email || outbox.email || null,
          version: (outbox.version || 0) + 1,
          desired: {
            premium: summary.premium,
            tier: summary.tier || null,
            subscriptionState: summary.subscriptionState,
            expiresAt: summary.expiresAt || null,
            autoRenew: summary.autoRenewEnabled || false,
            test: summary.test || false,
            productId: summary.productId || null,
            basePlanId: summary.basePlanId || null,
            whmcsProductId: whmcsProductIdOf(this.config.productMap, summary),
            lastVerifiedAt: summary.lastVerifiedAt || null,
          },
          status: 'queued',
          attempts: 0,
          nextAttemptAt: now,
          syncedVersion: outbox.syncedVersion || 0,
          lastError: outbox.lastError || null,
          updatedAt: now,
        });
      }
      return record;
    });
  }

  async recordCheckFailure(hash, error) {
    const now = this.now();
    await this.db.runTransaction(async (tx) => {
      const snap = await tx.get(this.purchaseRef(hash));
      if (!snap.exists) return;
      const failures = (snap.data().checkFailures || 0) + 1;
      // Transient failures are retried with backoff, up to a day apart
      const delay = Math.min(24 * 60 * 60 * 1000, 5 * 60 * 1000 * Math.pow(2, failures - 1));
      tx.update(snap.ref, {
        checkFailures: failures,
        nextCheckAt: !error.transient && failures >= 5 ? null : now + delay,
        lastError: { code: error.code || 'UNKNOWN', at: now },
        updatedAt: now,
      });
    });
  }

  // ---------------------------------------------------------------------------------------
  // Acknowledgement
  // ---------------------------------------------------------------------------------------

  /**
   * Acknowledge a purchase once its entitlement is recorded. Google refunds a purchase that is
   * not acknowledged within three days, so a failure is retried now, then by reconciliation.
   * Returns whether the purchase was acknowledged by this call.
   */
  async acknowledgeIfNeeded(token, record) {
    if (!record || record.ackState !== 'pending' || !record.uid || record.state === 'SUBSCRIPTION_STATE_PENDING') return false;
    const hash = sub.tokenHash(token);
    let lastError = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        await this.play.acknowledge(record.productId, token);
        await this.purchaseRef(hash).update({ ackState: 'acknowledged', nextAckAt: null, updatedAt: this.now() });
        record.ackState = 'acknowledged';
        return true;
      } catch (e) {
        lastError = e;
        if (!e.transient) break;
        await sleep(200 * Math.pow(2, attempt));
      }
    }
    const attempts = (record.ackAttempts || 0) + 1;
    await this.purchaseRef(hash).update({
      ackAttempts: attempts,
      nextAckAt: this.now() + Math.min(6 * 60 * 60 * 1000, 2 * 60 * 1000 * Math.pow(2, attempts - 1)),
      lastError: { code: (lastError && lastError.code) || 'ACK_FAILED', at: this.now() },
      updatedAt: this.now(),
    });
    this.log.warn('Acknowledgement failed for purchase ' + hash.slice(0, 12) + ': ' + redact(lastError && (lastError.code || lastError.message)));
    return false;
  }

  // ---------------------------------------------------------------------------------------
  // WHMCS
  // ---------------------------------------------------------------------------------------

  /**
   * Send the latest state of an account to WHMCS. Never blocks Premium: the entitlement is
   * already recorded. Retries back off and stop after a bounded number of attempts.
   */
  async syncWhmcs(uid) {
    const ref = this.db.collection('whmcsOutbox').doc(uid);
    const snap = await ref.get();
    if (!snap.exists) return 'missing';
    const outbox = snap.data();
    if (outbox.syncedVersion >= outbox.version) return 'synced';
    if (!this.whmcs) {
      await ref.update({ status: 'not_configured', updatedAt: this.now() });
      return 'not_configured';
    }
    const version = outbox.version;
    try {
      const answer = await this.whmcs.sync({ uid, email: outbox.email, version, ...outbox.desired });
      await this.db.runTransaction(async (tx) => {
        const current = (await tx.get(ref)).data();
        tx.update(ref, {
          syncedVersion: Math.max(current.syncedVersion || 0, version),
          status: current.version > version ? 'queued' : 'synced',
          whmcs: { clientId: answer.clientId || null, serviceId: answer.serviceId || null, status: answer.status || null },
          attempts: 0,
          lastError: null,
          lastSyncAt: this.now(),
          updatedAt: this.now(),
        });
      });
      return 'synced';
    } catch (e) {
      const attempts = (outbox.attempts || 0) + 1;
      const failed = attempts >= 12;
      await ref.update({
        attempts,
        status: failed ? 'failed' : 'retrying',
        nextAttemptAt: failed ? null : this.now() + Math.min(12 * 60 * 60 * 1000, 60 * 1000 * Math.pow(2, attempts - 1)),
        lastError: { code: e.code || 'WHMCS_ERROR', message: redact(e.message).slice(0, 300), at: this.now() },
        updatedAt: this.now(),
      });
      this.log.warn('WHMCS sync failed for an account (attempt ' + attempts + '): ' + redact(e.code || e.message));
      return failed ? 'failed' : 'retrying';
    }
  }

  /** The outboxes that are due, for the scheduled job. */
  async syncDueWhmcs(limit = 100) {
    const due = await this.db.collection('whmcsOutbox').where('nextAttemptAt', '<=', this.now()).limit(limit).get();
    const results = {};
    for (const doc of due.docs) {
      if (doc.data().status === 'synced' || doc.data().status === 'failed') continue;
      const result = await this.syncWhmcs(doc.id);
      results[result] = (results[result] || 0) + 1;
    }
    return results;
  }

  /** Link an existing WHMCS client with a one-time code that its owner got in the client area. */
  async linkWhmcs(uid, code) {
    if (!uid) throw new TermiNowError('SIGN_IN_REQUIRED', 'Sign in to TermiNow first');
    if (typeof code !== 'string' || !/^[A-Z0-9]{8}$/.test(code.trim().toUpperCase()))
      throw new TermiNowError('INVALID_LINK_CODE', 'Enter the 8 character code from the client area');
    if (!this.whmcs) throw new TermiNowError('WHMCS_NOT_CONFIGURED', 'Linking accounts is not set up on the server');
    const answer = await this.whmcs.redeemLink({ uid, code: code.trim().toUpperCase() });
    await this.db.collection('whmcsLinks').doc(uid).set({ clientId: answer.clientId, linkedAt: this.now() });
    // The next sync uses the linked client
    const outbox = this.db.collection('whmcsOutbox').doc(uid);
    if ((await outbox.get()).exists) await outbox.update({ version: FieldValue.increment(1), status: 'queued', attempts: 0, nextAttemptAt: this.now() });
    return { linked: true };
  }
}

function whmcsProductIdOf(productMap, summary) {
  const plans = summary.productId ? productMap[summary.productId] : null;
  const plan = plans && summary.basePlanId ? plans[summary.basePlanId] : null;
  return plan && plan.whmcsProductId ? plan.whmcsProductId : null;
}

/** The entitlement as the app may see it, the same as it can read itself. */
function publicEntitlement(entitlement) {
  const play = (entitlement.sources && entitlement.sources.google_play) || null;
  return { premium: Boolean(entitlement.premium), google_play: play };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { Service, publicEntitlement };
