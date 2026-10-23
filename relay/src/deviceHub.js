// The device channel: TermiNow dials OUT to the relay and holds it open, so the relay never
// connects to the device and the device is never publicly exposed. The channel is plain HTTPS:
//
//   GET  /device/stream   (device -> relay, held open)  Server-Sent Events carrying requests
//   POST /device/rpc      (device -> relay)              the device's responses to those requests
//
// A device authenticates with its device token, which maps to a user id. The relay forwards MCP
// JSON-RPC to a connected device of the request's user and correlates the reply by a relay id
// (rid). If the device is gone or slow, the pending request fails cleanly; a reconnecting device
// gets a fresh stream and the old pending requests are rejected so the client can retry.

import crypto from 'node:crypto';

export class DeviceHub {
  constructor(config) {
    this.config = config;
    this.byUser = new Map();   // userId -> Map(connId -> connection)
    this.ridCounter = 0;
  }

  userForDeviceToken(token) {
    return (token && this.config.deviceTokens[token]) || null;
  }

  // Register an SSE stream for a device. Returns a disposer to call when the request closes.
  addStream(userId, res) {
    const connId = crypto.randomBytes(8).toString('hex');
    const conn = { connId, userId, res, pending: new Map(), alive: true };
    let conns = this.byUser.get(userId);
    if (!conns) {
      conns = new Map();
      this.byUser.set(userId, conns);
    }
    conns.set(connId, conn);

    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
    });
    res.write('retry: 2000\n\n');            // tell the client how soon to reconnect
    res.write(': connected\n\n');

    const keepAlive = setInterval(() => {
      if (conn.alive) res.write(': ping\n\n');
    }, 15000);

    const dispose = () => {
      if (!conn.alive) return;
      conn.alive = false;
      clearInterval(keepAlive);
      conns.delete(connId);
      if (conns.size === 0) this.byUser.delete(userId);
      for (const pending of conn.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error('The device disconnected before it answered'));
      }
      conn.pending.clear();
    };
    return dispose;
  }

  hasDevice(userId) {
    const conns = this.byUser.get(userId);
    return !!conns && conns.size > 0;
  }

  // Send a JSON-RPC message to one of the user's devices and await its response.
  callDevice(userId, message) {
    const conns = this.byUser.get(userId);
    if (!conns || conns.size === 0) {
      return Promise.reject(new Error('No TermiNow device is connected for this account'));
    }
    const conn = conns.values().next().value; // one device per user in this self-host model
    const rid = 'r' + (++this.ridCounter);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        conn.pending.delete(rid);
        reject(new Error('The device did not answer in time'));
      }, this.config.requestTimeoutMs);
      conn.pending.set(rid, { resolve, reject, timer });
      try {
        conn.res.write(`data: ${JSON.stringify({ rid, message })}\n\n`);
      } catch (e) {
        clearTimeout(timer);
        conn.pending.delete(rid);
        reject(new Error('Could not reach the device'));
      }
    });
  }

  // A device posts a response for a relay id it received on its stream.
  deliverResponse(userId, body) {
    const conns = this.byUser.get(userId);
    if (!conns) return false;
    const { rid, response } = body || {};
    for (const conn of conns.values()) {
      const pending = conn.pending.get(rid);
      if (pending) {
        clearTimeout(pending.timer);
        conn.pending.delete(rid);
        pending.resolve(response);
        return true;
      }
    }
    return false;
  }
}
