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
