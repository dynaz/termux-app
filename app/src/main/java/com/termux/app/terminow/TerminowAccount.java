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
import java.util.Locale;

/**
 * The TermiNow account: Firebase Authentication with email and password, through its REST API,
 * and the Premium status from WHMCS at cp.accmove.com.
 *
 * Only the refresh token is kept, encrypted by {@link TerminowStore}. ID tokens live in memory
 * for their hour. The password is never stored. All calls block, run them off the main thread.
 */
public class TerminowAccount {

    public static final String PREMIUM_URL = "https://cp.accmove.com/terminow/premium.php";
    private static final String AUTH_URL = "https://identitytoolkit.googleapis.com/v1/";
    private static final String TOKEN_URL = "https://securetoken.googleapis.com/v1/token";
    private static final int TIMEOUT_MS = 20000;

    public static class AccountException extends Exception {
        /** The error code of Firebase, like EMAIL_EXISTS, or empty. */
        public final String code;

        AccountException(@NonNull String code, @NonNull String message) {
            super(message);
            this.code = code;
        }
    }

    /** A signed in user. */
    public static class Session {
        public String uid = "";
        public String email = "";
        public String refreshToken = "";
        public String idToken = "";
        /** When the ID token expires, in milliseconds. */
        public long idTokenExpiry;
        public boolean emailVerified;
    }

    public static class Premium {
        public boolean premium;
        public String plan = "";
        @Nullable public String nextDueDate;
        /** Where Premium comes from: "accmove", "play", or empty. */
        public String source = "";
    }

    private final String mApiKey;
    private final String mPackageName;
    private String mAuthUrl = AUTH_URL;
    private String mTokenUrl = TOKEN_URL;
    private String mPremiumUrl = PREMIUM_URL;
    /** Like https://REGION-PROJECT.cloudfunctions.net/, empty if the build has no functions region. */
    private String mFunctionsUrl = "";
    private String mFirestoreUrl = "";

    public TerminowAccount(@NonNull String apiKey, @NonNull String packageName) {
        mApiKey = apiKey;
        mPackageName = packageName;
    }

    /** For the tests, a local server instead of Google and WHMCS. */
    void setUrls(@NonNull String authUrl, @NonNull String tokenUrl, @NonNull String premiumUrl) {
        mAuthUrl = authUrl;
        mTokenUrl = tokenUrl;
        mPremiumUrl = premiumUrl;
    }

    /**
     * Where the Cloud Functions and Firestore of the Firebase project are, for Google Play
     * purchases. Without them, purchases can not be verified and the app says so.
     */
    public void setFirebaseBackend(@NonNull String projectId, @NonNull String functionsRegion) {
        if (projectId.isEmpty() || functionsRegion.isEmpty()) return;
        mFunctionsUrl = "https://" + functionsRegion + "-" + projectId + ".cloudfunctions.net/";
        mFirestoreUrl = "https://firestore.googleapis.com/v1/projects/" + projectId + "/databases/(default)/documents/";
    }

    /** For the tests, a local server. */
    void setFirebaseUrls(@NonNull String functionsUrl, @NonNull String firestoreUrl) {
        mFunctionsUrl = functionsUrl;
        mFirestoreUrl = firestoreUrl;
    }

    public boolean hasFirebaseBackend() {
        return !mFunctionsUrl.isEmpty();
    }

    public boolean isConfigured() {
        return !mApiKey.isEmpty();
    }

    @NonNull
    public Session signUp(@NonNull String email, @NonNull String password) throws AccountException {
        Session session = fromSignIn(post("accounts:signUp", credentials(email, password)));
        // Firebase only trusts an email after its owner opened the link
        sendVerificationEmail(session);
        return session;
    }

    @NonNull
    public Session signIn(@NonNull String email, @NonNull String password) throws AccountException {
        Session session = fromSignIn(post("accounts:signInWithPassword", credentials(email, password)));
        refreshVerified(session);
        return session;
    }

    /**
     * Sign in with the Google ID token from the account picker of Android. Google has verified
     * the email already. A new Google user gets a TermiNow account, an existing one signs in.
     */
    @NonNull
    public Session signInWithGoogle(@NonNull String googleIdToken) throws AccountException {
        JSONObject body = object("postBody", "id_token=" + encode(googleIdToken) + "&providerId=google.com",
            "requestUri", "http://localhost");
        try {
            body.put("returnSecureToken", true);
            body.put("returnIdpCredential", true);
        } catch (JSONException e) {
            throw new AccountException("", String.valueOf(e.getMessage()));
        }
        JSONObject result = post("accounts:signInWithIdp", body);
        // An email that has a password account already needs that to be linked first
        if (result.optBoolean("needConfirmation"))
            throw new AccountException("NEED_CONFIRMATION", "This email has an account with a password. Sign in with the password");
        Session session = fromSignIn(result);
        session.emailVerified = result.optBoolean("emailVerified");
        return session;
    }

    public void sendVerificationEmail(@NonNull Session session) throws AccountException {
        post("accounts:sendOobCode", object("requestType", "VERIFY_EMAIL", "idToken", getIdToken(session)));
    }

    public void sendPasswordReset(@NonNull String email) throws AccountException {
        post("accounts:sendOobCode", object("requestType", "PASSWORD_RESET", "email", email.trim()));
    }

    /** Ask Firebase whether the email was verified since. */
    public void refreshVerified(@NonNull Session session) throws AccountException {
        JSONObject result = post("accounts:lookup", object("idToken", getIdToken(session)));
        JSONObject user = result.optJSONArray("users") != null ? result.optJSONArray("users").optJSONObject(0) : null;
        if (user == null) throw new AccountException("USER_NOT_FOUND", "The account does not exist anymore");
        boolean wasVerified = session.emailVerified;
        session.emailVerified = user.optBoolean("emailVerified");
        session.email = user.optString("email", session.email);
        // The ID token says whether the email was verified when it was made, so get a new one
        if (session.emailVerified && !wasVerified) refresh(session);
    }

    /** Delete the account in Firebase. TermiNow keeps no other data about it. */
    public void deleteAccount(@NonNull Session session) throws AccountException {
        post("accounts:delete", object("idToken", getIdToken(session)));
    }

    /** A valid ID token, refreshed when it expires within five minutes. */
    @NonNull
    public String getIdToken(@NonNull Session session) throws AccountException {
        if (session.idToken.isEmpty() || session.idTokenExpiry < System.currentTimeMillis() + 5 * 60 * 1000)
            refresh(session);
        return session.idToken;
    }

    public void refresh(@NonNull Session session) throws AccountException {
        if (session.refreshToken.isEmpty()) throw new AccountException("NOT_SIGNED_IN", "Sign in again");
        String body = "grant_type=refresh_token&refresh_token=" + encode(session.refreshToken);
        JSONObject result = request(mTokenUrl + "?key=" + encode(mApiKey), "application/x-www-form-urlencoded", body, null);
        session.idToken = result.optString("id_token");
        session.refreshToken = result.optString("refresh_token", session.refreshToken);
        session.uid = result.optString("user_id", session.uid);
        session.idTokenExpiry = System.currentTimeMillis() + result.optLong("expires_in", 3600) * 1000;
        if (session.idToken.isEmpty()) throw new AccountException("", "Firebase did not return a token");
    }

    /** Ask WHMCS whether the account is Premium. Needs a verified email. */
    @NonNull
    public Premium checkPremium(@NonNull Session session) throws AccountException {
        if (!session.emailVerified) throw new AccountException("EMAIL_NOT_VERIFIED", "Verify your email first");
        return toPremium(request(mPremiumUrl, "application/json", "{}", getIdToken(session)));
    }

    /** A failure of a Cloud Function, with its stable code and whether trying again later may work. */
    public static class FunctionException extends AccountException {
        public final boolean transientFailure;

        FunctionException(@NonNull String code, @NonNull String message, boolean transientFailure) {
            super(code, message);
            this.transientFailure = transientFailure;
        }
    }

    /**
     * Give a Google Play purchase token to the verification function of Firebase, which checks
     * it with Google Play, records it for this account and acknowledges it. The account comes
     * from the ID token, never from the request.
     *
     * @return The result of the function: status, test, acknowledged and the entitlement.
     */
    @NonNull
    public JSONObject verifyPlayPurchase(@NonNull Session session, @NonNull String purchaseToken) throws AccountException {
        return callFunction(session, "verifyPlayPurchase", object("purchaseToken", purchaseToken));
    }

    /** Link an existing WHMCS client to this account with a one-time code from the client area. */
    public void linkWhmcsAccount(@NonNull Session session, @NonNull String code) throws AccountException {
        callFunction(session, "linkWhmcsAccount", object("code", code));
    }

    /**
     * Call a callable Cloud Function. Its answer is {"result": ...} or {"error": {"message",
     * "details": {"code", "transient"}}}.
     */
    @NonNull
    private JSONObject callFunction(@NonNull Session session, @NonNull String name, @NonNull JSONObject data) throws AccountException {
        if (mFunctionsUrl.isEmpty())
            throw new FunctionException("BACKEND_NOT_CONFIGURED", "Google Play purchases are not set up in this build", false);
        if (!session.emailVerified) throw new AccountException("EMAIL_NOT_VERIFIED", "Verify your email first");
        String idToken = getIdToken(session);
        JSONObject body;
        try {
            body = new JSONObject().put("data", data);
        } catch (JSONException e) {
            throw new AccountException("", String.valueOf(e.getMessage()));
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mFunctionsUrl + name).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS * 2);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject answer;
            try {
                answer = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new FunctionException("BACKEND_UNAVAILABLE", "The server answered with HTTP " + status, true);
            }
            JSONObject error = answer.optJSONObject("error");
            if (status >= 200 && status < 300 && error == null) {
                JSONObject result = answer.optJSONObject("result");
                return result != null ? result : new JSONObject();
            }
            JSONObject details = error != null ? error.optJSONObject("details") : null;
            String code = details != null ? details.optString("code", "") : "";
            if (code.isEmpty()) code = status == 404 ? "BACKEND_NOT_DEPLOYED" : "BACKEND_ERROR";
            boolean transientFailure = details != null ? details.optBoolean("transient", status >= 500) : status >= 500 || status == 404;
            String message = error != null ? error.optString("message", "") : "";
            if (message.isEmpty()) message = "The server answered with HTTP " + status;
            throw new FunctionException(code, message, transientFailure);
        } catch (IOException e) {
            throw new FunctionException("NETWORK", "No connection: " + e.getMessage(), true);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * The Google Play entitlement of the account from Firestore (entitlements/{uid}), which only
     * the account may read and only the server writes. {@code null} if there is none yet.
     */
    @Nullable
    public JSONObject getPlayEntitlement(@NonNull Session session) throws AccountException {
        if (mFirestoreUrl.isEmpty()) return null;
        String idToken = getIdToken(session);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mFirestoreUrl + "entitlements/" + encode(session.uid)).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            int status = connection.getResponseCode();
            if (status == 404) return null;
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status != 200) throw new FunctionException("ENTITLEMENT_UNAVAILABLE", "Premium could not be read (HTTP " + status + ")", true);
            Object value = fromFirestore(new JSONObject(text).optJSONObject("fields"));
            return value instanceof JSONObject ? (JSONObject) value : null;
        } catch (IOException e) {
            throw new FunctionException("NETWORK", "No connection: " + e.getMessage(), true);
        } catch (JSONException e) {
            throw new FunctionException("ENTITLEMENT_UNAVAILABLE", "Premium could not be read", true);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The plain JSON of the fields of a Firestore REST document, or of one of its values. */
    @Nullable
    static Object fromFirestore(@Nullable JSONObject fields) throws JSONException {
        if (fields == null) return null;
        JSONObject object = new JSONObject();
        java.util.Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            object.put(key, firestoreValue(fields.optJSONObject(key)));
        }
        return object;
    }

    @Nullable
    private static Object firestoreValue(@Nullable JSONObject value) throws JSONException {
        if (value == null || value.has("nullValue")) return JSONObject.NULL;
        if (value.has("booleanValue")) return value.optBoolean("booleanValue");
        if (value.has("integerValue")) return Long.parseLong(value.optString("integerValue", "0"));
        if (value.has("doubleValue")) return value.optDouble("doubleValue");
        if (value.has("stringValue")) return value.optString("stringValue");
        if (value.has("mapValue")) {
            Object map = fromFirestore(value.optJSONObject("mapValue").optJSONObject("fields"));
            return map != null ? map : new JSONObject();
        }
        if (value.has("arrayValue")) {
            org.json.JSONArray array = new org.json.JSONArray();
            org.json.JSONArray values = value.optJSONObject("arrayValue").optJSONArray("values");
            for (int i = 0; values != null && i < values.length(); i++) array.put(firestoreValue(values.optJSONObject(i)));
            return array;
        }
        return JSONObject.NULL;
    }

    @NonNull
    private static Premium toPremium(@NonNull JSONObject result) {
        Premium premium = new Premium();
        premium.premium = result.optBoolean("premium");
        premium.plan = result.optString("plan");
        premium.nextDueDate = result.isNull("nextDueDate") ? null : result.optString("nextDueDate", null);
        premium.source = result.isNull("source") ? "" : result.optString("source");
        return premium;
    }

    @NonNull
    private Session fromSignIn(@NonNull JSONObject result) throws AccountException {
        Session session = new Session();
        session.uid = result.optString("localId");
        session.email = result.optString("email");
        session.idToken = result.optString("idToken");
        session.refreshToken = result.optString("refreshToken");
        session.idTokenExpiry = System.currentTimeMillis() + Long.parseLong(result.optString("expiresIn", "3600")) * 1000;
        if (session.idToken.isEmpty() || session.refreshToken.isEmpty())
            throw new AccountException("", "Firebase did not return a session");
        return session;
    }

    @NonNull
    private static JSONObject credentials(@NonNull String email, @NonNull String password) throws AccountException {
        JSONObject object = object("email", email.trim(), "password", password);
        try {
            object.put("returnSecureToken", true);
        } catch (JSONException e) {
            throw new AccountException("", String.valueOf(e.getMessage()));
        }
        return object;
    }

    @NonNull
    private static JSONObject object(@NonNull String... keyValues) throws AccountException {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) object.put(keyValues[i], keyValues[i + 1]);
        } catch (JSONException e) {
            throw new AccountException("", String.valueOf(e.getMessage()));
        }
        return object;
    }

    @NonNull
    private JSONObject post(@NonNull String method, @NonNull JSONObject body) throws AccountException {
        if (!isConfigured()) throw new AccountException("NOT_CONFIGURED", "Accounts are not set up in this build");
        return request(mAuthUrl + method + "?key=" + encode(mApiKey), "application/json", body.toString(), null);
    }

    @NonNull
    private JSONObject request(@NonNull String url, @NonNull String contentType, @NonNull String body,
                               @Nullable String bearer) throws AccountException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            // Lets an API key that is restricted to the app be used by it
            connection.setRequestProperty("X-Android-Package", mPackageName);
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject result;
            try {
                result = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new AccountException("", "The server answered with HTTP " + status + " and no valid data");
            }
            if (status >= 200 && status < 300) return result;
            throw toException(status, result);
        } catch (IOException e) {
            throw new AccountException("NETWORK", "No connection: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    static AccountException toException(int status, @NonNull JSONObject result) {
        Object error = result.opt("error");
        String code;
        if (error instanceof JSONObject) code = ((JSONObject) error).optString("message");
        else code = error != null ? String.valueOf(error) : "HTTP_" + status;
        // Firebase adds details after the code, like "WEAK_PASSWORD : Password should be ..."
        String base = code.split(" ")[0].toUpperCase(Locale.ROOT);
        String message;
        switch (base) {
            case "EMAIL_EXISTS": message = "An account with this email exists already. Sign in instead"; break;
            case "INVALID_LOGIN_CREDENTIALS":
            case "INVALID_PASSWORD":
            case "EMAIL_NOT_FOUND": message = "Wrong email or password"; break;
            case "INVALID_EMAIL": message = "This is not a valid email address"; break;
            case "WEAK_PASSWORD": message = "Use a password with at least 6 characters"; break;
            case "MISSING_PASSWORD": message = "Enter a password"; break;
            case "TOO_MANY_ATTEMPTS_TRY_LATER": message = "Too many attempts. Try again later"; break;
            case "USER_DISABLED": message = "This account was disabled"; break;
            case "TOKEN_EXPIRED":
            case "INVALID_REFRESH_TOKEN":
            case "INVALID_ID_TOKEN":
            case "USER_NOT_FOUND":
            case "CREDENTIAL_TOO_OLD_LOGIN_AGAIN": message = "Sign in again"; break;
            case "OPERATION_NOT_ALLOWED":
            case "CONFIGURATION_NOT_FOUND": message = "Sign in with email is not turned on for TermiNow yet"; break;
            default:
                // The Premium endpoint answers {"error": "..."} with plain words
                message = status == 401 || status == 403 || status == 503 || status == 405 ? code : "Failed (" + code + ")";
        }
        return new AccountException(base, message);
    }

    @NonNull
    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 1024 * 1024) throw new IOException("Response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String encode(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

}
