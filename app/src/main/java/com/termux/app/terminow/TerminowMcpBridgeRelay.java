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

    public enum State { OFF, CONNECTING, CONNECTED, ERROR }

    private final HandlerFactory mFactory;
    private volatile String mBaseUrl = "";
    private volatile String mDeviceToken = "";
    private volatile boolean mRunning;
    @Nullable private volatile Thread mThread;
    @Nullable private volatile HttpURLConnection mStream;
    private volatile State mState = State.OFF;
    @Nullable private volatile String mLastError;

    public TerminowMcpBridgeRelay(@NonNull HandlerFactory factory) {
        mFactory = factory;
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
        mState = State.CONNECTING;
        Thread thread = new Thread(this::loop, "TerminowMcpBridge relay");
        thread.setDaemon(true);
        mThread = thread;
        thread.start();
    }

    public synchronized void stop() {
        mRunning = false;
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

    private void loop() {
        long backoff = BACKOFF_START_MS;
        while (mRunning) {
            try {
                runStream();
                backoff = BACKOFF_START_MS; // a clean end (e.g. stop) resets the backoff
            } catch (IOException | RuntimeException e) {
                if (!mRunning) break;
                mState = State.ERROR;
                mLastError = String.valueOf(e.getMessage());
                Logger.logDebug(LOG_TAG, "Relay stream ended: " + e.getMessage());
            }
            if (!mRunning) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
        mState = State.OFF;
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
            throw new IOException(mLastError);
        }
        mState = State.CONNECTED;
        mLastError = null;
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

    @NonNull
    private static String urlEncode(@NonNull String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
