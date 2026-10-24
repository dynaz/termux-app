// Local integration test for the relay: a stand-in TermiNow device connects over the outbound
// channel (as the Android connector will), an AI client authenticates with OAuth 2.1 + PKCE, and
// an MCP tools/call is routed to the device and back. This exercises the relay end to end on one
// machine; it does NOT test a real device, ChatGPT, or Claude Desktop (see README: those are
// pending remote end-to-end verification).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import crypto from 'node:crypto';
import { createServer } from '../src/server.js';

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function startRelay() {
  return new Promise((resolve) => {
    const config = {
      host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1',
      deviceTokens: { 'device-secret': 'user-1' },
      pairingCodes: { 'PAIR-123': 'user-1' },
      accessTokenTtlMs: 3600 * 1000, requestTimeoutMs: 5000, allowDynamicRegistration: true,
    };
    const server = createServer(config);
    server.listen(0, '127.0.0.1', () => {
      const base = `http://127.0.0.1:${server.address().port}`;
      server.config.publicUrl = base;
      resolve({ server, base });
    });
  });
}

function request(base, method, path, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, base);
    const req = http.request(url, { method, headers }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// A stand-in device: hold the SSE stream open and answer MCP requests like TerminowMcpBridgeMcpServer.
function connectFakeDevice(base, deviceToken, answer) {
  const url = new URL('/device/stream', base);
  url.searchParams.set('device_token', deviceToken);
  const req = http.get(url, (res) => {
    let buffer = '';
    res.on('data', (chunk) => {
      buffer += chunk;
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const line = frame.split('\n').find((l) => l.startsWith('data: '));
        if (!line) continue;
        const { rid, message } = JSON.parse(line.slice('data: '.length));
        const response = answer(message);
        const payload = JSON.stringify({ rid, response });
        const post = http.request(new URL('/device/rpc', base),
          { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${deviceToken}`, 'Content-Length': Buffer.byteLength(payload) } });
        post.end(payload);
      }
    });
  });
  return req;
}

async function getAccessToken(base, pairingCode = 'PAIR-123') {
  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const form = new URLSearchParams({
    client_id: 'static-client', redirect_uri: 'https://client.example/cb', state: 'xyz',
    code_challenge: challenge, code_challenge_method: 'S256', response_type: 'code', pairing_code: pairingCode,
  }).toString();
  const authorize = await request(base, 'POST', '/authorize',
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(form) }, body: form });
  assert.equal(authorize.status, 302, 'authorize should redirect with a code');
  const code = new URL(authorize.headers.location).searchParams.get('code');
  assert.ok(code, 'a code is returned');
  const tokenForm = new URLSearchParams({
    grant_type: 'authorization_code', code, code_verifier: verifier, redirect_uri: 'https://client.example/cb',
  }).toString();
  const token = await request(base, 'POST', '/token',
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(tokenForm) }, body: tokenForm });
  assert.equal(token.status, 200);
  return JSON.parse(token.body).access_token;
}

test('OAuth metadata is published for discovery', async () => {
  const { server, base } = await startRelay();
  try {
    const prm = await request(base, 'GET', '/.well-known/oauth-protected-resource');
    assert.equal(prm.status, 200);
    const prmJson = JSON.parse(prm.body);
    assert.ok(prmJson.authorization_servers.length >= 1);
    // Path-aware discovery (RFC 9728) must also resolve for the /mcp resource.
    const prmPathAware = await request(base, 'GET', '/.well-known/oauth-protected-resource/mcp');
    assert.equal(prmPathAware.status, 200);
    assert.equal(JSON.parse(prmPathAware.body).resource, prmJson.resource);
    const asm = await request(base, 'GET', '/.well-known/oauth-authorization-server');
    assert.deepEqual(JSON.parse(asm.body).code_challenge_methods_supported, ['S256']);
  } finally {
    server.close();
  }
});

test('an unauthenticated MCP call is challenged with WWW-Authenticate', async () => {
  const { server, base } = await startRelay();
  try {
    const res = await request(base, 'POST', '/mcp',
      { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }) });
    assert.equal(res.status, 401);
    assert.match(res.headers['www-authenticate'] || '', /resource_metadata=/);
  } finally {
    server.close();
  }
});

test('an authenticated MCP call is routed to the connected device and back', async () => {
  const { server, base } = await startRelay();
  const device = connectFakeDevice(base, 'device-secret', (message) => {
    if (message.method === 'tools/list') {
      return { jsonrpc: '2.0', id: message.id, result: { tools: [{ name: 'sessions_list' }, { name: 'task_submit' }] } };
    }
    return { jsonrpc: '2.0', id: message.id, result: {} };
  });
  try {
    await new Promise((r) => setTimeout(r, 200)); // let the device stream register
    const accessToken = await getAccessToken(base);
    const call = JSON.stringify({ jsonrpc: '2.0', id: 7, method: 'tools/list' });
    const res = await request(base, 'POST', '/mcp',
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}`, 'Content-Length': Buffer.byteLength(call) }, body: call });
    assert.equal(res.status, 200);
    const json = JSON.parse(res.body);
    assert.equal(json.id, 7);
    assert.equal(json.result.tools.length, 2);
    assert.equal(json.result.tools[0].name, 'sessions_list');
  } finally {
    device.destroy();
    server.close();
  }
});

test('a wrong device token cannot open the device channel', async () => {
  const { server, base } = await startRelay();
  try {
    const res = await request(base, 'GET', '/device/stream?device_token=nope');
    assert.equal(res.status, 401);
  } finally {
    server.close();
  }
});

test('when no device is connected, a call fails cleanly rather than hanging forever', async () => {
  const { server, base } = await startRelay();
  try {
    const accessToken = await getAccessToken(base);
    const call = JSON.stringify({ jsonrpc: '2.0', id: 9, method: 'tools/list' });
    const res = await request(base, 'POST', '/mcp',
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}`, 'Content-Length': Buffer.byteLength(call) }, body: call });
    assert.equal(res.status, 200);
    assert.ok(JSON.parse(res.body).error, 'a JSON-RPC error is returned');
  } finally {
    server.close();
  }
});

test("an OAuth token for one user is routed only to that user's device", async () => {
  // Two users, each with their own device and pairing code.
  const config = {
    host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1',
    deviceTokens: { 'dev-1': 'user-1', 'dev-2': 'user-2' },
    pairingCodes: { 'PAIR-1': 'user-1', 'PAIR-2': 'user-2' },
    accessTokenTtlMs: 3600 * 1000, requestTimeoutMs: 5000, allowDynamicRegistration: true,
  };
  const server = createServer(config);
  await new Promise((r) => server.listen(0, '127.0.0.1', () => { server.config.publicUrl = `http://127.0.0.1:${server.address().port}`; r(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  let hit1 = 0, hit2 = 0;
  const d1 = connectFakeDevice(base, 'dev-1', (m) => { hit1++; return { jsonrpc: '2.0', id: m.id, result: { who: 'device-1' } }; });
  const d2 = connectFakeDevice(base, 'dev-2', (m) => { hit2++; return { jsonrpc: '2.0', id: m.id, result: { who: 'device-2' } }; });
  try {
    await new Promise((r) => setTimeout(r, 200));
    const at = await getAccessToken(base, 'PAIR-1'); // token bound to user-1
    const call = JSON.stringify({ jsonrpc: '2.0', id: 5, method: 'tools/list' });
    const res = await request(base, 'POST', '/mcp',
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${at}`, 'Content-Length': Buffer.byteLength(call) }, body: call });
    assert.equal(res.status, 200);
    assert.equal(JSON.parse(res.body).result.who, 'device-1', 'user-1 token must reach user-1 device');
    assert.equal(hit1, 1, 'user-1 device answered');
    assert.equal(hit2, 0, "user-2 device must NOT be reachable with user-1's token");
  } finally {
    d1.destroy(); d2.destroy(); server.close();
  }
});

// Phase 1: the device registers the pairing codes it shows in "Pair a client", so the consent
// screen accepts them without editing RELAY_PAIRING_CODES. Only its own device token may set them.
test('a device can register dynamic pairing codes that the consent screen then accepts', async () => {
  const { server, base } = await startRelay();
  try {
    const dynCode = 'DYN-ABCDEFGH';
    // Before registration, a code not in the env map is rejected at /authorize.
    const verifier = b64url(crypto.randomBytes(32));
    const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
    const form = new URLSearchParams({
      client_id: 'c', redirect_uri: 'https://client.example/cb',
      code_challenge: challenge, code_challenge_method: 'S256', response_type: 'code', pairing_code: dynCode,
    }).toString();
    const before = await request(base, 'POST', '/authorize',
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(form) }, body: form });
    assert.equal(before.status, 403, 'an unregistered code is not recognised');

    // The device registers the code with its device token.
    const reg = JSON.stringify({ codes: [dynCode] });
    const regRes = await request(base, 'POST', '/device/pairing-codes',
      { headers: { 'Content-Type': 'application/json', Authorization: 'Bearer device-secret', 'Content-Length': Buffer.byteLength(reg) }, body: reg });
    assert.equal(regRes.status, 200);
    assert.equal(JSON.parse(regRes.body).count, 1);

    // Now the same code is accepted and yields a working access token bound to user-1.
    const at = await getAccessToken(base, dynCode);
    assert.ok(at, 'an access token is issued for the dynamically registered code');
  } finally {
    server.close();
  }
});

test('a wrong device token cannot register pairing codes', async () => {
  const { server, base } = await startRelay();
  try {
    const reg = JSON.stringify({ codes: ['SOME-OTHER-CODE'] });
    const res = await request(base, 'POST', '/device/pairing-codes',
      { headers: { 'Content-Type': 'application/json', Authorization: 'Bearer not-a-device', 'Content-Length': Buffer.byteLength(reg) }, body: reg });
    assert.equal(res.status, 401);
  } finally {
    server.close();
  }
});

// Phase 2: "Sign in with Google" at the consent screen. The relay is a Google OAuth client; a
// signed-in email maps to a user via RELAY_GOOGLE_USERS or a device registration. These tests stub
// Google's token endpoint (globalThis.fetch) so no real Google call is made.
function startGoogleRelay(users = { 'owner@example.com': 'user-1' }) {
  return new Promise((resolve) => {
    const config = {
      host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1',
      deviceTokens: { 'device-secret': 'user-1' }, pairingCodes: {},
      accessTokenTtlMs: 3600 * 1000, requestTimeoutMs: 5000, allowDynamicRegistration: true,
      google: { clientId: 'gid', clientSecret: 'gsecret', redirectUri: 'http://127.0.0.1/oauth/google/callback', users },
    };
    const server = createServer(config);
    server.listen(0, '127.0.0.1', () => {
      const bPort = server.address().port;
      server.config.publicUrl = `http://127.0.0.1:${bPort}`;
      server.config.google.redirectUri = `http://127.0.0.1:${bPort}/oauth/google/callback`;
      resolve({ server, base: `http://127.0.0.1:${bPort}` });
    });
  });
}

function fakeIdToken(email, verified = true) {
  const payload = b64url(Buffer.from(JSON.stringify({ email, email_verified: verified })));
  return `h.${payload}.s`;
}

async function googleSignInToToken(base, email) {
  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const startQ = new URLSearchParams({
    client_id: 'c', redirect_uri: 'https://client.example/cb', state: 'xyz',
    code_challenge: challenge, code_challenge_method: 'S256', response_type: 'code', scope: 'mcp',
  }).toString();
  const start = await request(base, 'GET', '/oauth/google/start?' + startQ);
  assert.equal(start.status, 302, 'start redirects to Google');
  const gstate = new URL(start.headers.location).searchParams.get('state');
  assert.ok(gstate, 'a state is carried to Google');
  // Stub the Google token exchange for this email.
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ id_token: fakeIdToken(email) }) });
  try {
    const cb = await request(base, 'GET', `/oauth/google/callback?state=${gstate}&code=abc`);
    if (cb.status !== 302) return { consent: cb };
    const authCode = new URL(cb.headers.location).searchParams.get('code');
    const tokenForm = new URLSearchParams({
      grant_type: 'authorization_code', code: authCode, code_verifier: verifier, redirect_uri: 'https://client.example/cb',
    }).toString();
    const token = await request(base, 'POST', '/token',
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(tokenForm) }, body: tokenForm });
    return { token };
  } finally {
    globalThis.fetch = realFetch;
  }
}

test('the consent screen offers Sign in with Google when configured', async () => {
  const { server, base } = await startGoogleRelay();
  try {
    const q = new URLSearchParams({ client_id: 'c', redirect_uri: 'https://client.example/cb', code_challenge: 'x', response_type: 'code' }).toString();
    const page = await request(base, 'GET', '/authorize?' + q);
    assert.equal(page.status, 200);
    assert.match(page.body, /Sign in with Google/);
    assert.match(page.body, /\/oauth\/google\/start\?/);
  } finally { server.close(); }
});

test('a mapped Google account signs in and gets a working access token', async () => {
  const { server, base } = await startGoogleRelay();
  try {
    const { token } = await googleSignInToToken(base, 'owner@example.com');
    assert.equal(token.status, 200);
    assert.ok(JSON.parse(token.body).access_token, 'an access token is issued via Google login');
  } finally { server.close(); }
});

test('an unmapped Google account is refused, not signed in', async () => {
  const { server, base } = await startGoogleRelay();
  try {
    const { consent } = await googleSignInToToken(base, 'stranger@example.com');
    assert.ok(consent, 'the callback returns the consent page, not a redirect');
    assert.equal(consent.status, 200);
    assert.match(consent.body, /not linked to a TermiNow device/);
  } finally { server.close(); }
});

test('a device can register a Google email that then signs in', async () => {
  const { server, base } = await startGoogleRelay({}); // no static users
  try {
    const reg = JSON.stringify({ emails: ['owner@example.com'] });
    const regRes = await request(base, 'POST', '/device/google-emails',
      { headers: { 'Content-Type': 'application/json', Authorization: 'Bearer device-secret', 'Content-Length': Buffer.byteLength(reg) }, body: reg });
    assert.equal(regRes.status, 200);
    assert.equal(JSON.parse(regRes.body).count, 1);
    const { token } = await googleSignInToToken(base, 'owner@example.com');
    assert.equal(token.status, 200);
  } finally { server.close(); }
});

test('a wrong device token cannot register Google emails', async () => {
  const { server, base } = await startGoogleRelay();
  try {
    const reg = JSON.stringify({ emails: ['x@example.com'] });
    const res = await request(base, 'POST', '/device/google-emails',
      { headers: { 'Content-Type': 'application/json', Authorization: 'Bearer nope', 'Content-Length': Buffer.byteLength(reg) }, body: reg });
    assert.equal(res.status, 401);
  } finally { server.close(); }
});

// Phase A: per-user device enrollment and multi-tenant sign-in through the central Premium
// authority (cp.accmove.com). The authority is stubbed (globalThis.fetch): it maps a Firebase ID
// token to { uid, premium }, exactly as relay_check.php would after verifying the token.
import { DeviceHub } from '../src/deviceHub.js';

function startBackendRelay() {
  return new Promise((resolve) => {
    const config = {
      host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1',
      deviceTokens: {}, pairingCodes: {},
      accessTokenTtlMs: 3600 * 1000, requestTimeoutMs: 5000, allowDynamicRegistration: true,
      backend: { url: 'https://backend.test/relay_check.php', secret: 'shhhhhhhhhhhhhhhh' },
      enrollTtlMs: 65 * 60 * 1000,
    };
    const server = createServer(config);
    server.listen(0, '127.0.0.1', () => {
      server.config.publicUrl = `http://127.0.0.1:${server.address().port}`;
      resolve({ server, base: `http://127.0.0.1:${server.address().port}` });
    });
  });
}

// Stub the authority: tokenMap = { '<firebase token>': { uid, premium } }. Unknown tokens → 401.
function withAuthority(tokenMap, fn) {
  const real = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    const auth = (opts.headers && opts.headers.Authorization) || '';
    const tok = auth.replace(/^Bearer\s+/, '');
    const hit = tokenMap[tok];
    if (!hit) return { status: 401, ok: false, json: async () => ({ error: 'bad' }) };
    return { status: 200, ok: true, json: async () => ({ uid: hit.uid, email: hit.email || (hit.uid + '@x'), premium: !!hit.premium, exp: 2000000000 }) };
  };
  return Promise.resolve(fn()).finally(() => { globalThis.fetch = real; });
}

async function enroll(base, firebaseToken) {
  return request(base, 'POST', '/device/enroll',
    { headers: { Authorization: `Bearer ${firebaseToken}`, 'Content-Type': 'application/json', 'Content-Length': 2 }, body: '{}' });
}

test('a Premium user enrols and gets a working per-device credential', async () => {
  const { server, base } = await startBackendRelay();
  try {
    await withAuthority({ 'fb-alice': { uid: 'u-alice', premium: true } }, async () => {
      const res = await enroll(base, 'fb-alice');
      assert.equal(res.status, 200);
      const cred = JSON.parse(res.body).device_token;
      assert.ok(cred, 'a device credential is issued');
      // The credential opens the device channel (resolves to the user).
      let answered = 0;
      const dev = connectFakeDevice(base, cred, (m) => { answered++; return { jsonrpc: '2.0', id: m.id, result: { ok: true } }; });
      try {
        await new Promise((r) => setTimeout(r, 150));
        assert.ok(server.hub.hasDevice('u-alice'), 'the enrolled device is connected for its uid');
      } finally { dev.destroy(); }
    });
  } finally { server.close(); }
});

test('a non-Premium user is refused enrollment', async () => {
  const { server, base } = await startBackendRelay();
  try {
    await withAuthority({ 'fb-free': { uid: 'u-free', premium: false } }, async () => {
      const res = await enroll(base, 'fb-free');
      assert.equal(res.status, 403);
      assert.equal(JSON.parse(res.body).error, 'not_premium');
    });
  } finally { server.close(); }
});

test('an unverified Firebase token is refused enrollment', async () => {
  const { server, base } = await startBackendRelay();
  try {
    await withAuthority({}, async () => {
      const res = await enroll(base, 'forged');
      assert.equal(res.status, 401);
    });
  } finally { server.close(); }
});

test('two users are isolated: a sign-in reaches only its own device', async () => {
  const { server, base } = await startBackendRelay();
  try {
    await withAuthority({ 'fb-alice': { uid: 'u-alice', premium: true }, 'fb-bob': { uid: 'u-bob', premium: true } }, async () => {
      const credA = JSON.parse((await enroll(base, 'fb-alice')).body).device_token;
      const credB = JSON.parse((await enroll(base, 'fb-bob')).body).device_token;
      let hitA = 0, hitB = 0;
      const devA = connectFakeDevice(base, credA, (m) => { hitA++; return { jsonrpc: '2.0', id: m.id, result: { who: 'alice' } }; });
      const devB = connectFakeDevice(base, credB, (m) => { hitB++; return { jsonrpc: '2.0', id: m.id, result: { who: 'bob' } }; });
      try {
        await new Promise((r) => setTimeout(r, 150));
        // Alice authorizes an AI client via her Firebase sign-in at the consent screen.
        const verifier = b64url(crypto.randomBytes(32));
        const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
        const form = new URLSearchParams({ client_id: 'c', redirect_uri: 'https://client.example/cb', state: 's',
          code_challenge: challenge, code_challenge_method: 'S256', response_type: 'code', firebase_id_token: 'fb-alice' }).toString();
        const authz = await request(base, 'POST', '/authorize',
          { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(form) }, body: form });
        assert.equal(authz.status, 302, 'Alice signs in and gets an auth code');
        const authCode = new URL(authz.headers.location).searchParams.get('code');
        const tokenForm = new URLSearchParams({ grant_type: 'authorization_code', code: authCode, code_verifier: verifier, redirect_uri: 'https://client.example/cb' }).toString();
        const at = JSON.parse((await request(base, 'POST', '/token',
          { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(tokenForm) }, body: tokenForm })).body).access_token;
        const call = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
        const res = await request(base, 'POST', '/mcp',
          { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${at}`, 'Content-Length': Buffer.byteLength(call) }, body: call });
        assert.equal(JSON.parse(res.body).result.who, 'alice', "Alice's client reaches only Alice's device");
        assert.equal(hitB, 0, "Bob's device is never reached by Alice's client");
      } finally { devA.destroy(); devB.destroy(); }
    });
  } finally { server.close(); }
});

test('a non-Premium Firebase sign-in is refused at the consent screen', async () => {
  const { server, base } = await startBackendRelay();
  try {
    await withAuthority({ 'fb-free': { uid: 'u-free', premium: false } }, async () => {
      const form = new URLSearchParams({ client_id: 'c', redirect_uri: 'https://client.example/cb',
        code_challenge: 'x', code_challenge_method: 'S256', response_type: 'code', firebase_id_token: 'fb-free' }).toString();
      const res = await request(base, 'POST', '/authorize',
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(form) }, body: form });
      assert.equal(res.status, 403);
      assert.match(res.body, /does not have TermiNow Premium/);
    });
  } finally { server.close(); }
});

test('an expired device credential is rejected (Premium must be re-confirmed)', () => {
  const hub = new DeviceHub({ deviceTokens: {} });
  const { credential } = hub.enrollDevice('u-x', -1); // already expired
  assert.equal(hub.userForDeviceToken(credential), null, 'an expired credential does not authenticate');
  const live = hub.enrollDevice('u-y', 60000);
  assert.equal(hub.userForDeviceToken(live.credential), 'u-y');
  hub.revokeDevice(live.credential);
  assert.equal(hub.userForDeviceToken(live.credential), null, 'a revoked credential stops working');
});

// Temporary disable: when RELAY_GOOGLE_SIGNIN_DISABLED is set, the consent page hides the Google
// button behind a "temporarily unavailable" note and refuses a posted Firebase token, while the
// pairing code still works and RELAY_BACKEND_URL stays configured.
test('Google sign-in can be temporarily disabled with a message, pairing retained', async () => {
  const config = {
    host: '127.0.0.1', port: 0, publicUrl: 'http://127.0.0.1',
    deviceTokens: {}, pairingCodes: { 'PAIR-OK': 'user-1' },
    accessTokenTtlMs: 3600 * 1000, requestTimeoutMs: 5000, allowDynamicRegistration: true,
    backend: { url: 'https://backend.test/relay_check.php', secret: 'shhhhhhhhhhhhhhhh' },
    firebaseWeb: { apiKey: 'k', authDomain: 'd', projectId: 'p' },
    googleSigninDisabled: true, enrollTtlMs: 1000,
  };
  const server = createServer(config);
  await new Promise((r) => server.listen(0, '127.0.0.1', () => { server.config.publicUrl = `http://127.0.0.1:${server.address().port}`; r(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const q = new URLSearchParams({ client_id: 'c', redirect_uri: 'https://client.example/cb', code_challenge: 'x', response_type: 'code' }).toString();
    const page = await request(base, 'GET', '/authorize?' + q);
    assert.match(page.body, /temporarily unavailable/);
    assert.ok(!/gstatic\.com\/firebasejs/.test(page.body), 'the Firebase SDK is not loaded while disabled');
    assert.match(page.body, /Pairing code/, 'the pairing code field remains');
    // A posted Firebase token is refused while disabled.
    const form = new URLSearchParams({ client_id: 'c', redirect_uri: 'https://client.example/cb',
      code_challenge: 'x', code_challenge_method: 'S256', response_type: 'code', firebase_id_token: 'fb' }).toString();
    const res = await request(base, 'POST', '/authorize',
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(form) }, body: form });
    assert.equal(res.status, 503);
  } finally { server.close(); }
});
