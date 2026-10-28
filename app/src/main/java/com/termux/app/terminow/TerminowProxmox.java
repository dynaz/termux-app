package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The Proxmox VE REST API over HTTPS, authenticated with an API token. TLS is always verified: a
 * normal certificate is checked against the system trust store, and a self-signed one is accepted only
 * after the user has confirmed its SHA-256 fingerprint, which is then pinned for that connection — TLS
 * is never disabled globally. The token secret is never logged.
 *
 * <p>The pure helpers here (auth header, base URL, fingerprint, the pinned trust manager and parsing)
 * are unit-tested with fixtures. {@link #testConnection} performs a real request and so can only be
 * verified against a real Proxmox server.</p>
 */
public final class TerminowProxmox {

    /** Default Proxmox VE API port. */
    public static final int DEFAULT_PORT = 8006;

    private TerminowProxmox() {}

    /** A saved Proxmox connection. The token secret is held encrypted at rest by {@link TerminowStore}. */
    public static final class Connection {
        public String id = "";
        public String name = "";
        public String host = "";
        public int port = DEFAULT_PORT;
        /** The API token id, like {@code user@pam!mytoken}. */
        public String tokenId = "";
        /** The API token secret (a UUID). Stored encrypted; never logged. */
        public String tokenSecret = "";
        /** The pinned certificate SHA-256 fingerprint for a self-signed server, empty to use system trust. */
        public String fingerprint = "";
    }

    public enum Status { OK, AUTH_FAILED, CERT_UNTRUSTED, CERT_MISMATCH, UNREACHABLE, ERROR }

    public static final class Result {
        public final Status status;
        @Nullable public final String version;       // on OK
        @Nullable public final String fingerprint;   // on CERT_UNTRUSTED: the server's, to show and trust
        @Nullable public final String error;         // sanitized

        Result(Status status, @Nullable String version, @Nullable String fingerprint, @Nullable String error) {
            this.status = status;
            this.version = version;
            this.fingerprint = fingerprint;
            this.error = error;
        }
    }

    // ---- pure helpers (unit-tested) ----

    /** The {@code Authorization} header value for an API token. Never logged. */
    @NonNull
    public static String authHeader(@NonNull String tokenId, @NonNull String tokenSecret) {
        return "PVEAPIToken=" + tokenId + "=" + tokenSecret;
    }

    /** The API base URL, with an IPv6 host bracketed. */
    @NonNull
    public static String baseUrl(@NonNull String host, int port) {
        String h = host.trim();
        if (h.contains(":") && !h.startsWith("[")) h = "[" + h + "]";   // IPv6 literal
        return "https://" + h + ":" + (port > 0 ? port : DEFAULT_PORT) + "/api2/json";
    }

    /** The SHA-256 fingerprint of a certificate, as uppercase colon-separated hex (as Proxmox shows it). */
    @NonNull
    public static String fingerprintOf(@NonNull X509Certificate cert) throws CertificateException {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(sha.length * 3);
            for (byte b : sha) {
                if (sb.length() > 0) sb.append(':');
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    /** A trust manager that accepts the leaf certificate only when its fingerprint matches the pinned one. */
    @NonNull
    public static X509TrustManager pinnedTrustManager(@NonNull String pinnedFingerprint) {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no server certificate");
                String actual = fingerprintOf(chain[0]);
                if (!actual.equalsIgnoreCase(pinnedFingerprint.trim()))
                    throw new CertificateException("certificate fingerprint does not match the pinned one");
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    /** The Proxmox version string from a {@code /version} response body, or empty if absent. */
    @NonNull
    public static String parseVersion(@NonNull String body) {
        try {
            JSONObject data = new JSONObject(body).optJSONObject("data");
            return data == null ? "" : data.optString("version", "");
        } catch (JSONException e) {
            return "";
        }
    }

    // ---- network (needs a real Proxmox server to verify) ----

    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;

    /**
     * Try {@code GET /version} to verify reachability, TLS and the API token. On an untrusted self-signed
     * certificate (no fingerprint pinned yet), returns {@link Status#CERT_UNTRUSTED} with the server's
     * fingerprint so the user can confirm and pin it; it never silently accepts an unverified certificate.
     */
    @NonNull
    public static Result testConnection(@NonNull Connection c) {
        URL url;
        try {
            url = new URL(baseUrl(c.host, c.port) + "/version");
        } catch (IOException e) {
            return new Result(Status.ERROR, null, null, "bad address");
        }
        try {
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            if (!c.fingerprint.isEmpty()) conn.setSSLSocketFactory(pinnedFactory(c.fingerprint));
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", authHeader(c.tokenId, c.tokenSecret));
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) return new Result(Status.AUTH_FAILED, null, null, "token not accepted");
            if (code / 100 == 2) {
                String body = read(conn.getInputStream());
                return new Result(Status.OK, parseVersion(body), null, null);
            }
            return new Result(Status.ERROR, null, null, "HTTP " + code);
        } catch (SSLHandshakeException e) {
            if (!c.fingerprint.isEmpty()) return new Result(Status.CERT_MISMATCH, null, null, "certificate changed");
            String fp = fetchFingerprint(url);
            return fp != null ? new Result(Status.CERT_UNTRUSTED, null, fp, null)
                              : new Result(Status.ERROR, null, null, "TLS handshake failed");
        } catch (ConnectException | SocketTimeoutException | UnknownHostException e) {
            return new Result(Status.UNREACHABLE, null, null, "could not reach the server");
        } catch (IOException | RuntimeException e) {
            return new Result(Status.ERROR, null, null, e.getClass().getSimpleName());
        }
    }

    /** Read the server's leaf-certificate fingerprint for the trust-on-confirm prompt. Reads it, does not trust it. */
    @Nullable
    private static String fetchFingerprint(@NonNull URL url) {
        AtomicReference<X509Certificate> captured = new AtomicReference<>();
        X509TrustManager capture = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain != null && chain.length > 0) captured.set(chain[0]);
                throw new CertificateException("inspecting only");   // never completes the handshake
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(factory(capture));
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.connect();
        } catch (IOException | RuntimeException ignored) {
            // The handshake is expected to fail; we only want the captured certificate.
        }
        X509Certificate cert = captured.get();
        if (cert == null) return null;
        try {
            return fingerprintOf(cert);
        } catch (CertificateException e) {
            return null;
        }
    }

    @NonNull
    private static SSLSocketFactory pinnedFactory(@NonNull String fingerprint) throws IOException {
        return factory(pinnedTrustManager(fingerprint));
    }

    @NonNull
    private static SSLSocketFactory factory(@NonNull X509TrustManager tm) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException(e);
        }
    }

    @NonNull
    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(b)) > 0) {
            total += n;
            if (total > 4 * 1024 * 1024) break;
            out.write(b, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }
}
