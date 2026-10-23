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

  if (path === '/mcp') return handleMcp(req, res, ctx);

  if (req.method === 'GET' && path === '/device/stream') return handleDeviceStream(req, res, ctx, url);
  if (req.method === 'POST' && path === '/device/rpc') return handleDeviceRpc(req, res, ctx);

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
    return sendHtml(res, 200, consentPage(q));
  }
  if (req.method === 'POST') {
    const form = await readForm(req);
    const redirectUri = form.redirect_uri;
    if (!redirectUri || !ctx.oauth.knownRedirect(form.client_id, redirectUri))
      return sendHtml(res, 400, '<p>Unknown redirect_uri.</p>');
    const code = ctx.oauth.issueCode({
      pairingCode: (form.pairing_code || '').trim(),
      clientId: form.client_id,
      redirectUri,
      challenge: form.code_challenge,
    });
    if (!code) return sendHtml(res, 403, consentPage(form, 'That pairing code was not recognised. Check it in TermiNow.'));
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
    return sendJson(res, 200, response);
  } catch (e) {
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

function consentPage(q, errorText) {
  const hidden = ['client_id', 'redirect_uri', 'state', 'code_challenge', 'code_challenge_method', 'response_type', 'scope']
    .map((k) => `<input type="hidden" name="${k}" value="${escapeHtml(q[k])}">`).join('\n');
  const error = errorText ? `<p style="color:#b00">${escapeHtml(errorText)}</p>` : '';
  return `
  <div style="font-family:system-ui;max-width:28rem;margin:4rem auto">
    <h1>Connect to TermiNow</h1>
    <p>Enter the pairing code from TermiNow (MCP Bridge → Pair a client) to let this AI client use your shared sessions.</p>
    ${error}
    <form method="post" action="/authorize">
      ${hidden}
      <input name="pairing_code" placeholder="Pairing code" autofocus
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
