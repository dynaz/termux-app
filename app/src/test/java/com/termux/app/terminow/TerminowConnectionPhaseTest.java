package com.termux.app.terminow;

import static com.termux.app.terminow.TerminowConnectionPhase.AUTHENTICATING;
import static com.termux.app.terminow.TerminowConnectionPhase.CONNECTED;
import static com.termux.app.terminow.TerminowConnectionPhase.CONNECTING;
import static com.termux.app.terminow.TerminowConnectionPhase.DISCONNECTED;
import static com.termux.app.terminow.TerminowConnectionPhase.FAILED;
import static com.termux.app.terminow.TerminowConnectionPhase.advance;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The connection phase comes from real signals: it only reaches CONNECTED on a ready signal
 * ({@code sftp>} or a shell prompt), never from time passing; a process that dies before it is
 * ready is FAILED; and a connected session that ends becomes DISCONNECTED, not FAILED.
 */
public class TerminowConnectionPhaseTest {

    // advance(current, running, ready, authPrompt, failure, ended)

    @Test public void staysConnectingWithoutSignals() {
        assertEquals(CONNECTING, advance(CONNECTING, true, false, false, false, false));
    }

    @Test public void connectingToAuthenticatingOnPrompt() {
        assertEquals(AUTHENTICATING, advance(CONNECTING, true, false, true, false, false));
    }

    @Test public void staysAuthenticatingAfterPromptAnswered() {
        // The prompt line is gone (authPrompt=false) but it must not fall back to CONNECTING.
        assertEquals(AUTHENTICATING, advance(AUTHENTICATING, true, false, false, false, false));
    }

    @Test public void readyReachesConnectedFromEitherPhase() {
        assertEquals(CONNECTED, advance(CONNECTING, true, true, false, false, false));
        assertEquals(CONNECTED, advance(AUTHENTICATING, true, true, false, false, false));
    }

    @Test public void failureBeatsReady() {
        assertEquals(FAILED, advance(AUTHENTICATING, true, true, false, true, false));
    }

    @Test public void processDyingBeforeReadyIsFailure() {
        assertEquals(FAILED, advance(CONNECTING, false, false, false, false, false));
        assertEquals(FAILED, advance(CONNECTING, true, false, false, false, true));
    }

    @Test public void connectedSessionEndingIsDisconnected() {
        assertEquals(DISCONNECTED, advance(CONNECTED, false, false, false, false, false));
        assertEquals(DISCONNECTED, advance(CONNECTED, true, false, false, false, true));
    }

    @Test public void connectedStaysConnectedWhileRunning() {
        assertEquals(CONNECTED, advance(CONNECTED, true, false, false, false, false));
    }

    @Test public void terminalPhasesDoNotChange() {
        assertEquals(FAILED, advance(FAILED, true, true, false, false, false));
        assertEquals(DISCONNECTED, advance(DISCONNECTED, true, true, false, false, false));
    }

    @Test public void readyBeatsAuthPrompt() {
        assertEquals(CONNECTED, advance(CONNECTING, true, true, true, false, false));
    }
}
