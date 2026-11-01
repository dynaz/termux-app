package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** OAuth callback parsing + validation (state/PKCE/cancellation/errors) and pending persistence. */
public class TerminowOAuthFlowTest {

    private static TerminowOAuthFlow.Pending pending(String state) {
        TerminowOAuthFlow.Pending p = new TerminowOAuthFlow.Pending();
        p.kind = TerminowOAuthClients.DROPBOX;
        p.verifier = "verifier-value";
        p.state = state;
        p.redirectUri = TerminowOAuthClients.DROPBOX_REDIRECT;
        p.createdAtMillis = 1000;
        return p;
    }

    @Test
    public void parsesCodeAndStateFromQuery() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse(
            "com.terminow://oauth2redirect/dropbox?code=ABC%2F123&state=xyz");
        assertEquals("ABC/123", p.code);   // url-decoded
        assertEquals("xyz", p.state);
        assertTrue(p.error.isEmpty());
    }

    @Test
    public void parsesErrorResponse() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse(
            "com.terminow://oauth2redirect/dropbox?error=access_denied&error_description=The%20user%20said%20no");
        assertEquals("access_denied", p.error);
        assertEquals("The user said no", p.errorDescription);
    }

    @Test
    public void validCodeWithMatchingStatePasses() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?code=the-code&state=S1");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(pending("S1"), p);
        assertTrue(v.ok());
        assertEquals("the-code", v.code);
    }

    @Test
    public void mismatchedStateIsRejected() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?code=the-code&state=ATTACKER");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(pending("S1"), p);
        assertFalse(v.ok());
        assertFalse(v.cancelled);
        assertNull(v.code);
        assertTrue(v.error.toLowerCase().contains("did not match"));
    }

    @Test
    public void userDenialIsCancellationNotError() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?error=access_denied");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(pending("S1"), p);
        assertTrue(v.cancelled);
        assertNull(v.error);
    }

    @Test
    public void providerErrorSurfacesDescription() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?error=invalid_scope&error_description=Bad%20scope");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(pending("S1"), p);
        assertFalse(v.ok());
        assertFalse(v.cancelled);
        assertEquals("Bad scope", v.error);
    }

    @Test
    public void noPendingAuthorizationIsRejected() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?code=c&state=S1");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(null, p);
        assertFalse(v.ok());
        assertTrue(v.error.toLowerCase().contains("no authorization"));
    }

    @Test
    public void codeMissingAndNoErrorIsTreatedAsAbandoned() {
        TerminowOAuthFlow.Params p = TerminowOAuthFlow.parse("x://y?state=S1");
        TerminowOAuthFlow.Validation v = TerminowOAuthFlow.validate(pending("S1"), p);
        assertTrue(v.cancelled);
    }

    @Test
    public void pendingSurvivesSerializationLikeProcessRecreation() throws Exception {
        // Simulate: begin stores pending, the process is killed while the browser is open, then the
        // callback arrives and the pending is reloaded from storage and still validates.
        TerminowOAuthFlow.Pending original = pending("STATE-123");
        String persisted = original.toJson().toString();

        TerminowOAuthFlow.Pending reloaded = TerminowOAuthFlow.Pending.fromJson(persisted);
        assertEquals("STATE-123", reloaded.state);
        assertEquals("verifier-value", reloaded.verifier);
        assertEquals(TerminowOAuthClients.DROPBOX, reloaded.kind);

        TerminowOAuthFlow.Params callback = TerminowOAuthFlow.parse("x://y?code=c&state=STATE-123");
        assertTrue(TerminowOAuthFlow.validate(reloaded, callback).ok());
    }
}
