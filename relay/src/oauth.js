// OAuth 2.1 for the relay, as the MCP authorization spec requires: the relay is the resource
// server, and it is its own authorization server here for a self-host. It publishes Protected
// Resource Metadata (RFC 9728) and Authorization Server Metadata (RFC 8414), runs the
// authorization-code flow with PKCE (S256 only), and supports Dynamic Client Registration
// (RFC 7591) for clients that still use it. Access tokens are opaque and bound to a user id.
//
// Storage is in memory: fine for a single-process self-host, but a multi-instance deployment must
// back these maps with shared storage (see README). No token is ever logged.

import crypto from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, mkdirSync } from 'node:fs';
import path from 'node:path';

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const randomToken = () => b64url(crypto.randomBytes(32));

function verifyPkce(verifier, challenge) {
  if (!verifier || !challenge) return false;
  const computed = b64url(crypto.createHash('sha256').update(verifier).digest());
  // Constant-time compare on equal-length buffers
  const a = Buffer.from(computed);
  const b = Buffer.from(challenge);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export class OAuth {
  constructor(config) {
    this.config = config;
    this.clients = new Map();        // client_id -> { redirectUris:Set }
    this.codes = new Map();          // code -> { userId, challenge, redirectUri, clientId, expiresAt }
    this.accessTokens = new Map();   // token -> { userId, expiresAt }
    this.refreshTokens = new Map();  // token -> { userId }
    // Persist OAuth tokens and registered clients so a relay restart does not invalidate a client's
    // session (otherwise ChatGPT must reconnect after every restart). In-memory only when unset.
    this.statePath = config && config.statePath ? path.join(config.statePath, 'oauth-state.json') : '';
    this._loadState();
    // Pairing codes a connected device registered for its user, in addition to the static env map.
    // This lets the app push the codes it shows in "Pair a client" so the user need not edit .env.
    this.dynamicCodes = new Map();   // pairing code -> userId
    // Google emails a connected device registered for its user, in addition to the static env map.
    this.dynamicEmails = new Map();  // Google email (lowercased) -> userId
    // In-flight "Sign in with Google" requests: the original client params kept across the Google
    // round-trip, keyed by an opaque state we send to Google and get back on the callback.
    this.googleStates = new Map();   // gstate -> { clientId, redirectUri, challenge, state, expiresAt }
  }

  _loadState() {
    if (!this.statePath) return;
    let data;
    try {
      data = JSON.parse(readFileSync(this.statePath, 'utf8'));
    } catch {
      return; // no state yet, or unreadable
    }
    const now = Date.now();
    for (const [t, e] of data.accessTokens || []) if (e && e.expiresAt > now) this.accessTokens.set(t, e);
    for (const [t, e] of data.refreshTokens || []) if (e) this.refreshTokens.set(t, e);
    for (const [id, uris] of data.clients || []) this.clients.set(id, { redirectUris: new Set(uris) });
  }

  _saveState() {
    if (!this.statePath) return;
    const data = {
      accessTokens: [...this.accessTokens.entries()],
      refreshTokens: [...this.refreshTokens.entries()],
      clients: [...this.clients.entries()].map(([id, c]) => [id, [...c.redirectUris]]),
    };
    try {
      mkdirSync(path.dirname(this.statePath), { recursive: true });
      const tmp = this.statePath + '.' + crypto.randomBytes(4).toString('hex');
      writeFileSync(tmp, JSON.stringify(data), { mode: 0o600 });
      renameSync(tmp, this.statePath);
    } catch {
      // Best effort; tokens still work in memory until the next restart.
    }
  }

  setGoogleEmails(userId, emails) {
    for (const [email, uid] of this.dynamicEmails) if (uid === userId) this.dynamicEmails.delete(email);
    for (const email of emails) {
      const e = String(email || '').trim().toLowerCase();
      if (e) this.dynamicEmails.set(e, userId);
    }
  }

  // The user id a verified Google email belongs to, from the static env map or a device registration.
  userForEmail(email) {
    const e = String(email || '').trim().toLowerCase();
    if (!e) return null;
    return this.config.google?.users?.[e] || this.dynamicEmails.get(e) || null;
  }

  // Stash the client's OAuth params for the Google round-trip; returns the opaque state to send.
  startGoogleLogin({ clientId, redirectUri, challenge, state }) {
    const gstate = randomToken();
    this.googleStates.set(gstate, { clientId, redirectUri, challenge, state, expiresAt: Date.now() + 10 * 60 * 1000 });
    return gstate;
  }

  // Take back the params for a Google callback, once. Null if unknown or expired.
  takeGoogleState(gstate) {
    const entry = this.googleStates.get(gstate);
    if (!entry) return null;
    this.googleStates.delete(gstate);
    if (entry.expiresAt < Date.now()) return null;
    return entry;
  }

  /**
   * Replace the dynamic pairing codes of one user with the given set. Only a device authenticated
   * with that user's device token reaches this (via the device endpoint), so a user can set codes
   * only for themselves. The static env codes are never touched.
   */
  setPairingCodes(userId, codes, ttlMs) {
    // Ownership: replace only this user's own dynamic codes. Expiry: each code lives for ttlMs, so
    // codes of a device that stopped re-registering (e.g. disconnected) stop working on their own.
    for (const [code, e] of this.dynamicCodes) if (e.userId === userId) this.dynamicCodes.delete(code);
    const expiresAt = Date.now() + (ttlMs > 0 ? ttlMs : 2 * 60 * 60 * 1000);
    for (const code of codes) {
      const c = String(code || '').trim();
      if (c) this.dynamicCodes.set(c, { userId, expiresAt });
    }
  }

  // The user of a dynamic pairing code, if present and not expired. Expired entries are dropped.
  userForDynamicCode(code) {
    const e = this.dynamicCodes.get(code);
    if (!e) return null;
    if (e.expiresAt < Date.now()) {
      this.dynamicCodes.delete(code);
      return null;
    }
    return e.userId;
  }

  protectedResourceMetadata() {
    return {
      resource: this.config.publicUrl + '/mcp',
      authorization_servers: [this.config.publicUrl],
      bearer_methods_supported: ['header'],
      scopes_supported: ['mcp'],
    };
  }

  authorizationServerMetadata() {
    const base = this.config.publicUrl;
    const meta = {
      issuer: base,
      authorization_endpoint: base + '/authorize',
      token_endpoint: base + '/token',
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      scopes_supported: ['mcp'],
    };
    if (this.config.allowDynamicRegistration) meta.registration_endpoint = base + '/register';
    return meta;
  }

  // RFC 7591 dynamic client registration: accept the client's redirect URIs.
  register(body) {
    const clientId = 'mcp-' + randomToken().slice(0, 24);
    const redirectUris = Array.isArray(body.redirect_uris) ? body.redirect_uris : [];
    this.clients.set(clientId, { redirectUris: new Set(redirectUris) });
    this._saveState();
    return {
      client_id: clientId,
      redirect_uris: redirectUris,
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
    };
  }

  knownRedirect(clientId, redirectUri) {
    const client = this.clients.get(clientId);
    // A client registered via DCR must use a registered redirect; an unregistered (static) client
    // id is allowed its stated redirect, which the consent screen shows to the user.
    if (client) return client.redirectUris.has(redirectUri);
    return !!redirectUri;
  }

  // The user approves at /authorize by entering a pairing code; a valid code identifies the user.
  issueCode({ pairingCode, clientId, redirectUri, challenge }) {
    const userId = this.config.pairingCodes[pairingCode] || this.userForDynamicCode(pairingCode);
    if (!userId) return null;
    return this.issueCodeForUser(userId, { clientId, redirectUri, challenge });
  }

  // Issue an authorization code for a user already identified (by pairing code or Google login).
  issueCodeForUser(userId, { clientId, redirectUri, challenge }) {
    if (!userId) return null;
    if (!challenge) return null; // PKCE is required
    const code = randomToken();
    this.codes.set(code, { userId, challenge, redirectUri, clientId, expiresAt: Date.now() + 5 * 60 * 1000 });
    return code;
  }

  // authorization_code grant with PKCE.
  exchangeCode({ code, verifier, redirectUri }) {
    const entry = this.codes.get(code);
    if (!entry) return { error: 'invalid_grant' };
    this.codes.delete(code);
    if (entry.expiresAt < Date.now()) return { error: 'invalid_grant' };
    if (entry.redirectUri && redirectUri && entry.redirectUri !== redirectUri) return { error: 'invalid_grant' };
    if (!verifyPkce(verifier, entry.challenge)) return { error: 'invalid_grant' };
    return this.issueTokens(entry.userId);
  }

  refresh(refreshToken) {
    const entry = this.refreshTokens.get(refreshToken);
    if (!entry) return { error: 'invalid_grant' };
    this.refreshTokens.delete(refreshToken);
    return this.issueTokens(entry.userId);
  }

  issueTokens(userId) {
    const accessToken = randomToken();
    const refreshToken = randomToken();
    this.accessTokens.set(accessToken, { userId, expiresAt: Date.now() + this.config.accessTokenTtlMs });
    this.refreshTokens.set(refreshToken, { userId });
    this._saveState();
    return {
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: Math.floor(this.config.accessTokenTtlMs / 1000),
      refresh_token: refreshToken,
      scope: 'mcp',
    };
  }

  // The user id a bearer token is for, or null. Expired tokens are rejected and dropped.
  userForBearer(authorizationHeader) {
    if (!authorizationHeader || !authorizationHeader.startsWith('Bearer ')) return null;
    const token = authorizationHeader.slice('Bearer '.length).trim();
    const entry = this.accessTokens.get(token);
    if (!entry) return null;
    if (entry.expiresAt < Date.now()) {
      this.accessTokens.delete(token);
      return null;
    }
    return entry.userId;
  }

  // Revoke every token of a user, for an emergency stop from the device or an admin.
  revokeUser(userId) {
    for (const [token, entry] of this.accessTokens) if (entry.userId === userId) this.accessTokens.delete(token);
    for (const [token, entry] of this.refreshTokens) if (entry.userId === userId) this.refreshTokens.delete(token);
    this._saveState();
  }
}
