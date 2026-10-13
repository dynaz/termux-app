'use strict';

const { GoogleAuth } = require('google-auth-library');
const { TermiNowError } = require('./errors');

const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/';

/**
 * The Google Play Developer API, with the identity of the runtime service account of the
 * functions (no key file). That account must be invited in Play Console with "View financial
 * data" and "Manage orders and subscriptions".
 */
class PlayClient {
  constructor(packageName, auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] })) {
    this.packageName = packageName;
    this.auth = auth;
  }

  async call(method, path, body) {
    let client;
    try {
      client = await this.auth.getClient();
    } catch (e) {
      throw new TermiNowError('PLAY_AUTH', 'The server could not sign in to Google Play', true);
    }
    let response;
    try {
      response = await client.request({
        url: API + encodeURIComponent(this.packageName) + path,
        method,
        data: body,
        validateStatus: () => true,
        timeout: 15000,
      });
    } catch (e) {
      throw new TermiNowError('PLAY_UNAVAILABLE', 'Google Play could not be reached', true);
    }
    const status = response.status;
    if (status >= 200 && status < 300) return response.data || {};
    if (status === 400 || status === 404 || status === 410)
      throw new TermiNowError('PURCHASE_NOT_FOUND', 'Google Play does not know this purchase for TermiNow');
    if (status === 401 || status === 403)
      throw new TermiNowError('PLAY_PERMISSION', 'The server is not allowed to check purchases in Play Console', true);
    throw new TermiNowError('PLAY_UNAVAILABLE', `Google Play answered with HTTP ${status}`, true);
  }

  getSubscription(token) {
    return this.call('GET', '/purchases/subscriptionsv2/tokens/' + encodeURIComponent(token));
  }

  acknowledge(productId, token) {
    return this.call('POST', '/purchases/subscriptions/' + encodeURIComponent(productId) + '/tokens/' +
      encodeURIComponent(token) + ':acknowledge', {});
  }
}

module.exports = { PlayClient };
