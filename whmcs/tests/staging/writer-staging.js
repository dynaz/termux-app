'use strict';
// EMULATED-FIREBASE harness for the REAL writer. Mirrors firebase/functions/index.js's whmcsEntitlement
// onRequest wrapper exactly, but binds firebase-admin to a local Firestore EMULATOR and a throwaway
// project. It is the production handler (src/entitlement.js makeHandler) over a real Admin-SDK write
// path — nothing is mocked here — but it is NOT the deployed Cloud Function and NOT a real project.
//
// Requires PR #12's firebase/functions/src/entitlement.js to be present (set TERMINOW_FUNCTIONS_DIR if
// the functions tree is elsewhere, e.g. when running from the whmcs-only branch).
const http = require('http');
const path = require('path');
const FUNCTIONS_DIR = process.env.TERMINOW_FUNCTIONS_DIR || path.resolve(__dirname, '../../../firebase/functions');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { makeHandler } = require(path.join(FUNCTIONS_DIR, 'src/entitlement'));

initializeApp({ projectId: process.env.GCLOUD_PROJECT || 'demo-terminow' });
const db = getFirestore();
const SECRET = process.env.WHMCS_ENTITLEMENT_SECRET || '';
const PORT = Number(process.env.WRITER_PORT || 9321);

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', async () => {
    const rawBody = Buffer.concat(chunks).toString('utf8');
    const headers = {
      'x-terminow-timestamp': String(req.headers['x-terminow-timestamp'] || ''),
      'x-terminow-signature': String(req.headers['x-terminow-signature'] || ''),
    };
    // grantsPremium is read per request, exactly as index.js reads process.env.WHMCS_GRANTS_PREMIUM.
    const handler = makeHandler({ db, secret: SECRET, grantsPremium: process.env.WHMCS_GRANTS_PREMIUM === '1' });
    let result;
    try { result = await handler({ method: req.method, headers, rawBody }); }
    catch (e) { result = { status: 503, body: { ok: false, code: 'WRITER_ERROR', error: String(e && e.message) } }; }
    res.writeHead(result.status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(result.body));
  });
});
server.listen(PORT, '127.0.0.1', () => console.log('writer-staging :' + PORT + ' grantsPremium=' + (process.env.WHMCS_GRANTS_PREMIUM === '1') + ' emulator=' + process.env.FIRESTORE_EMULATOR_HOST));
