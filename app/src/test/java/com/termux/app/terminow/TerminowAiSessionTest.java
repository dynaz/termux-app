package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TerminowAiSessionTest {

    private TerminowAiSession session;

    @Before
    public void setUp() {
        final Map<String, String> store = new HashMap<>();
        session = new TerminowAiSession(new TerminowAiSession.Store() {
            @Override public void save(String envKey, String token) { store.put(envKey, token); }
            @NonNull @Override public String load(String envKey) { return store.getOrDefault(envKey, ""); }
            @Override public void remove(String envKey) { store.remove(envKey); }
            @NonNull @Override public Map<String, String> all() { return new HashMap<>(store); }
        });
    }

    @Test
    public void mintedTokenResolvesToItsEnvironment() {
        String token = session.mint("ubuntu");
        assertEquals(64, token.length()); // 32 bytes hex
        assertEquals("ubuntu", session.environmentOf(token));
        assertTrue(session.hasToken("ubuntu"));
        assertEquals(1, session.count());
    }

    @Test
    public void tokensAreUniquePerEnvironmentAndMint() {
        String a = session.mint("ubuntu");
        String b = session.mint("debian");
        assertNotEquals(a, b);
        assertEquals("ubuntu", session.environmentOf(a));
        assertEquals("debian", session.environmentOf(b));
        // Minting again replaces the old token
        String a2 = session.mint("ubuntu");
        assertNotEquals(a, a2);
        assertNull(session.environmentOf(a));
        assertEquals("ubuntu", session.environmentOf(a2));
    }

    @Test
    public void unknownOrEmptyTokenResolvesToNothing() {
        session.mint("ubuntu");
        assertNull(session.environmentOf("deadbeef"));
        assertNull(session.environmentOf(""));
    }

    @Test
    public void revokeStopsTheToken() {
        String token = session.mint("ubuntu");
        session.revoke("ubuntu");
        assertNull(session.environmentOf(token));
        assertFalse(session.hasToken("ubuntu"));
        assertEquals(0, session.count());
    }

    @Test
    public void revokeAllClearsEverything() {
        session.mint("ubuntu");
        session.mint("debian");
        session.revokeAll();
        assertEquals(0, session.count());
    }
}
