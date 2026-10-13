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
                    if (!request.getString("password").equals("correct-horse")) {
                        status = 400;
                        response = "{\"error\":{\"code\":400,\"message\":\"INVALID_LOGIN_CREDENTIALS\"}}";
                    } else {
                        response = signIn(request.getString("email"));
                    }
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
                    response = "{\"users\":[{\"localId\":\"uid1\",\"email\":\"member@example.com\",\"emailVerified\":" + emailVerified + "}]}";
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
                } else if (path.startsWith("/play.php")) {
                    if (!new JSONObject(text).getString("purchaseToken").equals("good-token")) {
                        status = 403;
                        response = "{\"error\":\"This purchase belongs to another TermiNow account\"}";
                    } else {
                        response = "{\"premium\":true,\"plan\":\"TermiNow Premium (Google Play)\",\"nextDueDate\":null,\"source\":\"play\"}";
                    }
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
    public void playPurchaseIsGivenToTheServer() throws Exception {
        try (FakeServer server = new FakeServer()) {
            TerminowAccount account = account(server);
            TerminowAccount.Session session = account.signInWithGoogle("google-id-token");
            TerminowAccount.Premium premium = account.registerPlayPurchase(session, "good-token");
            assertTrue(premium.premium);
            assertEquals("play", premium.source);
            assertNull(premium.nextDueDate);
            expect(() -> account.registerPlayPurchase(session, "someone-elses"), "THIS",
                "This purchase belongs to another TermiNow account");
        }
    }

    @Test
    public void playAccountIdIsTheHashTheServerExpects() {
        // hash('sha256', 'terminow:alice') in play_lib.php
        assertEquals("51a095186e84d968360b1725b7502d99a0364cfe794c20c09f64c723dd254c22", TerminowBilling.accountId("alice"));
        assertEquals("https://cp.accmove.com/terminow/play.php", TerminowAccount.playUrl(TerminowAccount.PREMIUM_URL));
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
