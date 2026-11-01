package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared machinery for the token-authenticated cloud providers (Google Drive, Dropbox, OneDrive):
 * a Bearer-token connection, JSON request/response, a streamed file upload with progress, a download
 * stream, and the same retry policy as S3 (I/O, 5xx, 429 retried with backoff; 4xx not). Each provider
 * subclass adds only its own endpoints and JSON shapes. The base URLs are injected so the real APIs
 * are used in the app and a local fake server in tests.
 */
abstract class TerminowHttpProvider implements TerminowBackupProvider {

    /** Supplies a valid access token, refreshing it if needed. Device/account-specific. */
    interface TokenSource {
        @NonNull String token() throws IOException;
    }

    private static final int MAX_RETRIES = 4;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    protected final TokenSource mTokens;

    TerminowHttpProvider(@NonNull TokenSource tokens) {
        mTokens = tokens;
    }

    protected void requireOwnedKey(@NonNull String key) throws IOException {
        if (!key.startsWith(PREFIX)) throw new IOException("Refusing to touch a key outside " + PREFIX + ": " + key);
    }

    // ---------------------------------------------------------------------------------------
    // HTTP

    @NonNull
    protected HttpURLConnection open(@NonNull String method, @NonNull String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + mTokens.token());
        return c;
    }

    /** A JSON (or empty-body) request; returns the response body. Non-2xx throws (5xx/429 retryable). */
    @NonNull
    protected String requestJson(@NonNull String what, @NonNull String method, @NonNull String url,
                                 @Nullable String contentType, @Nullable String body) throws IOException {
        return withRetries(what, () -> {
            HttpURLConnection c = open(method, url);
            if (body != null) {
                c.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            }
            int code = c.getResponseCode();
            String response = readBody(c);
            c.disconnect();
            if (code < 200 || code >= 300) throw httpError(what, code, response);
            return response;
        });
    }

    /** Stream {@code file} as the request body with progress; headers via {@code apply}. */
    protected void uploadFile(@NonNull String what, @NonNull String method, @NonNull String url,
                              @NonNull HeaderSetter apply, @NonNull File file, @NonNull Progress p) throws IOException {
        final long length = file.length();
        withRetries(what, () -> {
            if (p.cancelled()) throw new IOException("Cancelled");
            HttpURLConnection c = open(method, url);
            apply.apply(c);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(length);
            try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[1 << 16];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (p.cancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    sent += n;
                    p.onProgress(sent, length);
                }
            }
            int code = c.getResponseCode();
            String response = readBody(c);
            c.disconnect();
            if (code < 200 || code >= 300) throw httpError(what, code, response);
            return null;
        });
    }

    /** Open a download stream (the caller closes it). {@code apply} adds any provider headers. */
    @NonNull
    protected InputStream download(@NonNull String what, @NonNull String method, @NonNull String url,
                                   @NonNull HeaderSetter apply) throws IOException {
        return withRetries(what, () -> {
            HttpURLConnection c = open(method, url);
            apply.apply(c);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { String b = readBody(c); c.disconnect(); throw httpError(what, code, b); }
            return c.getInputStream();
        });
    }

    protected interface HeaderSetter {
        void apply(@NonNull HttpURLConnection c) throws IOException;
    }

    @NonNull
    protected String readBody(@NonNull HttpURLConnection c) throws IOException {
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) >= 0 && total < (4 << 20)) { out.write(buf, 0, n); total += n; }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    // ---------------------------------------------------------------------------------------
    // Retry

    protected interface Call<T> { T run() throws IOException; }

    protected <T> T withRetries(@NonNull String what, @NonNull Call<T> call) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return call.run();
            } catch (Retryable e) {
                last = e;
            } catch (IOException e) {
                if ("Cancelled".equals(e.getMessage())) throw e;
                last = e;
            }
            try {
                Thread.sleep((long) (500 * Math.pow(2, attempt)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException(what + " was interrupted", ie);
            }
        }
        throw new IOException(what + " failed after " + MAX_RETRIES + " attempts: " + (last == null ? "?" : last.getMessage()), last);
    }

    protected static final class Retryable extends IOException {
        Retryable(@NonNull String message) { super(message); }
    }

    @NonNull
    protected IOException httpError(@NonNull String what, int code, @Nullable String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 300) oneLine = oneLine.substring(0, 300);
        String message = what + " got HTTP " + code + (oneLine.isEmpty() ? "" : ": " + oneLine);
        return (code >= 500 || code == 429) ? new Retryable(message) : new IOException(message);
    }

    protected static long parseIso(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.ROOT);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(s.trim()).getTime();
            } catch (java.text.ParseException ignored) {
            }
        }
        return 0;
    }
}
