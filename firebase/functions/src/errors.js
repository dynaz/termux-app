'use strict';

/**
 * Errors with a stable code that the app shows, and a message without secrets. Codes are
 * documented in firebase/README.md.
 */
class TermiNowError extends Error {
  /**
   * @param {string} code Like PLAY_UNAVAILABLE.
   * @param {string} message Safe to show to the user and to log.
   * @param {boolean} transient Whether trying again later may work.
   */
  constructor(code, message, transient = false) {
    super(message);
    this.code = code;
    this.transient = transient;
  }
}

/** Remove things that look like purchase tokens, bearer tokens or keys from text that is logged. */
function redact(text) {
  return String(text)
    // Play purchase tokens are long dotted strings of letters, digits, - and _
    .replace(/[A-Za-z0-9_-]{20,}(\.[A-Za-z0-9_-]{8,})+/g, '[token]')
    .replace(/(bearer\s+)[A-Za-z0-9._~+/=-]{8,}/gi, '$1[redacted]')
    .replace(/(private_key|secret|signature|token)("?\s*[:=]\s*"?)[^\s",}]+/gi, '$1$2[redacted]');
}

module.exports = { TermiNowError, redact };
