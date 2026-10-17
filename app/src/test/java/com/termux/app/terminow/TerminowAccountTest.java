package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The account client against a local fake of Firebase Authentication and the Premium endpoint. */
public class TerminowAccountTest {

    /** Answers like Firebase and the WHMCS endpoint, and records the requests. */
    static class FakeServer implements AutoCloseable {
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final ServerSocket server;
        volatile boolean emailVerified;
        volatile boolean premium = true;
        /** Whether the account has an authenticator app as its second factor. */
        volatile boolean mfa;
        volatile String password = "correct-horse";

        FakeServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        serve(socket);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        private void serve(Socket socket) throws IOException {
            InputStream in = socket.getInputStream();
            String requestLine = line(in);
            String authorization = "";
            int length = 0;
            String header;
            while ((header = line(in)) != null && !header.isEmpty()) {
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
                if (lower.startsWith("authorization:")) authorization = header.substring(14).trim();
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) read += Math.max(0, in.read(body, read, length - read));
            String text = new String(body, StandardCharsets.UTF_8);
            String path = requestLine.split(" ")[1];
            requests.add(path.replaceAll("\\?.*", "") + " " + text + " " + authorization);

            int status = 200;
            String response;
            try {
                if (path.startsWith("/auth/accounts:signUp")) {
                    JSONObject request = new JSONObject(text);
                    if (request.getString("password").length() < 6) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"WEAK_PASSWORD : Password should be at least 6 characters\"}}";
                    } else if (request.getString("email").equals("taken@example.com")) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"EMAIL_EXISTS\"}}";
                    } else {
                        response = signIn(request.getString("email"));
                    }
                } else if (path.startsWith("/auth/accounts:signInWithPassword")) {
                    JSONObject request = new JSONObject(text);
                    if (!request.getString("password").equals(password)) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"INVALID_LOGIN_CREDENTIALS\"}}";
                    } else if (mfa) {
                        response = "{\"localId\":\"uid1\",\"email\":\"" + request.getString("email") + "\",\"mfaPendingCredential\":\"pending-1\"," +
                            "\"mfaInfo\":[{\"mfaEnrollmentId\":\"factor-1\",\"displayName\":\"Authenticator app\",\"totpInfo\":{}}]}";
                    } else {
                        response = signIn(request.getString("email"));
                    }
                } else if (path.startsWith("/auth/accounts/mfaSignIn:finalize")) {
                    JSONObject request = new JSONObject(text);
                    if (!request.getString("mfaPendingCredential").equals("pending-1") || !request.getString("mfaEnrollmentId").equals("factor-1") ||
                        !request.getJSONObject("totpVerificationInfo").getString("verificationCode").equals("123456")) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"INVALID_CODE\"}}";
                    } else {
                        response = "{\"idToken\":\"id-mfa\",\"refreshToken\":\"refresh-mfa\"}";
                    }
                } else if (path.startsWith("/auth/accounts/mfaEnrollment:start")) {
                    response = "{\"totpSessionInfo\":{\"sharedSecretKey\":\"JBSWY3DPEHPK3PXP\",\"verificationCodeLength\":6," +
                        "\"hashingAlgorithm\":\"SHA1\",\"periodSec\":30,\"sessionInfo\":\"enroll-1\"}}";
                } else if (path.startsWith("/auth/accounts/mfaEnrollment:finalize")) {
                    JSONObject request = new JSONObject(text);
                    if (!request.getJSONObject("totpVerificationInfo").getString("sessionInfo").equals("enroll-1") ||
                        !request.getJSONObject("totpVerificationInfo").getString("verificationCode").equals("654321")) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"INVALID_CODE\"}}";
                    } else {
                        mfa = true;
                        response = "{\"idToken\":\"id-enrolled\",\"refreshToken\":\"refresh-enrolled\"}";
                    }
                } else if (path.startsWith("/auth/accounts/mfaEnrollment:withdraw")) {
                    mfa = false;
                    response = "{\"idToken\":\"id-withdrawn\",\"refreshToken\":\"refresh-withdrawn\"}";
                } else if (path.startsWith("/auth/accounts:update")) {
                    JSONObject request = new JSONObject(text);
                    password = request.getString("password");
                    response = "{\"localId\":\"uid1\",\"idToken\":\"id-updated\",\"refreshToken\":\"refresh-updated\",\"expiresIn\":\"3600\"}";
                } else if (path.startsWith("/auth/accounts:signInWithIdp")) {
                    JSONObject request = new JSONObject(text);
                    if (!request.getString("postBody").equals("id_token=google-id-token&providerId=google.com") ||
                        !request.getBoolean("returnSecureToken")) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"INVALID_IDP_RESPONSE\"}}";
                    } else {
                        response = "{\"localId\":\"uid2\",\"email\":\"g@example.com\",\"emailVerified\":true,\"providerId\":\"google.com\"," +
                            "\"idToken\":\"id-g\",\"refreshToken\":\"refresh-g\",\"expiresIn\":\"3600\"}";
                    }
                } else if (path.startsWith("/auth/accounts:sendOobCode")) {
                    response = "{\"email\":\"x\"}";
                } else if (path.startsWith("/auth/accounts:lookup")) {
                    response = "{\"users\":[{\"localId\":\"uid1\",\"email\":\"member@example.com\",\"emailVerified\":" + emailVerified +
                        ",\"providerUserInfo\":[{\"providerId\":\"password\"}]" +
                        (mfa ? ",\"mfaInfo\":[{\"mfaEnrollmentId\":\"factor-1\",\"displayName\":\"Authenticator app\",\"totpInfo\":{}," +
                            "\"enrolledAt\":\"2026-10-17T01:02:03.456Z\"}]" : "") + "}]}";
                } else if (path.startsWith("/auth/accounts:delete")) {
                    response = "{}";
                } else if (path.startsWith("/token")) {
                    response = "{\"id_token\":\"id-refreshed\",\"refresh_token\":\"refresh-2\",\"user_id\":\"uid1\",\"expires_in\":\"3600\"}";
                } else if (path.startsWith("/premium")) {
                    if (!authorization.startsWith("Bearer id-")) {
                        status = 401;
                        response = "{\"error\":\"Sign in first\"}";
                    } else {
                        response = premium ? "{\"premium\":true,\"plan\":\"TermiNow Premium Yearly\",\"nextDueDate\":\"2027-10-12\"}" : "{\"premium\":false}";
                    }
                } else if (path.startsWith("/functions/verifyPlayPurchase")) {
                    String token = new JSONObject(text).getJSONObject("data").getString("purchaseToken");
                    if (!authorization.startsWith("Bearer id-")) {
                        status = 401;
                        response = "{\"error\":{\"status\":\"UNAUTHENTICATED\",\"message\":\"Sign in to TermiNow first\",\"details\":{\"code\":\"SIGN_IN_REQUIRED\"}}}";
                    } else if (token.equals("good-token")) {
                        response = "{\"result\":{\"status\":\"ACTIVE\",\"test\":true,\"acknowledged\":true,\"entitlement\":{\"premium\":true," +
                            "\"google_play\":{\"premium\":true,\"subscriptionState\":\"SUBSCRIPTION_STATE_ACTIVE\",\"expiresAt\":4102444800000,\"autoRenewEnabled\":true,\"test\":true}}}}";
                    } else if (token.equals("someone-elses")) {
                        status = 403;
                        response = "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"This Google Play purchase belongs to another TermiNow account\"," +
                            "\"details\":{\"code\":\"PURCHASE_OF_ANOTHER_ACCOUNT\",\"transient\":false}}}";
                    } else {
                        status = 503;
                        response = "{\"error\":{\"status\":\"UNAVAILABLE\",\"message\":\"Google Play could not be reached\"," +
                            "\"details\":{\"code\":\"PLAY_UNAVAILABLE\",\"transient\":true}}}";
                    }
                } else if (path.startsWith("/firestore/entitlements/uid2")) {
                    response = "{\"name\":\"x\",\"fields\":{\"premium\":{\"booleanValue\":true},\"sources\":{\"mapValue\":{\"fields\":{\"google_play\":" +
                        "{\"mapValue\":{\"fields\":{\"premium\":{\"booleanValue\":true},\"expiresAt\":{\"integerValue\":\"4102444800000\"}," +
                        "\"subscriptionState\":{\"stringValue\":\"SUBSCRIPTION_STATE_ACTIVE\"},\"tier\":{\"nullValue\":null}}}}}}}}}";
                } else if (path.startsWith("/firestore/entitlements/")) {
                    status = 404;
                    response = "{\"error\":{\"code\":404}}";
                } else {
                    status = 404;
                    response = "{}";
                }
            } catch (Exception e) {
                status = 500;
                response = "{}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        }

        private static String signIn(String email) {
            return "{\"localId\":\"uid1\",\"email\":\"" + email + "\",\"idToken\":\"id-1\",\"refreshToken\":\"refresh-1\",\"expiresIn\":\"3600\"}";
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') if (c != '\r') out.write(c);
            return c == -1 && out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static TerminowAccount account(FakeServer server) {
        TerminowAccount account = new TerminowAccount("test-key", "com.terminow");
        account.setUrls(server.url("/auth/"), server.url("/token"), server.url("/premium"));
        return account;
    }

    @Test
    public void signUpSendsVerificationAndPremiumNeedsAVerifiedEmail() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signUp("member@example.com", "correct-horse");
            assertEquals("uid1", session.uid);
            assertEquals("refresh-1", session.refreshToken);
            assertFalse(session.emailVerified);
            assertTrue(server.requests.get(1).contains("VERIFY_EMAIL"));

            try {
                account.checkPremium(session);
                fail("Premium was checked for an unverified email");
            } catch (TerminowAccount.AccountException expected) {
                assertEquals("EMAIL_NOT_VERIFIED", expected.code);
            }
            // Nothing was sent to the Premium endpoint
            for (String request : server.requests) assertFalse(request.startsWith("/premium"));

            server.emailVerified = true;
            account.refreshVerified(session);
            assertTrue(session.emailVerified);
            // A new ID token, since the old one says the email is not verified
            assertEquals("id-refreshed", session.idToken);
            assertEquals("refresh-2", session.refreshToken);

            TerminowAccount.Premium premium = account.checkPremium(session);
            assertTrue(premium.premium);
            assertEquals("TermiNow Premium Yearly", premium.plan);
            assertEquals("2027-10-12", premium.nextDueDate);
            String premiumRequest = server.requests.get(server.requests.size() - 1);
            assertTrue(premiumRequest, premiumRequest.endsWith("Bearer id-refreshed"));

            server.premium = false;
            premium = account.checkPremium(session);
            assertFalse(premium.premium);
            assertNull(premium.nextDueDate);
        }
    }

    @Test
    public void twoFactorEnrollSignInChangePasswordAndRemove() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.emailVerified = true;
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signIn("member@example.com", "correct-horse");
            TerminowAccount.Details details = account.getDetails(session);
            assertTrue(details.hasPassword);
            assertFalse(details.hasGoogle);
            assertTrue(details.factors.isEmpty());

            TerminowAccount.TotpSetup setup = account.startTotpEnrollment(session);
            assertEquals("JBSWY3DPEHPK3PXP", setup.secret);
            assertEquals(6, setup.digits);
            try {
                account.finishTotpEnrollment(session, setup, "000000", "Authenticator app");
                fail("A wrong code turned on 2FA");
            } catch (TerminowAccount.AccountException expected) {
                assertEquals("INVALID_CODE", expected.code);
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("Wrong code"));
            }
            account.finishTotpEnrollment(session, setup, "654321", "Authenticator app");
            // Firebase gives new tokens after the change
            assertEquals("refresh-enrolled", session.refreshToken);
            details = account.getDetails(session);
            assertEquals(1, details.factors.size());
            assertTrue(details.factors.get(0).totp);
            assertEquals(java.time.Instant.parse("2026-10-17T01:02:03Z").toEpochMilli(), details.factors.get(0).enrolledAt);

            // Signing in now needs the code
            TerminowAccount.MfaRequiredException pending = null;
            try {
                account.signIn("member@example.com", "correct-horse");
                fail("Signed in without the second factor");
            } catch (TerminowAccount.MfaRequiredException e) {
                pending = e;
            }
            assertEquals("factor-1", pending.enrollmentId);
            try {
                account.finishMfaSignIn(pending, "111111");
                fail("Signed in with a wrong code");
            } catch (TerminowAccount.MfaRequiredException e) {
                fail("Wrong exception");
            } catch (TerminowAccount.AccountException expected) {
                assertEquals("INVALID_CODE", expected.code);
            }
            TerminowAccount.Session signedIn = account.finishMfaSignIn(pending, "123456");
            assertEquals("uid1", signedIn.uid);
            assertEquals("member@example.com", signedIn.email);
            assertTrue(signedIn.emailVerified);

            // Changing the password asks for the code too
            try {
                account.changePassword(signedIn, "correct-horse", "battery-staple", null);
                fail("The password changed without the code");
            } catch (TerminowAccount.MfaRequiredException expected) {
                assertEquals("correct-horse", server.password);
            }
            try {
                account.changePassword(signedIn, "wrong", "battery-staple", "123456");
                fail("The password changed with a wrong current one");
            } catch (TerminowAccount.AccountException expected) {
                assertEquals("INVALID_LOGIN_CREDENTIALS", expected.code);
            }
            account.changePassword(signedIn, "correct-horse", "battery-staple", "123456");
            assertEquals("battery-staple", server.password);
            assertEquals("refresh-updated", signedIn.refreshToken);
            String update = null;
            for (String request : server.requests) if (request.startsWith("/auth/accounts:update")) update = request;
            assertTrue(update, update.contains("\"idToken\":\"id-mfa\""));

            account.removeFactor(signedIn, "factor-1");
            assertTrue(account.getDetails(signedIn).factors.isEmpty());
            assertEquals("member@example.com", account.signIn("member@example.com", "battery-staple").email);
        }
    }

    @Test
    public void totpSecretIsShownInGroupsOfFour() {
        TerminowAccount.TotpSetup setup = new TerminowAccount.TotpSetup();
        setup.secret = "JBSWY3DPEHPK3PXP";
        assertEquals("JBSW Y3DP EHPK 3PXP", TerminowAccountSecurity.groups(setup.secret));
    }

    @Test
    public void googleSignInIsVerifiedAndCanCheckPremium() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signInWithGoogle("google-id-token");
            assertEquals("uid2", session.uid);
            assertEquals("g@example.com", session.email);
            assertTrue(session.emailVerified);
            assertTrue(account.checkPremium(session).premium);
            expect(() -> account.signInWithGoogle("forged"), "INVALID_IDP_RESPONSE", "Failed (INVALID_IDP_RESPONSE)");
        }
    }

    @Test
    public void playPurchasesAreVerifiedByTheFunctionOfTheAccount() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signInWithGoogle("google-id-token");
            // Without a functions region the app says so, it does not try
            try {
                account.verifyPlayPurchase(session, "good-token");
                fail("verified without a backend");
            } catch (TerminowAccount.FunctionException e) {
                assertEquals("BACKEND_NOT_CONFIGURED", e.code);
            }
            account.setFirebaseUrls(server.url("/functions/"), server.url("/firestore/"));
            JSONObject result = account.verifyPlayPurchase(session, "good-token");
            assertEquals("ACTIVE", result.getString("status"));
            assertTrue(result.getJSONObject("entitlement").getBoolean("premium"));
            // The ID token of the account goes with it, the account is never a field of the request
            String request = server.requests.get(server.requests.size() - 1);
            assertTrue(request, request.endsWith("Bearer id-g"));
            assertFalse(request, request.contains("uid2"));

            try {
                account.verifyPlayPurchase(session, "someone-elses");
                fail("accepted the purchase of another account");
            } catch (TerminowAccount.FunctionException e) {
                assertEquals("PURCHASE_OF_ANOTHER_ACCOUNT", e.code);
                assertFalse(e.transientFailure);
            }
            try {
                account.verifyPlayPurchase(session, "flaky-token");
                fail("no error");
            } catch (TerminowAccount.FunctionException e) {
                assertEquals("PLAY_UNAVAILABLE", e.code);
                assertTrue(e.transientFailure);
            }
        }
    }

    @Test
    public void theEntitlementIsReadFromFirestore() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            account.setFirebaseUrls(server.url("/functions/"), server.url("/firestore/"));
            TerminowAccount.Session session = account.signInWithGoogle("google-id-token");
            JSONObject entitlement = account.getPlayEntitlement(session);
            assertTrue(entitlement.getBoolean("premium"));
            JSONObject play = entitlement.getJSONObject("sources").getJSONObject("google_play");
            assertEquals(4102444800000L, play.getLong("expiresAt"));
            assertEquals("SUBSCRIPTION_STATE_ACTIVE", play.getString("subscriptionState"));
            assertTrue(play.isNull("tier"));
            session.uid = "nobody";
            assertNull(account.getPlayEntitlement(session));
        }
    }

    @Test
    public void playAccountIdIsTheHashTheServerExpects() {
        // accountIdFor('alice') in firebase/functions/src/subscription.js
        assertEquals("51a095186e84d968360b1725b7502d99a0364cfe794c20c09f64c723dd254c22", TerminowBilling.accountId("alice"));
    }

    @Test
    public void errorsAreReadable() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            expect(() -> account.signIn("member@example.com", "wrong"), "INVALID_LOGIN_CREDENTIALS", "Wrong email or password");
            expect(() -> account.signUp("taken@example.com", "correct-horse"), "EMAIL_EXISTS", "An account with this email exists already. Sign in instead");
            expect(() -> account.signUp("new@example.com", "123"), "WEAK_PASSWORD", "Use a password with at least 6 characters");
        }
        assertEquals("Sign in with email is not turned on for TermiNow yet",
            TerminowAccount.toException(400, new JSONObject("{\"error\":{\"message\":\"CONFIGURATION_NOT_FOUND\"}}")).getMessage());
        assertEquals("Email not verified",
            TerminowAccount.toException(401, new JSONObject("{\"error\":\"Email not verified\"}")).getMessage());
    }

    @Test
    public void notConfiguredWithoutAKey() {
        TerminowAccount account = new TerminowAccount("", "com.terminow");
        assertFalse(account.isConfigured());
        expect(() -> account.signIn("a@example.com", "x"), "NOT_CONFIGURED", "Accounts are not set up in this build");
    }

    @Test
    public void deleteAndSignOutNeedNothingElse() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signIn("member@example.com", "correct-horse");
            account.deleteAccount(session);
            assertTrue(server.requests.get(server.requests.size() - 1).startsWith("/auth/accounts:delete"));
            // The password is never part of a request other than sign in and sign up
            for (String request : server.requests)
                if (!request.startsWith("/auth/accounts:signInWithPassword") && !request.startsWith("/auth/accounts:signUp"))
                    assertFalse(request, request.contains("correct-horse"));
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expect(Call call, String code, String message) {
        try {
            call.run();
            fail("No error for " + code);
        } catch (TerminowAccount.AccountException e) {
            assertEquals(code, e.code);
            assertEquals(message, e.getMessage());
        } catch (Exception e) {
            fail(String.valueOf(e));
        }
    }

}
