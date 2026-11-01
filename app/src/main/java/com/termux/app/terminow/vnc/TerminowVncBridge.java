package com.termux.app.terminow.vnc;

import androidx.annotation.NonNull;

import com.termux.app.terminow.TerminowStore;

/**
 * Builds a {@link TerminowVncSession.Target} from a saved {@link TerminowStore.Host}. The host's
 * password is already decrypted by {@link TerminowStore#getHosts()}; it is copied into a char array
 * that the session wipes after the handshake, and it is never written to a log.
 */
public final class TerminowVncBridge {

    private TerminowVncBridge() {}

    @NonNull
    public static TerminowVncSession.Target vncTarget(@NonNull TerminowStore.Host host) {
        TerminowVncSession.Target target = new TerminowVncSession.Target();
        target.title = host.label.isEmpty() ? host.address : host.label;
        target.hostKey = host.getKey();
        target.address = host.address;
        target.port = host.port > 0 ? host.port : 5900;
        target.password = host.password == null || host.password.isEmpty() ? null : host.password.toCharArray();
        target.viewOnly = host.vncViewOnly;
        return target;
    }
}
