package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth 2.0 authorization-code + PKCE for the cloud providers (Google Drive, Dropbox, OneDrive):
 * builds the consent URL, exchanges the returned code for tokens, and refreshes an expired access
 * token. The consent step itself (opening the authorize URL in a browser and catching the redirect)
 * needs a registered client id/redirect per provider and a device browser, so it is wired on the
 * device; the token exchange and refresh here are provider-agnostic HTTP and are unit-tested against
 * a fake token endpoint. The token endpoint is part of {@link Endpoints} so tests can point it local.
 */
final class TerminowOAuth {

    /** One provider's OAuth endpoints and app registration. */
    static final class Endpoints {
        final String authUrl;
        final String tokenUrl;
        final String clientId;
        final String redirectUri;
        final String scope;
        final Map<String, String> extraAuthParams;

        Endpoints(String authUrl, String tokenUrl, String clientId, String redirectUri, String scope,
                  @Nullable Map<String, String> extraAuthParams) {
            this.authUrl = authUrl;
            this.tokenUrl = tokenUrl;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.scope = scope;
            this.extraAuthParams = extraAuthParams == null ? new LinkedHashMap<>() : extraAuthParams;
        }
    }

    static Endpoints google(@NonNull String clientId, @NonNull String redirectUri) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("access_type", "offline");   // ask for a refresh token
        extra.put("prompt", "consent");
        return new Endpoints("https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token", clientId, redirectUri,
            "https://www.googleapis.com/auth/drive.appdata", extra);
    }

    static Endpoints dropbox(@NonNull String clientId, @NonNull String redirectUri) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("token_access_type", "offline");   // long-lived refresh token
        return new Endpoints("https://www.dropbox.com/oauth2/authorize",
            "https://api.dropboxapi.com/oauth2/token", clientId, redirectUri,
            "files.content.write files.content.read files.metadata.read", extra);
    }

    static Endpoints microsoft(@NonNull String clientId, @NonNull String redirectUri) {
        return new Endpoints("https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token", clientId, redirectUri,
            "Files.ReadWrite.AppFolder offline_access", null);
    }

    static final class Tokens {
        String accessToken = "";
        String refreshToken = "";
        long expiresAtMillis;   // 0 = unknown

        boolean isFresh(long now) {
            return !accessToken.isEmpty() && (expiresAtMillis == 0 || now < expiresAtMillis - 60_000);
        }
    }

    // ---------------------------------------------------------------------------------------
    // PKCE

    @NonNull
    static String codeVerifier() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    @NonNull
    static String codeChallenge(@NonNull String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return base64Url(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    static String authorizationUrl(@NonNull Endpoints ep, @NonNull String verifier, @NonNull String state) {
        StringBuilder b = new StringBuilder(ep.authUrl);
        b.append(ep.authUrl.contains("?") ? '&' : '?');
        b.append("response_type=code");
        b.append("&client_id=").append(enc(ep.clientId));
        b.append("&redirect_uri=").append(enc(ep.redirectUri));
        b.append("&scope=").append(enc(ep.scope));
        b.append("&state=").append(enc(state));
        b.append("&code_challenge=").append(enc(codeChallenge(verifier)));
        b.append("&code_challenge_method=S256");
        for (Map.Entry<String, String> e : ep.extraAuthParams.entrySet())
            b.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Token exchange / refresh

    @NonNull
    static Tokens exchangeCode(@NonNull Endpoints ep, @NonNull String code, @NonNull String verifier) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", ep.clientId);
        form.put("redirect_uri", ep.redirectUri);
        form.put("code_verifier", verifier);
        return parse(post(ep.tokenUrl, form), null);
    }

    @NonNull
    static Tokens refresh(@NonNull Endpoints ep, @NonNull String refreshToken) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", ep.clientId);
        return parse(post(ep.tokenUrl, form), refreshToken);
    }

    /**
     * A {@link TerminowHttpProvider.TokenSource} that keeps the access token fresh, refreshing it
     * through {@code ep} when it is about to expire and handing the new tokens to {@code onRefreshed}
     * (so the caller can persist them). The refresh is serialized so concurrent calls refresh once.
     */
    @NonNull
    static TerminowHttpProvider.TokenSource source(@NonNull Endpoints ep, @NonNull Tokens tokens,
                                                   @Nullable java.util.function.Consumer<Tokens> onRefreshed) {
        final Object lock = new Object();
        final Tokens[] held = {tokens};
        return () -> {
            synchronized (lock) {
                Tokens current = held[0];
                if (current.isFresh(System.currentTimeMillis())) return current.accessToken;
                if (current.refreshToken.isEmpty())
                    throw new IOException("The cloud account needs to be reconnected (no refresh token)");
                Tokens refreshed = refresh(ep, current.refreshToken);
                held[0] = refreshed;
                if (onRefreshed != null) onRefreshed.accept(refreshed);
                return refreshed.accessToken;
            }
        };
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private static Tokens parse(@NonNull String json, @Nullable String fallbackRefresh) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error"))
                throw new IOException("OAuth token request failed: " + o.optString("error_description", o.optString("error")));
            Tokens t = new Tokens();
            t.accessToken = o.optString("access_token", "");
            t.refreshToken = o.optString("refresh_token", fallbackRefresh == null ? "" : fallbackRefresh);
            long expiresIn = o.optLong("expires_in", 0);
            t.expiresAtMillis = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0;
            if (t.accessToken.isEmpty()) throw new IOException("OAuth token response had no access_token");
            return t;
        } catch (JSONException e) {
            throw new IOException("OAuth token response was not valid JSON", e);
        }
    }

    @NonNull
    private static String post(@NonNull String url, @NonNull Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(30_000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = c.getOutputStream()) { out.write(payload); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String response = readAll(in);
        c.disconnect();
        if (code >= 500) throw new IOException("OAuth token endpoint returned HTTP " + code);
        return response;   // 4xx bodies carry an error object parse() surfaces
    }

    @NonNull
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final char[] B64URL =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** RFC 4648 base64url without padding; no dependency on API 26+ or android.util in tests. */
    @NonNull
    private static String base64Url(@NonNull byte[] in) {
        StringBuilder out = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= in.length) {
            int n = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8) | (in[i + 2] & 0xff);
            out.append(B64URL[(n >> 18) & 63]).append(B64URL[(n >> 12) & 63])
               .append(B64URL[(n >> 6) & 63]).append(B64URL[n & 63]);
            i += 3;
        }
        int rem = in.length - i;
        if (rem == 1) {
            int n = (in[i] & 0xff) << 16;
            out.append(B64URL[(n >> 18) & 63]).append(B64URL[(n >> 12) & 63]);
        } else if (rem == 2) {
            int n = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8);
            out.append(B64URL[(n >> 18) & 63]).append(B64URL[(n >> 12) & 63]).append(B64URL[(n >> 6) & 63]);
        }
        return out.toString();
    }

    @NonNull
    private static String enc(@NonNull String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TerminowOAuth() {}
}
