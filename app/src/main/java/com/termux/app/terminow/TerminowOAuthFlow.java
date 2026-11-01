package com.termux.app.terminow;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Drives the authorization-code + PKCE browser consent for a cloud provider: starts it (builds the
 * consent URL and remembers a pending authorization that survives process death), then completes it
 * when the redirect comes back (validates state to stop CSRF/injection, distinguishes cancellation
 * from a provider error, exchanges the code, and stores the tokens). The parsing and validation are
 * pure and unit-tested; only {@link #begin} and {@link #complete} touch Android and the network.
 */
final class TerminowOAuthFlow {

    // ---------------------------------------------------------------------------------------
    // Pending authorization (persisted across the browser round-trip and process recreation)

    static final class Pending {
        String kind = "";
        String verifier = "";
        String state = "";
        String redirectUri = "";
        long createdAtMillis;

        @NonNull
        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("kind", kind).put("verifier", verifier)
                .put("state", state).put("redirectUri", redirectUri)
                .put("createdAt", createdAtMillis);
        }

        @NonNull
        static Pending fromJson(@NonNull String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            Pending p = new Pending();
            p.kind = o.optString("kind", "");
            p.verifier = o.optString("verifier", "");
            p.state = o.optString("state", "");
            p.redirectUri = o.optString("redirectUri", "");
            p.createdAtMillis = o.optLong("createdAt", 0);
            return p;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Callback parsing (pure)

    static final class Params {
        String code = "";
        String state = "";
        String error = "";
        String errorDescription = "";
    }

    /** Parse the query of a redirect URI into its OAuth parameters. Never throws. */
    @NonNull
    static Params parse(@Nullable String redirectUri) {
        Params p = new Params();
        if (redirectUri == null) return p;
        int q = redirectUri.indexOf('?');
        String query = q >= 0 ? redirectUri.substring(q + 1) : "";
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            switch (name) {
                case "code": p.code = value; break;
                case "state": p.state = value; break;
                case "error": p.error = value; break;
                case "error_description": p.errorDescription = value; break;
                default: break;
            }
        }
        return p;
    }

    static final class Validation {
        final boolean cancelled;
        @Nullable final String error;   // null if ok
        @Nullable final String code;    // the code to exchange when ok

        private Validation(boolean cancelled, @Nullable String error, @Nullable String code) {
            this.cancelled = cancelled; this.error = error; this.code = code;
        }

        boolean ok() { return error == null && !cancelled; }

        static Validation ok(String code) { return new Validation(false, null, code); }
        static Validation cancelled() { return new Validation(true, null, null); }
        static Validation error(String msg) { return new Validation(false, msg, null); }
    }

    /** Check a callback against the pending authorization: CSRF state, cancellation, errors, code. */
    @NonNull
    static Validation validate(@Nullable Pending pending, @NonNull Params p) {
        if (pending == null || pending.state.isEmpty())
            return Validation.error("There was no authorization in progress on this device");
        if (!p.error.isEmpty()) {
            if (p.error.equals("access_denied") || p.error.equals("user_cancelled"))
                return Validation.cancelled();
            return Validation.error(p.errorDescription.isEmpty() ? p.error : p.errorDescription);
        }
        if (!p.state.equals(pending.state))
            return Validation.error("The sign-in response did not match this device's request and was rejected");
        if (p.code.isEmpty())
            return Validation.cancelled();   // no code and no error: treat as abandoned
        return Validation.ok(p.code);
    }

    // ---------------------------------------------------------------------------------------
    // Device entry points

    static final class Result {
        enum Status { CONNECTED, CANCELLED, ERROR }
        final Status status;
        final String kind;
        final String message;
        Result(Status status, String kind, String message) { this.status = status; this.kind = kind; this.message = message; }
    }

    /**
     * Start consent for {@code kind}: remember a fresh pending authorization and return an Intent that
     * opens the provider's consent page in a browser. The caller launches it; the redirect returns to
     * {@link TerminowOAuthRedirectActivity}. Returns {@code null} if the provider is not configured.
     */
    @Nullable
    static Intent begin(@NonNull Context context, @NonNull TerminowStore store, @NonNull String kind) {
        TerminowOAuth.Endpoints ep = TerminowOAuthClients.endpoints(kind);
        if (ep == null) return null;
        Pending pending = new Pending();
        pending.kind = kind;
        pending.verifier = TerminowOAuth.codeVerifier();
        pending.state = randomState();
        pending.redirectUri = TerminowOAuthClients.redirect(kind);
        pending.createdAtMillis = System.currentTimeMillis();
        store.setPendingOAuth(pending);
        String url = TerminowOAuth.authorizationUrl(ep, pending.verifier, pending.state);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Complete consent from a redirect URI: validate it against the stored pending authorization
     * (which may have been reloaded after the app process was recreated), exchange the code, and
     * store the tokens. Clears the pending authorization in every terminal case.
     */
    @NonNull
    static Result complete(@NonNull TerminowStore store, @Nullable String redirectUri) {
        Pending pending = store.getPendingOAuth();
        Params params = parse(redirectUri);
        Validation v = validate(pending, params);
        if (v.cancelled) {
            store.setPendingOAuth(null);
            return new Result(Result.Status.CANCELLED, pending != null ? pending.kind : "", "Sign-in was cancelled");
        }
        if (!v.ok()) {
            store.setPendingOAuth(null);
            return new Result(Result.Status.ERROR, pending != null ? pending.kind : "", v.error);
        }
        TerminowOAuth.Endpoints ep = TerminowOAuthClients.endpoints(pending.kind);
        if (ep == null) {
            store.setPendingOAuth(null);
            return new Result(Result.Status.ERROR, pending.kind, "This provider is no longer configured");
        }
        try {
            TerminowOAuth.Tokens tokens = TerminowOAuth.exchangeCode(ep, v.code, pending.verifier);
            if (!store.setOAuthTokens(pending.kind, tokens))
                return new Result(Result.Status.ERROR, pending.kind, "Could not store the account securely on this device");
            return new Result(Result.Status.CONNECTED, pending.kind, "Connected");
        } catch (IOException e) {
            return new Result(Result.Status.ERROR, pending.kind, e.getMessage());
        } finally {
            store.setPendingOAuth(null);
        }
    }

    @NonNull
    private static String randomState() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @NonNull
    private static String decode(@NonNull String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private TerminowOAuthFlow() {}
}
