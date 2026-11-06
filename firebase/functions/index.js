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
 * - enrollWhmcsMembers (scheduled): every account with a verified email, free too, into the outbox.
 * - accountDevices (callable): the devices signed in to the account, and signing them out.
 * - signedEntitlement (callable): records Play Integrity verdicts, observed only; signs nothing yet.
 *
 * See firebase/README.md for the configuration and the setup.
 */

const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getAuth } = require('firebase-admin/auth');
const { setGlobalOptions } = require('firebase-functions/v2');
const { onCall, onRequest, HttpsError } = require('firebase-functions/v2/https');
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
const { Devices } = require('./src/devices');
const { IntegrityClient, IntegrityObserver } = require('./src/integrity');
const crypto = require('crypto');
const storeGrant = require('./src/storeGrant');

// Configuration, set in functions/.env.<project id> (not secret) and Secret Manager (secret)
const FUNCTIONS_REGION = defineString('FUNCTIONS_REGION', { description: 'Region of the functions, like asia-southeast1' });
defineString('PLAY_PACKAGE_NAME', { description: 'The Android package, com.terminow' });
defineString('PLAY_PRODUCT_MAP', { description: 'JSON: {"<product>": {"<base plan>": {"tier": "premium", "whmcsProductId": N}}}' });
defineString('WHMCS_SYNC_URL', { default: '', description: 'https URL of whmcs/terminow/play_sync.php, empty to not sync' });
const WHMCS_SYNC_SECRET = defineSecret('WHMCS_SYNC_SECRET');
// WHMCS -> Firebase entitlement writer (src/entitlement.js). The secret is shared with the WHMCS addon
// setting entitlementSyncSecret. WHMCS_GRANTS_PREMIUM gates whether an Active WHMCS service flips
// top-level Premium: '' (default) records the whmcs source VISIBLY without granting (premium.php stays
// the compatibility path); '1' is the deliberate, reviewed go-live that lets WHMCS grant Premium.
const WHMCS_ENTITLEMENT_SECRET = defineSecret('WHMCS_ENTITLEMENT_SECRET');
defineString('WHMCS_GRANTS_PREMIUM', { default: '', description: "'1' to let WHMCS services grant Premium; empty records the source without granting" });
const { makeHandler: makeWhmcsEntitlementHandler } = require('./src/entitlement');
// TermiNow Store -> Firebase entitlement receiver (src/storeGrant.js). The secret is shared with the
// Store's TERMINOW_ENTITLEMENT_KEY. STORE_GRANTS_PREMIUM gates whether a real (non-sandbox) Store grant
// flips top-level Premium: '' (default) records the Store source VISIBLY without granting; '1' is the
// deliberate, reviewed go-live. A sandbox (Devnet/canary) grant is ALWAYS recorded-only, never granting.
const STORE_GRANT_SECRET = defineSecret('STORE_GRANT_SECRET');
defineString('STORE_GRANTS_PREMIUM', { default: '', description: "'1' to let a real (non-sandbox) Store purchase grant Premium; empty records the source without granting" });
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
function toHttpsError(e, fallback = 'The purchase could not be checked, try again later') {
  if (e instanceof TermiNowError) {
    const kind = e.transient ? 'unavailable' : e.code === 'SIGN_IN_REQUIRED' ? 'unauthenticated' :
      e.code === 'PURCHASE_OF_ANOTHER_ACCOUNT' ? 'permission-denied' : e.code === 'BACKEND_NOT_CONFIGURED' ? 'failed-precondition' : 'invalid-argument';
    return new HttpsError(kind, e.message, { code: e.code, transient: e.transient });
  }
  logger.error('Unexpected error: ' + redact(e && e.stack ? e.stack : e));
  return new HttpsError('internal', fallback, { code: 'INTERNAL', transient: true });
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

exports.accountDevices = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to TermiNow first', { code: 'SIGN_IN_REQUIRED' });
  try {
    return await new Devices({ db: getFirestore(), auth: getAuth() }).handle(request.auth.uid, request.data || {});
  } catch (e) {
    throw toHttpsError(e, 'The devices could not be read, try again later');
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

exports.enrollWhmcsMembers = onSchedule({ schedule: 'every 60 minutes', secrets: [WHMCS_SYNC_SECRET] }, async () => {
  const totals = {};
  let pageToken;
  do {
    const page = await getAuth().listUsers(1000, pageToken);
    const done = await service().enrollMembers(page.users.map((u) => ({ uid: u.uid, email: u.email || null, emailVerified: u.emailVerified })));
    for (const [key, n] of Object.entries(done)) totals[key] = (totals[key] || 0) + n;
    pageToken = page.pageToken;
  } while (pageToken);
  logger.info('WHMCS members: ' + JSON.stringify(totals));
});

exports.retryWhmcsSync = onSchedule({ schedule: 'every 15 minutes', secrets: [WHMCS_SYNC_SECRET] }, async () => {
  const done = await service().syncDueWhmcs();
  logger.info('WHMCS retries: ' + JSON.stringify(done));
});

// WHMCS service-lifecycle writer (HTTP). whmcs/terminow/entitlement_lib.php POSTs one signed event
// per feature here; it verifies the HMAC, confirms the client<->uid link against whmcsLinks/{uid},
// and merges ONLY sources.whmcs into entitlements/{uid} (idempotent + ordered). Google Play and Store
// sources are preserved. Premium stays gated off unless WHMCS_GRANTS_PREMIUM='1'.
exports.whmcsEntitlement = onRequest({ secrets: [WHMCS_ENTITLEMENT_SECRET], cors: false }, async (req, res) => {
  const headers = {
    'x-terminow-timestamp': String(req.get('X-TermiNow-Timestamp') || ''),
    'x-terminow-signature': String(req.get('X-TermiNow-Signature') || ''),
  };
  const rawBody = req.rawBody ? req.rawBody.toString('utf8') : (typeof req.body === 'string' ? req.body : JSON.stringify(req.body || {}));
  const handler = makeWhmcsEntitlementHandler({
    db: getFirestore(),
    secret: WHMCS_ENTITLEMENT_SECRET.value(),
    grantsPremium: process.env.WHMCS_GRANTS_PREMIUM === '1',
  });
  let result;
  try {
    result = await handler({ method: req.method, headers, rawBody });
  } catch (e) {
    logger.error('whmcsEntitlement error: ' + redact(e && e.message));
    result = { status: 503, body: { ok: false, code: 'WRITER_ERROR', error: 'Try again later' } };
  }
  res.status(result.status).json(result.body);
});

// Play Integrity, observed only (src/integrity.js): the app asks for the signed entitlement and
// sends a token; no entitlement is signed yet, so it answers {signed: null} and Premium stays as
// the other functions decide. A failure here is never an error for the app.
exports.signedEntitlement = onCall(async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to TermiNow first', { code: 'SIGN_IN_REQUIRED' });
  try {
    const packageName = process.env.PLAY_PACKAGE_NAME;
    return await new IntegrityObserver({ db: getFirestore(), client: new IntegrityClient(packageName), packageName, log: logger })
      .observe(request.auth.uid, request.data || {});
  } catch (e) {
    throw toHttpsError(e, 'The request could not be checked, try again later');
  }
});

// Entitlement delivery from the TermiNow Store (store.terminow.online). A server-to-server HTTPS call,
// authenticated by a shared secret (Authorization: Bearer <STORE_GRANT_SECRET>), carrying a purchase the
// Store already verified on-chain. It is keyed on terminowRef (the Firebase UID) — NEVER an email — and
// records a labeled Store source on entitlements/{uid} (see src/storeGrant.js). Idempotent on eventId
// via storeDeliveries/{eventId}. Real Premium stays DISABLED unless STORE_GRANTS_PREMIUM === '1' AND the
// grant is not a sandbox (Devnet/canary) grant; a sandbox grant is recorded but never flips `premium`.
exports.storeEntitlement = onRequest({ secrets: [STORE_GRANT_SECRET] }, async (req, res) => {
  try {
    if (req.method !== 'POST') return void res.status(405).json({ error: 'POST only' });
    const secret = STORE_GRANT_SECRET.value();
    const header = req.get('authorization') || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    const a = Buffer.from(token), b = Buffer.from(secret || '');
    if (!secret || a.length !== b.length || !crypto.timingSafeEqual(a, b))
      return void res.status(401).json({ error: 'unauthorized' });

    const grant = storeGrant.validateGrant(req.body || {});
    const allowPremium = process.env.STORE_GRANTS_PREMIUM === '1' && grant.sandbox !== true;
    const db = getFirestore();
    const result = await db.runTransaction(async (tx) => {
      const deliveryRef = db.collection('storeDeliveries').doc(grant.eventId);
      if ((await tx.get(deliveryRef)).exists) return { duplicate: true };
      const entRef = db.collection('entitlements').doc(grant.terminowRef);
      const snap = await tx.get(entRef);
      const next = storeGrant.applyStoreSource(snap.exists ? snap.data() : {}, grant, { allowPremium });
      tx.set(entRef, next);
      tx.create(deliveryRef, {
        eventId: grant.eventId, terminowRef: grant.terminowRef, featureKey: grant.featureKey,
        kind: grant.kind, source: grant.source, sandbox: grant.sandbox, appliedAt: Date.now(),
      });
      return { duplicate: false, premium: next.premium };
    });
    return void res.status(200).json({ ok: true, ...result });
  } catch (e) {
    if (e instanceof TermiNowError) return void res.status(400).json({ error: e.code });
    logger.error('storeEntitlement error: ' + redact(e && e.stack ? e.stack : e));
    return void res.status(500).json({ error: 'INTERNAL' });
  }
});
