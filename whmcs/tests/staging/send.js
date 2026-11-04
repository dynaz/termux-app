'use strict';
// Craft one signed delivery to the staging writer (bypassing the outbox dedup) so duplicate and
// out-of-order seq can be exercised at the writer. Uses the same HMAC scheme as entitlement_lib::sign.
// Usage: node send.js '<eventJson>' <idempotencyKey> <seq>
const { createHmac } = require('node:crypto');
const { readFileSync } = require('node:fs');
const http = require('node:http');

const [eventJson, idem, seq] = process.argv.slice(2);
const secret = readFileSync(process.env.ENTITLEMENT_SECRET_FILE, 'utf8').trim();
const body = JSON.stringify({ event: JSON.parse(eventJson), idempotencyKey: idem, seq: Number(seq) });
const ts = String(Math.floor(Date.now() / 1000));
const sig = createHmac('sha256', secret).update(ts + '.' + body).digest('hex');

const u = new URL(process.env.ENTITLEMENT_URL);
const req = http.request({ hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-TermiNow-Timestamp': ts, 'X-TermiNow-Signature': sig, 'Content-Length': Buffer.byteLength(body) } },
  (res) => { let d = ''; res.on('data', (c) => d += c); res.on('end', () => console.log('HTTP ' + res.statusCode + ' ' + d)); });
req.on('error', (e) => { console.error(e.message); process.exit(1); });
req.write(body); req.end();
