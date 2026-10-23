package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * The Streamable HTTP transport: every message is a POST of its own, answered with JSON or with
 * an SSE stream that ends with the response. Each request has its own connection, so several can
 * wait at the same time and one can be cancelled alone.
 *
 * A request runs on a worker thread while the caller waits for it. A cancel or a timeout ends the
 * wait at once: some HttpURLConnection implementations block disconnect() until a read of a
 * chunked stream on another thread returns, which an idle SSE stream may never do.
 *
 * Redirects are never followed, since they could send the credentials to another server, and
 * TLS certificates are always verified by the platform.
 */
public final class McpHttpTransport implements McpTransport {

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mcp-http");
        thread.setDaemon(true);
        return thread;
    });
    private static final long OTHER_TIMEOUT_MS = 30_000;
    private static final int CLOSE_TIMEOUT_MS = 5000;

    /** One message on its own connection. The first outcome wins. */
    private static final class Exchange {
        final CountDownLatch done = new CountDownLatch(1);
        volatile HttpURLConnection connection;
        volatile boolean cancelled;
        volatile boolean timedOut;
        volatile JSONObject response;
        volatile McpException failure;

        synchronized void complete(@Nullable JSONObject message) {
            if (done.getCount() == 0) return;
            response = message;
            done.countDown();
        }

        synchronized void fail(@NonNull McpException e) {
            if (done.getCount() == 0) return;
            failure = e;
            done.countDown();
        }

        /** Disconnect on a worker, since disconnect() itself can block. */
        void abort() {
            HttpURLConnection c = connection;
            if (c != null) WORKERS.execute(c::disconnect);
        }
    }

    private final URL mUrl;
    private final Map<String, String> mHeaders;
    private final String mClientVersion;
    private int mConnectTimeoutMs = 15000;

    /** The requests that wait for a response, by id. */
    private final Map<String, Exchange> mRequests = new ConcurrentHashMap<>();
    private final Set<Exchange> mOpen = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Nullable private volatile Listener mListener;
    @Nullable private volatile String mProtocolVersion;
    @Nullable private volatile String mSessionId;
    private volatile boolean mClosed;
    private volatile boolean mSessionEnded;

    /**
     * @param url The MCP endpoint, already validated.
     * @param headers The headers to send with every message, like `Authorization`.
     */
    public McpHttpTransport(@NonNull URL url, @NonNull Map<String, String> headers, @NonNull String clientVersion) {
        mUrl = url;
        mHeaders = new LinkedHashMap<>(headers);
        mClientVersion = clientVersion;
    }

    public void setConnectTimeout(int connectTimeoutMs) {
        mConnectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void start(@NonNull Listener listener) {
        mListener = listener;
    }

    @Override
    public void setProtocolVersion(@Nullable String version) {
        mProtocolVersion = version;
    }

    @NonNull
    @Override
    public JSONObject request(@NonNull JSONObject message, @NonNull String method, long timeoutMs) throws McpException {
        if (mClosed) throw closed();
        Exchange exchange = new Exchange();
        String id = String.valueOf(message.opt("id"));
        mRequests.put(id, exchange);
        try {
            WORKERS.execute(() -> {
                try {
                    exchange.complete(post(message, method, exchange, timeoutMs, true));
                } catch (McpException e) {
                    exchange.fail(e);
                } catch (RuntimeException e) {
                    exchange.fail(new McpException(McpException.Kind.INVALID_RESPONSE, "Failed to read the answer to " + method + ": " + e));
                }
            });
            boolean done;
            try {
                done = exchange.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = false;
                exchange.cancelled = true;
            }
            if (!done) {
                if (!exchange.cancelled) exchange.timedOut = true;
                exchange.fail(interrupted(exchange, method));
                exchange.abort();
            }
            if (exchange.failure != null) throw exchange.failure;
            if (exchange.response == null) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server did not answer " + method);
            return exchange.response;
        } finally {
            mRequests.remove(id, exchange);
        }
    }

    @Override
    public void notify(@NonNull JSONObject message, @NonNull String method) throws McpException {
        post(message, method, new Exchange(), OTHER_TIMEOUT_MS, false);
    }

    @Override
    public void respond(@NonNull JSONObject response) throws McpException {
        post(response, "a response", new Exchange(), OTHER_TIMEOUT_MS, false);
    }

    @Override
    public void cancel(@NonNull Object id) {
        Exchange exchange = mRequests.get(String.valueOf(id));
        if (exchange == null) return;
        exchange.cancelled = true;
        exchange.fail(new McpException(McpException.Kind.CANCELLED, "Cancelled"));
        exchange.abort();
    }

    /** Stop every message on its way, and end the session of a legacy server, if it started one. */
    @Override
    public void close() {
        if (mClosed) return;
        mClosed = true;
        for (Exchange exchange : mOpen) {
            exchange.fail(closed());
            exchange.abort();
        }
        String sessionId = mSessionId;
        mSessionId = null;
        if (sessionId == null || mSessionEnded) return;
        HttpURLConnection connection = null;
        try {
            connection = open("DELETE", CLOSE_TIMEOUT_MS);
            connection.setConnectTimeout(CLOSE_TIMEOUT_MS);
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
            String version = mProtocolVersion;
            if (version != null) connection.setRequestProperty("MCP-Protocol-Version", version);
            // 405 means that the server does not let clients end sessions, which is fine
            connection.getResponseCode();
        } catch (IOException e) {
            // The server ends the session itself later
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Send a message and, for a request, read its response: a JSON object or an SSE stream that
     * ends with it. Messages of the server on the stream before it go to the listener.
     */
    @Nullable
    private JSONObject post(@NonNull JSONObject message, @NonNull String method, @NonNull Exchange exchange, long timeoutMs,
                            boolean isRequest) throws McpException {
        if (mClosed) throw closed();
        mOpen.add(exchange);
        HttpURLConnection connection = null;
        boolean sentSession = false;
        try {
            connection = open("POST", timeoutMs);
            exchange.connection = connection;
            if (exchange.cancelled || exchange.timedOut || mClosed) throw new InterruptedIOException();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            String version = mProtocolVersion;
            if (version != null) connection.setRequestProperty("MCP-Protocol-Version", version);
            if (McpSession.MODERN_VERSION.equals(version) && message.has("method")) {
                // The modern revision mirrors the method, and the tool name, into headers
                connection.setRequestProperty("Mcp-Method", method);
                JSONObject params = message.optJSONObject("params");
                if ("tools/call".equals(method) && params != null) connection.setRequestProperty("Mcp-Name", params.optString("name"));
            }
            String sessionId = mSessionId;
            if (sessionId != null) {
                connection.setRequestProperty("Mcp-Session-Id", sessionId);
                sentSession = true;
            }
            byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            if ("initialize".equals(method) && status >= 200 && status < 300)
                mSessionId = connection.getHeaderField("Mcp-Session-Id");
            checkStatus(connection, status, method, sentSession);

            if (!isRequest) {
                readLimited(errorOrInput(connection, status));
                if (status < 200 || status >= 300)
                    throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server did not accept " + method + " (HTTP " + status + ")", status);
                return null;
            }
            String id = String.valueOf(message.opt("id"));
            String contentType = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
            InputStream in = errorOrInput(connection, status);
            boolean success = status >= 200 && status < 300;
            JSONObject response = success && contentType.startsWith("text/event-stream") ? readSse(in, id) :
                parseMessage(readLimited(in), success);
            if (!success) {
                JSONObject error = response != null ? response.optJSONObject("error") : null;
                if (error != null)
                    throw new McpException(McpException.Kind.SERVER_ERROR, McpSession.describeError(method, error), status, "", error);
                throw new McpException(McpException.Kind.HTTP_ERROR, "The server answered " + method + " with HTTP " + status, status);
            }
            if (response == null)
                throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server answered " + method +
                    " without a JSON-RPC response (content type " + contentType + ")", status);
            if (response.has("id") && !response.isNull("id") && !id.equals(String.valueOf(response.opt("id"))))
                throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server answered with the id of another request");
            return response;
        } catch (McpException e) {
            if (exchange.cancelled || exchange.timedOut || mClosed) throw interrupted(exchange, method);
            throw e;
        } catch (SocketTimeoutException e) {
            if (exchange.cancelled || mClosed) throw interrupted(exchange, method);
            throw new McpException(McpException.Kind.TIMEOUT, "The server did not answer " + method + " in time");
        } catch (SSLException e) {
            if (exchange.cancelled || exchange.timedOut || mClosed) throw interrupted(exchange, method);
            throw new McpException(McpException.Kind.TLS, "The secure connection failed: " + e.getMessage() +
                ". The certificate of the server must be valid, TermiNow never skips the check");
        } catch (IOException e) {
            if (exchange.cancelled || exchange.timedOut || mClosed || e instanceof InterruptedIOException)
                throw interrupted(exchange, method);
            throw new McpException(McpException.Kind.NETWORK, "Failed to connect to " + mUrl.getHost() + ": " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            exchange.connection = null;
            mOpen.remove(exchange);
        }
    }

    @NonNull
    private McpException interrupted(@NonNull Exchange exchange, @NonNull String method) {
        if (exchange.cancelled) return new McpException(McpException.Kind.CANCELLED, "Cancelled");
        if (mClosed) return closed();
        return new McpException(McpException.Kind.TIMEOUT, "The server did not answer " + method + " in time");
    }

    @NonNull
    private static McpException closed() {
        return new McpException(McpException.Kind.CANCELLED, "The connection was closed");
    }

    @NonNull
    private HttpURLConnection open(@NonNull String method, long timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) mUrl.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int) Math.min(mConnectTimeoutMs, timeoutMs));
        connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMs));
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "TermiNow/" + mClientVersion);
        for (Map.Entry<String, String> header : mHeaders.entrySet())
            connection.setRequestProperty(header.getKey(), header.getValue());
        return connection;
    }

    private void checkStatus(@NonNull HttpURLConnection connection, int status, @NonNull String method, boolean sentSession)
        throws McpException, IOException {
        if (status >= 300 && status < 400)
            throw new McpException(McpException.Kind.REDIRECT, "The server redirected to " + connection.getHeaderField("Location") +
                ". Enter that URL instead, if you trust it", status);
        if (status == 401) {
            String authenticate = String.valueOf(connection.getHeaderField("WWW-Authenticate"));
            readLimited(errorOrInput(connection, status));
            if (mHeaders.isEmpty() && authenticate.toLowerCase(Locale.ROOT).startsWith("bearer")) {
                Matcher matcher = Pattern.compile("resource_metadata=\"([^\"]*)\"").matcher(authenticate);
                throw new McpException(McpException.Kind.OAUTH_REQUIRED, "The server requires OAuth sign-in", status,
                    matcher.find() ? matcher.group(1) : "", null);
            }
            throw new McpException(McpException.Kind.AUTH_FAILED, mHeaders.isEmpty() ? "The server requires authentication" :
                "The server rejected the credentials (HTTP 401)", status);
        }
        if (status == 403) {
            readLimited(errorOrInput(connection, status));
            throw new McpException(McpException.Kind.FORBIDDEN, "The server refused access (HTTP 403). The credentials may lack permissions, or the server rejected the request origin", status);
        }
        if (status == 404 && sentSession) {
            // The server forgot the session, for example after a restart. Only a new one helps
            readLimited(errorOrInput(connection, status));
            McpException ended = new McpException(McpException.Kind.HTTP_ERROR, "The server ended the session (HTTP 404). Reconnect to start a new one", status);
            mSessionEnded = true;
            Listener listener = mListener;
            if (listener != null && !mClosed) listener.onClosed(ended);
            throw ended;
        }
        if (status >= 500)
            throw new McpException(McpException.Kind.HTTP_ERROR, "The server failed with HTTP " + status + " for " + method, status);
    }

    @Nullable
    private static InputStream errorOrInput(@NonNull HttpURLConnection connection, int status) throws IOException {
        return status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    }

    @NonNull
    private static String readLimited(@Nullable InputStream in) throws IOException, McpException {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_MESSAGE_BYTES) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The response of the server is too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Nullable
    private static JSONObject parseMessage(@NonNull String body, boolean success) throws McpException {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return new JSONObject(trimmed);
        } catch (JSONException e) {
            if (success)
                throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server did not answer with JSON. Is this the MCP endpoint URL?");
            return null;
        }
    }

    /**
     * Read an SSE stream until the response with the id. Requests and notifications of the server
     * on the way go to the listener.
     */
    @Nullable
    private JSONObject readSse(@Nullable InputStream in, @NonNull String id) throws IOException, McpException {
        if (in == null) return null;
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_MESSAGE_BYTES) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The response of the server is too large");
                if (line.isEmpty()) {
                    if (data.length() == 0) continue;
                    JSONObject message;
                    try {
                        message = new JSONObject(data.toString());
                    } catch (JSONException e) {
                        throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server sent an event that is not JSON");
                    }
                    data.setLength(0);
                    if (!message.has("method")) {
                        if (id.equals(String.valueOf(message.opt("id")))) return message;
                        continue;
                    }
                    Listener listener = mListener;
                    if (listener == null) continue;
                    if (message.has("id")) listener.onServerRequest(message);
                    else listener.onNotification(message);
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
                }
                // Comments, event names, ids and retry fields carry no message
            }
        }
        throw new McpException(McpException.Kind.INVALID_RESPONSE, "The event stream ended without the response");
    }

}
