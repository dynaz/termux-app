package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * An S3 / S3-compatible (AWS S3, MinIO, Backblaze B2, Wasabi…) backup provider: SigV4-signed
 * {@code PUT}/{@code GET}/{@code DELETE}/{@code ListObjectsV2} over HTTPS, no SDK. Uploads stream the
 * file with {@code x-amz-content-sha256: UNSIGNED-PAYLOAD}. Transient failures (I/O, HTTP 5xx, 429)
 * are retried with backoff; an upload restarts from the beginning of the file on retry. Only keys
 * under {@link #PREFIX} are listed, written or deleted.
 *
 * <p>The signing is unit-tested against AWS's documented vector, and the HTTP/retry/list/delete flow
 * is tested against an in-process fake-S3 server. It is <b>not</b> verified against a real S3 account;
 * that needs a live bucket (see the feature report).</p>
 */
final class TerminowS3Provider implements TerminowBackupProvider {

    /** How a bucket and credentials are reached. {@code endpoint} has no scheme, e.g. "s3.amazonaws.com". */
    static final class Config {
        final String endpoint;
        final boolean tls;
        final String region;
        final String bucket;
        final String accessKey;
        final String secretKey;
        final boolean pathStyle;

        Config(@NonNull String endpoint, boolean tls, @NonNull String region, @NonNull String bucket,
               @NonNull String accessKey, @NonNull String secretKey, boolean pathStyle) {
            this.endpoint = endpoint;
            this.tls = tls;
            this.region = region;
            this.bucket = bucket;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.pathStyle = pathStyle;
        }
    }

    private static final int MAX_RETRIES = 4;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final Config mConfig;

    TerminowS3Provider(@NonNull Config config) {
        mConfig = config;
    }

    @NonNull @Override public String id() { return "s3"; }
    @NonNull @Override public String displayName() { return "S3-compatible storage"; }

    // ---------------------------------------------------------------------------------------
    // Operations

    @Override
    public void upload(@NonNull String key, @NonNull File file, @NonNull Progress p) throws IOException {
        requireOwnedKey(key);
        final long length = file.length();
        withRetries("upload " + key, () -> {
            if (p.cancelled()) throw new IOException("Cancelled");
            HttpURLConnection c = open("PUT", key, "", TerminowAwsSigV4.UNSIGNED_PAYLOAD, length);
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
            checkStatus(c, 200, "upload");
            c.disconnect();
            return null;
        });
    }

    @NonNull @Override
    public InputStream open(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        return withRetries("open " + key, () -> {
            HttpURLConnection c = open("GET", key, "", TerminowAwsSigV4.EMPTY_SHA256, -1);
            int code = c.getResponseCode();
            if (code != 200) { String b = errorBody(c); c.disconnect(); throw httpError("download", code, b); }
            return c.getInputStream();   // the caller closes it, which closes the connection
        });
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        requireOwnedKey(key);
        withRetries("delete " + key, () -> {
            HttpURLConnection c = open("DELETE", key, "", TerminowAwsSigV4.EMPTY_SHA256, -1);
            int code = c.getResponseCode();
            if (code != 204 && code != 200 && code != 404) { String b = errorBody(c); c.disconnect(); throw httpError("delete", code, b); }
            c.disconnect();
            return null;
        });
    }

    @NonNull @Override
    public List<Remote> list() throws IOException {
        List<Remote> all = new ArrayList<>();
        String token = null;
        do {
            final String continuation = token;
            String[] next = new String[1];
            List<Remote> page = withRetries("list", () -> {
                // Canonical query must be sorted by parameter name.
                StringBuilder q = new StringBuilder();
                if (continuation != null) q.append("continuation-token=").append(TerminowAwsSigV4.uriEncode(continuation, true)).append('&');
                q.append("list-type=2&prefix=").append(TerminowAwsSigV4.uriEncode(PREFIX, true));
                HttpURLConnection c = open("GET", "", q.toString(), TerminowAwsSigV4.EMPTY_SHA256, -1);
                int code = c.getResponseCode();
                if (code != 200) { String b = errorBody(c); c.disconnect(); throw httpError("list", code, b); }
                List<Remote> parsed;
                try (InputStream in = c.getInputStream()) {
                    parsed = parseList(in, next);
                }
                c.disconnect();
                return parsed;
            });
            all.addAll(page);
            token = next[0];
        } while (token != null);
        return all;
    }

    // ---------------------------------------------------------------------------------------
    // HTTP + signing

    /** Build and sign a connection. {@code canonicalQuery} is already sorted + encoded (no leading '?'). */
    @NonNull
    private HttpURLConnection open(@NonNull String method, @NonNull String key, @NonNull String canonicalQuery,
                                   @NonNull String payloadSha256, long contentLength) throws IOException {
        String host = mConfig.pathStyle ? mConfig.endpoint : mConfig.bucket + "." + mConfig.endpoint;
        String encodedKey = key.isEmpty() ? "" : TerminowAwsSigV4.uriEncode(key, false);
        String canonicalUri = mConfig.pathStyle
            ? "/" + mConfig.bucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey)
            : "/" + encodedKey;
        if (canonicalUri.isEmpty()) canonicalUri = "/";

        String amzDate = amzDate();
        String dateStamp = amzDate.substring(0, 8);
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadSha256);
        headers.put("x-amz-date", amzDate);
        String auth = TerminowAwsSigV4.authorizationWithHash(method, "s3", mConfig.region, canonicalUri,
            canonicalQuery, headers, payloadSha256, mConfig.accessKey, mConfig.secretKey, amzDate, dateStamp);

        String url = (mConfig.tls ? "https://" : "http://") + host + canonicalUri + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("x-amz-date", amzDate);
        c.setRequestProperty("x-amz-content-sha256", payloadSha256);
        c.setRequestProperty("Authorization", auth);
        if (contentLength >= 0) c.setRequestProperty("Content-Length", Long.toString(contentLength));
        return c;
    }

    private void checkStatus(@NonNull HttpURLConnection c, int expected, @NonNull String what) throws IOException {
        int code = c.getResponseCode();
        if (code != expected) throw httpError(what, code, errorBody(c));
    }

    @NonNull
    private List<Remote> parseList(@NonNull InputStream in, @NonNull String[] nextToken) throws IOException {
        List<Remote> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(in);
            NodeList contents = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element e = (Element) contents.item(i);
                String key = text(e, "Key");
                if (key == null || !key.startsWith(PREFIX)) continue;
                long size = parseLong(text(e, "Size"));
                long modified = parseIso(text(e, "LastModified"));
                out.add(new Remote(key, size, modified));
            }
            String truncated = firstText(doc, "IsTruncated");
            nextToken[0] = "true".equalsIgnoreCase(truncated) ? firstText(doc, "NextContinuationToken") : null;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("The storage returned a listing TermiNow could not read", e);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Retry + helpers

    private interface Call<T> { T run() throws IOException; }

    private <T> T withRetries(@NonNull String what, @NonNull Call<T> call) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return call.run();
            } catch (RetryableException e) {
                last = e;
            } catch (IOException e) {
                // Connection reset / timeout etc. are worth retrying; a cancel is not.
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

    /** An HTTP status that is worth retrying (5xx, 429). 4xx (e.g. 403 bad credentials) is not. */
    private static final class RetryableException extends IOException {
        RetryableException(@NonNull String message) { super(message); }
    }

    @NonNull
    private static IOException httpError(@NonNull String what, int code, @Nullable String body) {
        String message = what + " got HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + oneLine(body));
        return (code >= 500 || code == 429) ? new RetryableException(message) : new IOException(message);
    }

    @Nullable
    private static String errorBody(@NonNull HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) >= 0 && total < 8192) { out.write(buf, 0, n); total += n; }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void requireOwnedKey(@NonNull String key) throws IOException {
        if (!key.startsWith(PREFIX)) throw new IOException("Refusing to touch a key outside " + PREFIX + ": " + key);
    }

    @NonNull
    private static String amzDate() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    @Nullable private static String text(@NonNull Element parent, @NonNull String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    @Nullable private static String firstText(@NonNull Document doc, @NonNull String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getParentNode() != null && "ListBucketResult".equals(n.getParentNode().getNodeName())) return n.getTextContent();
        }
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    private static long parseLong(@Nullable String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseIso(@Nullable String s) {
        if (s == null) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(s.trim()).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }

    @NonNull private static String oneLine(@NonNull String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
