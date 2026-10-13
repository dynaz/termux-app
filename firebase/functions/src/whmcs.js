'use strict';

const crypto = require('crypto');
const { TermiNowError } = require('./errors');

/**
 * The WHMCS side of TermiNow: whmcs/terminow/play_sync.php. Every request is signed with
 * HMAC-SHA256 over "<timestamp>.<body>" with the shared secret WHMCS_SYNC_SECRET, and WHMCS
 * refuses requests older than five minutes.
 */
class WhmcsClient {
  constructor(url, secret, fetchImpl = fetch) {
    this.url = url;
    this.secret = secret;
    this.fetch = fetchImpl;
  }

  static sign(secret, timestamp, body) {
    return crypto.createHmac('sha256', secret).update(timestamp + '.' + body).digest('hex');
  }

  async post(payload) {
    const body = JSON.stringify(payload);
    const timestamp = String(Math.floor(Date.now() / 1000));
    let response;
    try {
      response = await this.fetch(this.url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-TermiNow-Timestamp': timestamp,
          'X-TermiNow-Signature': WhmcsClient.sign(this.secret, timestamp, body),
        },
        body,
        signal: AbortSignal.timeout(20000),
      });
    } catch (e) {
      throw new TermiNowError('WHMCS_UNAVAILABLE', 'WHMCS could not be reached', true);
    }
    let answer = {};
    try {
      answer = await response.json();
    } catch (e) {
      throw new TermiNowError('WHMCS_INVALID_RESPONSE', `WHMCS answered with HTTP ${response.status} and no JSON`, true);
    }
    if (!response.ok) {
      const code = typeof answer.code === 'string' && /^[A-Z_]{3,40}$/.test(answer.code) ? answer.code : 'WHMCS_ERROR';
      throw new TermiNowError(code, String(answer.error || `WHMCS answered with HTTP ${response.status}`).slice(0, 300), response.status >= 500);
    }
    return answer;
  }

  /** Create or update the client and the Premium service of a TermiNow account. Idempotent by uid and version. */
  sync(state) {
    return this.post({ action: 'sync', ...state });
  }

  /** Redeem a one-time link code from the client area for an account. */
  redeemLink({ uid, code }) {
    return this.post({ action: 'redeem_link', uid, code });
  }
}

module.exports = { WhmcsClient };
