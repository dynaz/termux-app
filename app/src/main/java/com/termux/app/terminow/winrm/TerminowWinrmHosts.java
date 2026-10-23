package com.termux.app.terminow.winrm;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowRdpBridge;
import com.termux.app.terminow.TerminowStore;

import java.util.List;

/** The WinRM connection of a saved host: its address, account, transport and Linux environment. */
public final class TerminowWinrmHosts {

    private TerminowWinrmHosts() {}

    /** The target of the host with the key in the vault that is shown, {@code null} if it is gone. */
    @Nullable
    public static TerminowWinrmSession.Target targetFor(@NonNull Context context, @NonNull String hostKey) {
        TerminowStore store = new TerminowStore(context);
        List<TerminowStore.Host> hosts = store.getHosts();
        TerminowStore.Host host = TerminowStore.findHost(hosts, hostKey);
        return host != null ? targetFor(store, host) : null;
    }

    /** The target of a host, with the username and password of its identity if it has one; no environment, {@code null}. */
    @Nullable
    public static TerminowWinrmSession.Target targetFor(@NonNull TerminowStore store, @NonNull TerminowStore.Host host) {
        String container = TerminowRdpBridge.winrmContainer();
        if (container == null) return null;
        TerminowStore.Host login = TerminowStore.resolve(host, store.getIdentities());
        TerminowWinrmSession.Target target = new TerminowWinrmSession.Target();
        target.title = host.getTitle();
        target.hostKey = host.getKey();
        target.address = host.address;
        target.port = host.port;
        target.https = host.winrmHttps;
        target.verify = host.winrmVerify;
        target.username = login.username;
        target.domain = host.domain;
        target.password = login.password;
        target.container = container;
        return target;
    }
}
