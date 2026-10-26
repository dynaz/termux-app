package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * The phase of a host (SSH/SFTP) connection shown by the connecting panel, derived from real
 * terminal output signals rather than a timer: the panel never claims success just because some
 * time passed or a shell prompt scrolled by on its own.
 *
 * <p>For a PTY {@code ssh} there is no connection event API, so {@link TerminowHome#watchConnection}
 * reads the signals from the transcript and calls {@link #advance}. A host-key {@code (yes/no)}
 * confirmation is deliberately not a signal here; it keeps the existing verify flow and the panel
 * stays {@link #CONNECTING}. When authenticating can not be told apart from connecting, the caller
 * passes {@code authPrompt=false} and the phase stays {@link #CONNECTING}.
 */
enum TerminowConnectionPhase {
    CONNECTING, AUTHENTICATING, CONNECTED, FAILED, DISCONNECTED;

    boolean isTerminal() {
        return this == FAILED || this == DISCONNECTED;
    }

    boolean isConnected() {
        return this == CONNECTED;
    }

    /**
     * Next phase from the current one and the signals observed this poll.
     *
     * @param current       the phase so far
     * @param running       the session process is still alive
     * @param ready         a real connected signal: {@code sftp>} for SFTP, a shell prompt for SSH
     * @param authPrompt    a password/passphrase/verification prompt is showing (not host-key yes/no)
     * @param failure       a failure reason matched the output
     * @param ended         the session finished (process exited or the tab was closed)
     */
    @NonNull
    static TerminowConnectionPhase advance(@NonNull TerminowConnectionPhase current, boolean running,
                                           boolean ready, boolean authPrompt, boolean failure, boolean ended) {
        if (current.isTerminal()) return current;
        if (current == CONNECTED) return (ended || !running) ? DISCONNECTED : CONNECTED;

        // Still connecting or authenticating: a matched failure, or the process dying before it
        // connected, is a failure. A real ready signal wins over everything but a failure.
        if (failure) return FAILED;
        if (ended || !running) return FAILED;
        if (ready) return CONNECTED;
        if (authPrompt) return AUTHENTICATING;
        return current;
    }
}
