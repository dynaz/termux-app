package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** OAuth PKCE + token exchange/refresh against a fake token endpoint. */
public class TerminowOAuthTest {

    private FakeHttpServer mServer;
    private final AtomicInteger mRefreshes = new AtomicInteger();

    @Before public void start() throws Exception {
        mServer = new FakeHttpServer(r -> {
            String body = r.bodyText();
            if (body.contains("grant_type=authorization_code"))
                return FakeHttpServer.Response.json(200, "{\"access_token\":\"A1\",\"refresh_token\":\"R1\",\"expires_in\":3600}");
            if (body.contains("grant_type=refresh_token")) {
                mRefreshes.incrementAndGet();
                return FakeHttpServer.Response.json(200, "{\"access_token\":\"A2\",\"expires_in\":3600}");
            }
            return FakeHttpServer.Response.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad\"}");
        });
    }

    @After public void stop() throws Exception { if (mServer != null) mServer.close(); }

    private TerminowOAuth.Endpoints endpoints() {
        return new TerminowOAuth.Endpoints("https://auth.example/authorize", mServer.baseUrl() + "/token",
            "client-123", "com.terminow:/oauth", "scope.one scope.two", null);
    }

    @Test
    public void pkceChallengeMatchesTheRfcVector() {
        // RFC 7636 Appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            TerminowOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void verifierIsUrlSafeAndLongEnough() {
        String v = TerminowOAuth.codeVerifier();
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(v.matches("[A-Za-z0-9\\-_]+"));
    }

    @Test
    public void authorizationUrlCarriesPkceAndParams() {
        String url = TerminowOAuth.authorizationUrl(endpoints(), "verifier-xyz", "state-abc");
        assertTrue(url.startsWith("https://auth.example/authorize?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=client-123"));
        assertTrue(url.contains("code_challenge=" + TerminowOAuth.codeChallenge("verifier-xyz")));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=state-abc"));
        assertTrue(url.contains("scope=scope.one%20scope.two"));
    }

    @Test
    public void exchangeCodeReturnsTokens() throws Exception {
        TerminowOAuth.Tokens t = TerminowOAuth.exchangeCode(endpoints(), "the-code", "the-verifier");
        assertEquals("A1", t.accessToken);
        assertEquals("R1", t.refreshToken);
        assertTrue(t.expiresAtMillis > System.currentTimeMillis());
    }

    @Test
    public void refreshKeepsTheRefreshTokenWhenOmitted() throws Exception {
        TerminowOAuth.Tokens t = TerminowOAuth.refresh(endpoints(), "R1");
        assertEquals("A2", t.accessToken);
        assertEquals("R1", t.refreshToken);   // provider omitted it; we keep the old one
    }

    @Test
    public void sourceRefreshesOnlyWhenExpiredAndPersists() throws Exception {
        TerminowOAuth.Tokens expired = new TerminowOAuth.Tokens();
        expired.accessToken = "OLD";
        expired.refreshToken = "R1";
        expired.expiresAtMillis = System.currentTimeMillis() - 1000;   // already expired
        TerminowOAuth.Tokens[] saved = new TerminowOAuth.Tokens[1];
        TerminowHttpProvider.TokenSource source = TerminowOAuth.source(endpoints(), expired, t -> saved[0] = t);

        assertEquals("A2", source.token());      // refreshed
        assertEquals(1, mRefreshes.get());
        assertTrue(saved[0] != null && "A2".equals(saved[0].accessToken));
        assertEquals("A2", source.token());      // now fresh, no second refresh
        assertEquals(1, mRefreshes.get());
    }

    @Test
    public void sourceFailsWhenExpiredWithNoRefreshToken() {
        TerminowOAuth.Tokens expired = new TerminowOAuth.Tokens();
        expired.accessToken = "OLD";
        expired.expiresAtMillis = System.currentTimeMillis() - 1000;
        TerminowHttpProvider.TokenSource source = TerminowOAuth.source(endpoints(), expired, null);
        try { source.token(); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("reconnected")); }
    }
}
