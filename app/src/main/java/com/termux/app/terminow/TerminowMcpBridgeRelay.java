package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The outbound connector to the TermiNow MCP relay. TermiNow dials OUT and holds the connection
 * open, so the relay never connects to the device and the device's local bridge is never publicly
 * exposed. The connector reads MCP JSON-RPC requests the relay streams down (Server-Sent Events),
 * answers each with the in-process {@link TerminowMcpBridgeMcpServer} of the relay's paired client,
 * and posts the reply back up. On any drop it reconnects with a bounded backoff.
 *
 * <p>The relay authenticates the AI client with OAuth and the device with its device token; this
 * connector carries only the device token and the standard MCP messages, never the device's custom
 * local handshake. Every tool call is still authorized in {@link TerminowMcpBridge}, so the relay
 * can never reach a session the user did not share.</p>
 */
public final class TerminowMcpBridgeRelay {

    private static final String LOG_TAG = "TerminowMcpBridgeRelay";
    private static final int READ_TIMEOUT_MS = 60_000;   // the relay pings well within this
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final long BACKOFF_START_MS = 2_000;
    private static final long BACKOFF_MAX_MS = 30_000;

    /** Builds the MCP handler for the relay's paired client; the manager supplies this. */
    public interface HandlerFactory {
        @NonNull TerminowAiBridgeServer.Handler handler();
    }

    /** Supplies the pairing codes to register with the relay (the tokens shown in "Pair a client"). */
    public interface CodesProvider {
        @NonNull java.util.List<String> pairingCodes();
    }

    /** Re-enrolls this device and returns a fresh device credential, or {@code null} if it cannot. */
    public interface Reenroller {
        @Nullable String reenroll();
    }

    /** The relay rejected the device credential (expired/invalid); re-enrol rather than retry it. */
    private static final class CredentialRejected extends IOException {
        CredentialRejected(String message) { super(message); }
    }

    /** After this many re-enrolments without a successful connection, back off to avoid a loop. */
    private static final int MAX_REENROLL_BEFORE_BACKOFF = 5;

    public enum State { OFF, CONNECTING, CONNECTED, ERROR }

    private final HandlerFactory mFactory;
    @Nullable private final CodesProvider mCodes;
    @Nullable private final Reenroller mReenroll;
    private volatile String mBaseUrl = "";
    private volatile String mDeviceToken = "";
    private volatile boolean mRunning;
    @Nullable private volatile Thread mThread;
    @Nullable private volatile HttpURLConnection mStream;
    private volatile State mState = State.OFF;
    @Nullable private volatile String mLastError;
    // Bumped on every start()/stop(). Each loop thread captures its generation and exits as soon as a
    // newer one supersedes it, so a fresh start() can never leave an old thread reconnecting in
    // parallel (which previously opened overlapping streams — a connect/disconnect storm).
    private volatile int mGeneration;

    public TerminowMcpBridgeRelay(@NonNull HandlerFactory factory) {
        this(factory, null, null);
    }

    private final java.util.concurrent.ScheduledExecutorService mRepush =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TerminowMcpBridge repush");
            t.setDaemon(true);
            return t;
        });

    public TerminowMcpBridgeRelay(@NonNull HandlerFactory factory, @Nullable CodesProvider codes) {
        this(factory, codes, null);
    }

    public TerminowMcpBridgeRelay(@NonNull HandlerFactory factory, @Nullable CodesProvider codes, @Nullable Reenroller reenroll) {
        mFactory = factory;
        mCodes = codes;
        mReenroll = reenroll;
        // Re-register the pairing codes periodically so the relay (which expires dynamic codes) keeps
        // them valid during a long connection. A no-op while disconnected; a drop/restart re-registers
        // on reconnect. Runs for the connector's lifetime.
        mRepush.scheduleWithFixedDelay(this::refreshPairingCodes, 45, 45, java.util.concurrent.TimeUnit.MINUTES);
    }

    public synchronized void start(@NonNull String baseUrl, @NonNull String deviceToken) {
        stop();
        mBaseUrl = baseUrl.replaceAll("/+$", "");
        mDeviceToken = deviceToken;
        if (mBaseUrl.isEmpty() || mDeviceToken.isEmpty()) {
            mState = State.OFF;
            return;
        }
        mRunning = true;
        final int gen = ++mGeneration; // this thread's generation; a later start()/stop() supersedes it
        mState = State.CONNECTING;
        Thread thread = new Thread(() -> loop(gen), "TerminowMcpBridge relay");
        thread.setDaemon(true);
        mThread = thread;
        thread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        mGeneration++; // invalidate any running loop so it cannot reconnect after we tear it down
        mState = State.OFF;
        HttpURLConnection stream = mStream;
        if (stream != null) stream.disconnect();
        mStream = null;
        Thread thread = mThread;
        if (thread != null) thread.interrupt();
        mThread = null;
    }

    @NonNull
    public State state() {
        return mState;
    }

    @Nullable
    public String lastError() {
        return mLastError;
    }

    /**
     * True if a live loop already owns this exact base URL + credential. Callers use it to avoid a
     * redundant {@link #start} that would tear down a healthy (or self-reconnecting) connection and
     * cause needless churn, e.g. when the activity is recreated and re-runs startIfEnabled.
     */
    public synchronized boolean isActiveWith(@NonNull String baseUrl, @NonNull String deviceToken) {
        return mRunning && mBaseUrl.equals(baseUrl.replaceAll("/+$", "")) && mDeviceToken.equals(deviceToken);
    }

    /** This loop is still the current one: running and not superseded by a newer start()/stop(). */
    private boolean alive(int gen) {
        return mRunning && gen == mGeneration;
    }

    private void loop(int gen) {
        long backoff = BACKOFF_START_MS;
        int reenrollTries = 0; // consecutive re-enrolments without a successful connection, bounds the loop
        while (alive(gen)) {
            try {
                runStream();
                backoff = BACKOFF_START_MS; // a clean (connected) session ended: reset
                reenrollTries = 0;
            } catch (CredentialRejected e) {
                if (!alive(gen)) break;
                mState = State.ERROR;
                mLastError = String.valueOf(e.getMessage());
                // Re-enrol for a fresh credential instead of retrying the rejected one. Bounded: after
                // MAX tries without a good connection, stop re-enrolling and back off (no tight loop).
                String fresh = (mReenroll != null && reenrollTries < MAX_REENROLL_BEFORE_BACKOFF) ? safeReenroll() : null;
                reenrollTries++;
                if (fresh != null && !fresh.isEmpty()) {
                    mDeviceToken = fresh;
                    backoff = BACKOFF_START_MS; // reconnect promptly with the new credential
                } else {
                    backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                }
            } catch (IOException | RuntimeException e) {
                if (!alive(gen)) break;
                mState = State.ERROR;
                mLastError = String.valueOf(e.getMessage());
                Logger.logDebug(LOG_TAG, "Relay stream ended: " + e.getMessage());
            }
            if (!alive(gen)) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
        // Only the current generation owns the state; a superseded thread must not clobber the
        // state (or stream) of the newer one that replaced it.
        if (gen == mGeneration) mState = State.OFF;
    }

    /** Re-enrol for a fresh device credential; never throws, returns {@code null} on any failure. */
    @Nullable
    private String safeReenroll() {
        try {
            return mReenroll != null ? mReenroll.reenroll() : null;
        } catch (RuntimeException e) {
            Logger.logDebug(LOG_TAG, "Re-enrol failed: " + e.getMessage());
            return null;
        }
    }

    private void runStream() throws IOException {
        URL url = new URL(mBaseUrl + "/device/stream?device_token=" + urlEncode(mDeviceToken));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + mDeviceToken);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        mStream = connection;
        int code = connection.getResponseCode();
        if (code != 200) {
            mState = State.ERROR;
            mLastError = "The relay refused the device connection (HTTP " + code + ")";
            // 401 = our device credential is invalid/expired (e.g. the relay forgot it on a restart):
            // signal the loop to re-enrol for a fresh one rather than retry the dead credential.
            if (code == 401) throw new CredentialRejected(mLastError);
            throw new IOException(mLastError);
        }
        mState = State.CONNECTED;
        mLastError = null;
        // Register the current pairing codes so the relay's consent screen accepts them without the
        // operator editing RELAY_PAIRING_CODES; re-sent on every (re)connect.
        pushPairingCodes();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (mRunning && (line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) dispatch(line.substring("data: ".length()));
            }
        } finally {
            connection.disconnect();
            if (mStream == connection) mStream = null;
        }
    }

    /** Answer one relayed request with the client's MCP handler and post the reply back. */
    private void dispatch(@NonNull String data) {
        try {
            JSONObject frame = new JSONObject(data);
            String rid = frame.optString("rid");
            JSONObject message = frame.optJSONObject("message");
            if (rid.isEmpty() || message == null) return;
            JSONObject response = mFactory.handler().handle(message);
            if (response == null) return; // a notification has no reply
            postResponse(rid, response);
        } catch (org.json.JSONException e) {
            Logger.logDebug(LOG_TAG, "Ignoring a malformed relay frame");
        }
    }

    private void postResponse(@NonNull String rid, @NonNull JSONObject response) {
        try {
            JSONObject payload = new JSONObject().put("rid", rid).put("response", response);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(mBaseUrl + "/device/rpc");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + mDeviceToken);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(CONNECT_TIMEOUT_MS);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            connection.getResponseCode();
            connection.disconnect();
        } catch (IOException | org.json.JSONException e) {
            Logger.logDebug(LOG_TAG, "Could not post a relay response: " + e.getMessage());
        }
    }

    /** Re-send the pairing codes to the relay, off the caller's thread. Call after pairing or revoking. */
    public void refreshPairingCodes() {
        if (!mRunning || mState != State.CONNECTED) return;
        Thread thread = new Thread(this::pushPairingCodes, "TerminowMcpBridge codes");
        thread.setDaemon(true);
        thread.start();
    }

    /** POST the current pairing codes to the relay. Best effort: a failure is retried on reconnect. */
    private void pushPairingCodes() {
        if (mCodes == null || mBaseUrl.isEmpty() || mDeviceToken.isEmpty()) return;
        try {
            org.json.JSONArray codes = new org.json.JSONArray();
            for (String code : mCodes.pairingCodes()) if (code != null && !code.isEmpty()) codes.put(code);
            byte[] body = new JSONObject().put("codes", codes).toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(mBaseUrl + "/device/pairing-codes");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + mDeviceToken);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(CONNECT_TIMEOUT_MS);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            connection.getResponseCode();
            connection.disconnect();
        } catch (IOException | org.json.JSONException e) {
            Logger.logDebug(LOG_TAG, "Could not register pairing codes: " + e.getMessage());
        }
    }

    @NonNull
    private static String urlEncode(@NonNull String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
