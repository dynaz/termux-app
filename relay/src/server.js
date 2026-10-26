// The TermiNow MCP relay HTTP server. It presents a standards-compliant remote MCP endpoint with
// OAuth 2.1 to AI clients (ChatGPT developer mode, Claude Desktop custom connectors), and forwards
// MCP JSON-RPC to the user's TermiNow device over the outbound device channel. It holds no session
// content: the device runs the tasks and keeps their state. Terminate TLS in front of this (see
// deploy/); it listens on plain HTTP so a reverse proxy can add HTTPS and HTTP/2.

import http from 'node:http';
import { URL } from 'node:url';
import { loadConfig } from './config.js';
import { OAuth } from './oauth.js';
import { DeviceHub } from './deviceHub.js';
import { googleConfigured, googleAuthUrl, fetchGoogleEmail } from './google.js';
import { checkPremium, backendConfigured } from './backend.js';
import { readFileSync } from 'node:fs';

// The connector logo, loaded once; served at /icon.png and referenced from serverInfo.icons.
const ICON = (() => {
  try {
    return readFileSync(new URL('./icon.png', import.meta.url));
  } catch {
    return null;
  }
})();

const MAX_BODY = 1024 * 1024;

export function createServer(config = loadConfig()) {
  const oauth = new OAuth(config);
  const hub = new DeviceHub(config);

  const server = http.createServer((req, res) => {
    handle(req, res, { config, oauth, hub }).catch((e) => {
      sendJson(res, 500, { error: 'server_error', message: String(e && e.message || e) });
    });
  });
  server.oauth = oauth;
  server.hub = hub;
  server.config = config;
  return server;
}

async function handle(req, res, ctx) {
  const url = new URL(req.url, ctx.config.publicUrl);
  const path = url.pathname;

  if (req.method === 'GET' && path === '/healthz') return sendJson(res, 200, { ok: true });

  if (req.method === 'GET' && path === '/icon.png') {
    if (!ICON) return sendJson(res, 404, { error: 'not_found' });
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': ICON.length, 'Cache-Control': 'public, max-age=86400' });
    return res.end(ICON);
  }

  // Discovery. Clients form the metadata URL path-aware per RFC 9728 — inserting the well-known
  // segment before the resource path — so /mcp's metadata is at
  // /.well-known/oauth-protected-resource/mcp; the root form is also served for clients that use it.
  if (req.method === 'GET' &&
      (path === '/.well-known/oauth-protected-resource' || path === '/.well-known/oauth-protected-resource/mcp'))
    return sendJson(res, 200, ctx.oauth.protectedResourceMetadata());
  if (req.method === 'GET' &&
      (path === '/.well-known/oauth-authorization-server' || path === '/.well-known/oauth-authorization-server/mcp'))
    return sendJson(res, 200, ctx.oauth.authorizationServerMetadata());

  if (req.method === 'POST' && path === '/register') {
    if (!ctx.config.allowDynamicRegistration) return sendJson(res, 404, { error: 'not_found' });
    const body = await readJson(req);
    return sendJson(res, 201, ctx.oauth.register(body || {}));
  }

  if (path === '/authorize') return handleAuthorize(req, res, ctx, url);
  if (req.method === 'POST' && path === '/token') return handleToken(req, res, ctx);
  if (req.method === 'GET' && path === '/oauth/google/start') return handleGoogleStart(req, res, ctx, url);
  if (req.method === 'GET' && path === '/oauth/google/callback') return handleGoogleCallback(req, res, ctx, url);

  if (path === '/mcp') return handleMcp(req, res, ctx);

  if (req.method === 'GET' && path === '/device/stream') return handleDeviceStream(req, res, ctx, url);
  if (req.method === 'POST' && path === '/device/rpc') return handleDeviceRpc(req, res, ctx);
  if (req.method === 'POST' && path === '/device/pairing-codes') return handleDevicePairingCodes(req, res, ctx);
  if (req.method === 'POST' && path === '/device/google-emails') return handleDeviceGoogleEmails(req, res, ctx);
  if (req.method === 'POST' && path === '/device/enroll') return handleDeviceEnroll(req, res, ctx);

  sendJson(res, 404, { error: 'not_found' });
}

// ---------------------------------------------------------------------------------------
// OAuth: consent and token
// ---------------------------------------------------------------------------------------

async function handleAuthorize(req, res, ctx, url) {
  if (req.method === 'GET') {
    const q = Object.fromEntries(url.searchParams);
    if ((q.code_challenge_method || 'S256') !== 'S256')
      return sendHtml(res, 400, '<p>Only PKCE S256 is supported.</p>');
    return sendHtml(res, 200, consentPage(q, consentOpts(ctx)));
  }
  if (req.method === 'POST') {
    const form = await readForm(req);
    const redirectUri = form.redirect_uri;
    if (!redirectUri || !ctx.oauth.knownRedirect(form.client_id, redirectUri))
      return sendHtml(res, 400, '<p>Unknown redirect_uri.</p>');
    // Multi-tenant sign-in: a Firebase ID token the browser obtained, verified for Premium by the
    // central authority. The identity is the verified uid, never anything the page claimed.
    if (form.firebase_id_token) {
      if (ctx.config.googleSigninDisabled)
        return sendHtml(res, 503, consentPage(form, consentOpts(ctx, 'Sign in with Google is temporarily unavailable. Please use a pairing code.')));
      const info = await checkPremium(ctx.config, form.firebase_id_token);
      if (info.error || !info.premium) return sendHtml(res, 403, consentPage(form, consentOpts(ctx,
        info.premium === false ? 'This account does not have TermiNow Premium.' : 'Sign-in could not be verified. Try again.')));
      const authCode = ctx.oauth.issueCodeForUser(info.uid, { clientId: form.client_id, redirectUri, challenge: form.code_challenge });
      if (!authCode) return sendHtml(res, 400, '<p>PKCE is required.</p>');
      const loc = new URL(redirectUri);
      loc.searchParams.set('code', authCode);
      if (form.state) loc.searchParams.set('state', form.state);
      res.writeHead(302, { Location: loc.toString() });
      return res.end();
    }
    const code = ctx.oauth.issueCode({
      pairingCode: (form.pairing_code || '').trim(),
      clientId: form.client_id,
      redirectUri,
      challenge: form.code_challenge,
    });
    if (!code) return sendHtml(res, 403, consentPage(form,
      consentOpts(ctx, 'That pairing code was not recognised. Check it in TermiNow.')));
    const location = new URL(redirectUri);
    location.searchParams.set('code', code);
    if (form.state) location.searchParams.set('state', form.state);
    res.writeHead(302, { Location: location.toString() });
    res.end();
    return;
  }
  sendJson(res, 405, { error: 'method_not_allowed' });
}

async function handleToken(req, res, ctx) {
  const form = await readForm(req);
  let result;
  if (form.grant_type === 'authorization_code') {
    result = ctx.oauth.exchangeCode({ code: form.code, verifier: form.code_verifier, redirectUri: form.redirect_uri });
  } else if (form.grant_type === 'refresh_token') {
    result = ctx.oauth.refresh(form.refresh_token);
  } else {
    result = { error: 'unsupported_grant_type' };
  }
  if (result.error) return sendJson(res, 400, result);
  res.setHeader('Cache-Control', 'no-store');
  return sendJson(res, 200, result);
}

// "Sign in with Google": send the user to Google, keeping the client's OAuth params for the return.
function handleGoogleStart(req, res, ctx, url) {
  if (!googleConfigured(ctx.config)) return sendHtml(res, 404, '<p>Google sign-in is not configured.</p>');
  const q = Object.fromEntries(url.searchParams);
  if (!q.redirect_uri || !ctx.oauth.knownRedirect(q.client_id, q.redirect_uri))
    return sendHtml(res, 400, '<p>Unknown redirect_uri.</p>');
  if ((q.code_challenge_method || 'S256') !== 'S256') return sendHtml(res, 400, '<p>Only PKCE S256 is supported.</p>');
  if (!q.code_challenge) return sendHtml(res, 400, '<p>PKCE is required.</p>');
  const gstate = ctx.oauth.startGoogleLogin(
    { clientId: q.client_id, redirectUri: q.redirect_uri, challenge: q.code_challenge, state: q.state });
  res.writeHead(302, { Location: googleAuthUrl(ctx.config, gstate) });
  res.end();
}

// Google returns here; verify the email, map it to a user, and issue our authorization code.
async function handleGoogleCallback(req, res, ctx, url) {
  const saved = ctx.oauth.takeGoogleState(url.searchParams.get('state'));
  if (!saved) return sendHtml(res, 400, '<p>This sign-in link has expired. Start again from your AI client.</p>');
  const code = url.searchParams.get('code');
  if (!code) return backToConsent(res, ctx, saved, 'Google sign-in was cancelled.');
  const result = await fetchGoogleEmail(ctx.config, code);
  if (result.error) return backToConsent(res, ctx, saved, 'Google sign-in failed. Try again.');
  const userId = ctx.oauth.userForEmail(result.email);
  if (!userId) return backToConsent(res, ctx, saved, 'That Google account is not linked to a TermiNow device.');
  const authCode = ctx.oauth.issueCodeForUser(userId,
    { clientId: saved.clientId, redirectUri: saved.redirectUri, challenge: saved.challenge });
  if (!authCode) return backToConsent(res, ctx, saved, 'Could not complete sign-in.');
  const location = new URL(saved.redirectUri);
  location.searchParams.set('code', authCode);
  if (saved.state) location.searchParams.set('state', saved.state);
  res.writeHead(302, { Location: location.toString() });
  res.end();
}

function backToConsent(res, ctx, saved, errorText) {
  const q = {
    client_id: saved.clientId, redirect_uri: saved.redirectUri, state: saved.state || '',
    code_challenge: saved.challenge, code_challenge_method: 'S256', response_type: 'code', scope: 'mcp',
  };
  return sendHtml(res, 200, consentPage(q, consentOpts(ctx, errorText)));
}

// ---------------------------------------------------------------------------------------
// MCP endpoint (Streamable HTTP): forward to the user's device
// ---------------------------------------------------------------------------------------

async function handleMcp(req, res, ctx) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return sendJson(res, 405, { error: 'method_not_allowed' });
  }
  const userId = ctx.oauth.userForBearer(req.headers['authorization']);
  if (!userId) {
    const prm = ctx.config.publicUrl + '/.well-known/oauth-protected-resource';
    res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${prm}"`);
    return sendJson(res, 401, { error: 'invalid_token', message: 'An OAuth bearer token is required' });
  }
  const message = await readJson(req);
  if (!message || typeof message !== 'object') return sendJson(res, 400, jsonRpcError(null, -32700, 'Parse error'));

  const isRequest = Object.prototype.hasOwnProperty.call(message, 'id') && message.id !== null;
  if (!isRequest) {
    // A notification has no reply; still forward it so the device sees it, but answer 202.
    ctx.hub.callDevice(userId, message).catch(() => {});
    res.writeHead(202);
    return res.end();
  }
  try {
    const response = await ctx.hub.callDevice(userId, message);
    console.log('relay: mcp ok method=%s uid=%s', message.method, userId);
    // Brand the connector: add a display title and icon to the device's serverInfo on initialize,
    // without changing its stable name. Clients that render these (e.g. ChatGPT) show TermiNow.
    if (message.method === 'initialize' && response && response.result && response.result.serverInfo) {
      response.result.serverInfo.title = 'TermiNow';
      response.result.serverInfo.websiteUrl = 'https://terminow.online';
      response.result.serverInfo.icons = [{ src: ctx.config.publicUrl + '/icon.png', mimeType: 'image/png', sizes: '192x192' }];
    }
    return sendJson(res, 200, response);
  } catch (e) {
    console.log('relay: mcp FAILED method=%s uid=%s err=%s', message.method, userId, String(e && e.message || e));
    return sendJson(res, 200, jsonRpcError(message.id, -32001, String(e && e.message || e)));
  }
}

// ---------------------------------------------------------------------------------------
// Device channel
// ---------------------------------------------------------------------------------------

function handleDeviceStream(req, res, ctx, url) {
  const token = bearerOrQueryToken(req, url);
  const userId = ctx.hub.userForDeviceToken(token);
  if (!userId) return sendJson(res, 401, { error: 'invalid_device_token' });
  const dispose = ctx.hub.addStream(userId, res);
  req.on('close', dispose);
  req.on('aborted', dispose);
}

async function handleDeviceRpc(req, res, ctx) {
  const token = bearerOrQueryToken(req, new URL(req.url, ctx.config.publicUrl));
  const userId = ctx.hub.userForDeviceToken(token);
  if (!userId) return sendJson(res, 401, { error: 'invalid_device_token' });
  const body = await readJson(req);
  const delivered = ctx.hub.deliverResponse(userId, body || {});
  return sendJson(res, delivered ? 200 : 409, { delivered });
}

// The device registers the pairing codes it shows in "Pair a client", so the consent screen
// accepts them without the operator editing RELAY_PAIRING_CODES. Authenticated by the device token,
// so a user can set codes only for their own account; the static env codes remain a fallback.
const MAX_PAIRING_CODES = 64;
async function handleDevicePairingCodes(req, res, ctx) {
  const token = bearerOrQueryToken(req, new URL(req.url, ctx.config.publicUrl));
  const userId = ctx.hub.userForDeviceToken(token);
  if (!userId) return sendJson(res, 401, { error: 'invalid_device_token' });
  const body = await readJson(req);
  const list = body && Array.isArray(body.codes) ? body.codes : null;
  if (!list) return sendJson(res, 400, { error: 'invalid_request', message: 'codes[] required' });
  const codes = list
    .map((c) => String(c == null ? '' : c).trim())
    .filter((c) => c.length >= 8 && c.length <= 256)
    .slice(0, MAX_PAIRING_CODES);
  ctx.oauth.setPairingCodes(userId, codes, ctx.config.pairingTtlMs);
  console.log('relay: pairing codes registered uid=%s count=%d', userId, codes.length);
  return sendJson(res, 200, { ok: true, count: codes.length });
}

// A device enrols by presenting its owner's Firebase ID token; the central authority verifies the
// token and Premium, and the relay issues a short-lived per-device credential bound to that uid.
// The device re-enrols before it expires, so access stops when Premium lapses or is revoked.
async function handleDeviceEnroll(req, res, ctx) {
  const token = bearerOrQueryToken(req, new URL(req.url, ctx.config.publicUrl));
  const info = await checkPremium(ctx.config, token);
  if (info.error === 'backend_not_configured') return sendJson(res, 501, { error: 'enrollment_not_configured' });
  if (info.error === 'unauthorized' || info.error === 'no_token') return sendJson(res, 401, { error: 'invalid_token' });
  if (info.error) return sendJson(res, 503, { error: 'try_again' });
  if (!info.premium) {
    console.log('relay: enroll refused (not premium) uid=%s', info.uid);
    return sendJson(res, 403, { error: 'not_premium' });
  }
  console.log('relay: enroll ok uid=%s', info.uid);
  const { credential, expiresAt } = ctx.hub.enrollDevice(info.uid, ctx.config.enrollTtlMs);
  return sendJson(res, 200, {
    device_token: credential,
    expires_in: Math.max(0, Math.floor((expiresAt - Date.now()) / 1000)),
    uid: info.uid,
  });
}

// The device registers the owner's Google email(s) so "Sign in with Google" maps to this account
// without editing RELAY_GOOGLE_USERS. The app only sends these for a Premium account. Authenticated
// by the device token, so a user links only their own email.
const MAX_GOOGLE_EMAILS = 8;
async function handleDeviceGoogleEmails(req, res, ctx) {
  const token = bearerOrQueryToken(req, new URL(req.url, ctx.config.publicUrl));
  const userId = ctx.hub.userForDeviceToken(token);
  if (!userId) return sendJson(res, 401, { error: 'invalid_device_token' });
  const body = await readJson(req);
  const list = body && Array.isArray(body.emails) ? body.emails : null;
  if (!list) return sendJson(res, 400, { error: 'invalid_request', message: 'emails[] required' });
  const emails = list
    .map((e) => String(e == null ? '' : e).trim().toLowerCase())
    .filter((e) => e.length <= 320 && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e))
    .slice(0, MAX_GOOGLE_EMAILS);
  ctx.oauth.setGoogleEmails(userId, emails);
  return sendJson(res, 200, { ok: true, count: emails.length });
}

// ---------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------

function bearerOrQueryToken(req, url) {
  const header = req.headers['authorization'];
  if (header && header.startsWith('Bearer ')) return header.slice('Bearer '.length).trim();
  return url.searchParams.get('device_token') || '';
}

function jsonRpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } };
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

function sendHtml(res, status, html) {
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end('<!doctype html><meta charset="utf-8"><title>TermiNow MCP relay</title>' + html);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const text = await readBody(req);
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function readForm(req) {
  const text = await readBody(req);
  const ct = req.headers['content-type'] || '';
  if (ct.includes('application/json')) {
    try {
      return JSON.parse(text) || {};
    } catch {
      return {};
    }
  }
  return Object.fromEntries(new URLSearchParams(text));
}

function escapeHtml(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// The sign-in options a consent page offers, from what the relay is configured with.
function consentOpts(ctx, error) {
  return {
    error,
    googleEnabled: googleConfigured(ctx.config),
    firebase: backendConfigured(ctx.config) ? ctx.config.firebaseWeb : null,
    firebaseDisabled: !!ctx.config.googleSigninDisabled,
  };
}

function consentPage(q, opts = {}) {
  const keys = ['client_id', 'redirect_uri', 'state', 'code_challenge', 'code_challenge_method', 'response_type', 'scope'];
  const hidden = keys.map((k) => `<input type="hidden" name="${k}" value="${escapeHtml(q[k])}">`).join('\n');
  const error = opts.error ? `<p style="color:#b00">${escapeHtml(opts.error)}</p>` : '';

  // Primary sign-in: Firebase Google sign-in in the browser. It yields a Firebase ID token the
  // backend authority verifies for Premium. The original OAuth request is preserved in the hidden
  // fields and validated server-side (redirect_uri, PKCE); only the verified uid is trusted.
  let firebaseBlock = '';
  if (opts.firebase && opts.firebaseDisabled) {
    firebaseBlock = `
    <p style="padding:.6rem;border:1px solid #ddd;border-radius:.4rem;background:#fafafa;color:#555">
      Sign in with Google is temporarily unavailable. Please use a pairing code below.</p>`;
  } else if (opts.firebase && opts.firebase.apiKey) {
    const cfg = JSON.stringify({ apiKey: opts.firebase.apiKey, authDomain: opts.firebase.authDomain, projectId: opts.firebase.projectId });
    firebaseBlock = `
    <form id="fbform" method="post" action="/authorize">
      ${hidden}
      <input type="hidden" name="firebase_id_token" id="fbtok">
    </form>
    <button id="gbtn" type="button" style="padding:.6rem 1rem;font-size:1rem;border:1px solid #888;border-radius:.4rem;background:#fff;cursor:pointer">Sign in with Google</button>
    <p id="gerr" style="color:#b00"></p>
    <p style="color:#888;margin:1rem 0 .5rem">or enter a pairing code</p>
    <script src="https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js"></script>
    <script src="https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js"></script>
    <script>
      firebase.initializeApp(${cfg});
      document.getElementById('gbtn').addEventListener('click', async function () {
        var btn = this; btn.disabled = true;
        try {
          var r = await firebase.auth().signInWithPopup(new firebase.auth.GoogleAuthProvider());
          var t = await r.user.getIdToken();
          document.getElementById('fbtok').value = t;
          document.getElementById('fbform').submit();
        } catch (e) {
          document.getElementById('gerr').textContent = 'Sign-in failed: ' + ((e && e.message) || e);
          btn.disabled = false;
        }
      });
    </script>`;
  } else if (opts.googleEnabled) {
    const params = new URLSearchParams();
    for (const k of keys) if (q[k]) params.set(k, q[k]);
    firebaseBlock = `
    <a href="/oauth/google/start?${escapeHtml(params.toString())}"
       style="display:inline-block;padding:.6rem 1rem;font-size:1rem;border:1px solid #888;border-radius:.4rem;text-decoration:none;color:#111">
      Sign in with Google</a>
    <p style="color:#888;margin:1rem 0 .5rem">or enter a pairing code</p>`;
  }
  return `
  <div style="font-family:system-ui;max-width:28rem;margin:4rem auto">
    <h1>Connect to TermiNow</h1>
    <p>Let this AI client use your shared sessions.</p>
    ${error}
    ${firebaseBlock}
    <form method="post" action="/authorize">
      ${hidden}
      <input name="pairing_code" placeholder="Pairing code"
        style="width:100%;padding:.6rem;font-size:1rem;margin:.5rem 0">
      <button type="submit" style="padding:.6rem 1rem;font-size:1rem">Allow access</button>
    </form>
  </div>`;
}

// Start when run directly.
if (import.meta.url === `file://${process.argv[1]}`) {
  const config = loadConfig();
  const server = createServer(config);
  server.listen(config.port, config.host, () => {
    // eslint-disable-next-line no-console
    console.log(`TermiNow MCP relay on http://${config.host}:${config.port} (public ${config.publicUrl})`);
  });
}
