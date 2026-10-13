'use strict';

/**
 * TermiNow Cloud Functions: Google Play subscriptions and their Premium entitlement.
 *
 * - verifyPlayPurchase (callable): the app sends a purchase token after a purchase, a restore
 *   or when it starts. The uid comes from the verified Firebase ID token of the caller.
 * - linkWhmcsAccount (callable): link an existing WHMCS client with a code from the client area.
 * - onPlayNotification (Pub/Sub): Real-time Developer Notifications of Google Play.
 * - reconcilePlayPurchases (scheduled): purchases that are due, failed acknowledgements.
 * - syncWhmcs (Firestore trigger) and retryWhmcsSync (scheduled): the WHMCS outbox.
 *
 * See firebase/README.md for the configuration and the setup.
 */

const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { setGlobalOptions } = require('firebase-functions/v2');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onMessagePublished } = require('firebase-functions/v2/pubsub');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { defineString, defineSecret } = require('firebase-functions/params');
const logger = require('firebase-functions/logger');

const config = require('./src/config');
const { PlayClient } = require('./src/play');
const { WhmcsClient } = require('./src/whmcs');
const { Service } = require('./src/service');
const { TermiNowError, redact } = require('./src/errors');

// Configuration, set in functions/.env.<project id> (not secret) and Secret Manager (secret)
const FUNCTIONS_REGION = defineString('FUNCTIONS_REGION', { description: 'Region of the functions, like asia-southeast1' });
defineString('PLAY_PACKAGE_NAME', { description: 'The Android package, com.terminow' });
defineString('PLAY_PRODUCT_MAP', { description: 'JSON: {"<product>": {"<base plan>": {"tier": "premium", "whmcsProductId": N}}}' });
defineString('WHMCS_SYNC_URL', { default: '', description: 'https URL of whmcs/terminow/play_sync.php, empty to not sync' });
const WHMCS_SYNC_SECRET = defineSecret('WHMCS_SYNC_SECRET');
// The topic of Real-time Developer Notifications, the same as in Play Console. It names the
// trigger, so it is read when the deploy loads this file, before .env values are there.
const RTDN_TOPIC = process.env.PLAY_RTDN_TOPIC || 'play-rtdn';

setGlobalOptions({ region: FUNCTIONS_REGION, maxInstances: 10 });
initializeApp();

let cached = null;

/** The service, created once per instance with the configuration checked. */
function service() {
  if (cached) return cached;
  const cfg = config.load({ ...process.env, WHMCS_SYNC_SECRET: WHMCS_SYNC_SECRET.value() });
  for (const problem of cfg.problems) logger.error('Configuration: ' + problem.code + ': ' + problem.message);
  config.requireUsable(cfg);
  const whmcs = cfg.whmcsUrl && !cfg.problems.some((p) => p.code.startsWith('CONFIG_WHMCS')) ? new WhmcsClient(cfg.whmcsUrl, cfg.whmcsSecret) : null;
  cached = new Service({ db: getFirestore(), play: new PlayClient(cfg.packageName), config: cfg, whmcs, log: logger });
  return cached;
}

/** A sanitized error for the app: a stable code and a message without secrets. */
function toHttpsError(e) {
  if (e instanceof TermiNowError) {
    const kind = e.transient ? 'unavailable' : e.code === 'SIGN_IN_REQUIRED' ? 'unauthenticated' :
      e.code === 'PURCHASE_OF_ANOTHER_ACCOUNT' ? 'permission-denied' : e.code === 'BACKEND_NOT_CONFIGURED' ? 'failed-precondition' : 'invalid-argument';
    return new HttpsError(kind, e.message, { code: e.code, transient: e.transient });
  }
  logger.error('Unexpected error: ' + redact(e && e.stack ? e.stack : e));
  return new HttpsError('internal', 'The purchase could not be checked, try again later', { code: 'INTERNAL', transient: true });
}

exports.verifyPlayPurchase = onCall({ secrets: [WHMCS_SYNC_SECRET], enforceAppCheck: false }, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to TermiNow first', { code: 'SIGN_IN_REQUIRED' });
  const token = request.data && request.data.purchaseToken;
  try {
    return await service().verifyForUser(request.auth.uid, request.auth.token.email_verified ? request.auth.token.email : null, token);
  } catch (e) {
    throw toHttpsError(e);
  }
});

exports.linkWhmcsAccount = onCall({ secrets: [WHMCS_SYNC_SECRET] }, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to TermiNow first', { code: 'SIGN_IN_REQUIRED' });
  try {
    return await service().linkWhmcs(request.auth.uid, request.data && request.data.code);
  } catch (e) {
    throw toHttpsError(e);
  }
});

{
  exports.onPlayNotification = onMessagePublished({ topic: RTDN_TOPIC, secrets: [WHMCS_SYNC_SECRET], retry: true }, async (event) => {
    let message;
    try {
      message = event.data.message.json;
    } catch (e) {
      logger.warn('RTDN message that is not JSON ignored');
      return;
    }
    try {
      const result = await service().handleNotification(message, event.data.message.messageId);
      logger.info('RTDN ' + result.handled);
    } catch (e) {
      // A transient failure is retried by Pub/Sub; anything else is left to reconciliation
      if (e instanceof TermiNowError && !e.transient) {
        logger.warn('RTDN not applied: ' + e.code);
        return;
      }
      throw toHttpsError(e);
    }
  });
}

exports.reconcilePlayPurchases = onSchedule({ schedule: 'every 30 minutes', secrets: [WHMCS_SYNC_SECRET] }, async () => {
  const done = await service().reconcile();
  logger.info('Reconciliation: ' + JSON.stringify(done));
});

// A Firestore trigger runs where the database is: nam5, so a US region, whatever FUNCTIONS_REGION is
exports.syncWhmcs = onDocumentWritten({ document: 'whmcsOutbox/{uid}', region: process.env.FIRESTORE_TRIGGER_REGION || 'us-central1',
  secrets: [WHMCS_SYNC_SECRET] }, async (event) => {
  const after = event.data && event.data.after && event.data.after.data();
  if (!after || after.status !== 'queued' || after.syncedVersion >= after.version) return;
  await service().syncWhmcs(event.params.uid);
});

exports.retryWhmcsSync = onSchedule({ schedule: 'every 15 minutes', secrets: [WHMCS_SYNC_SECRET] }, async () => {
  const done = await service().syncDueWhmcs();
  logger.info('WHMCS retries: ' + JSON.stringify(done));
});
