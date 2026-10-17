package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/** What the RDP and WinRM screens use of the rest of TermiNow, which keeps its internals to itself. */
public final class TerminowRdpBridge {

    private TerminowRdpBridge() {}

    /** Why a host can not be connected to, for the message the user sees. */
    public static class TargetException extends Exception {
        public TargetException(@NonNull String message) {
            super(message);
        }
    }

    /**
     * What an RDP connection to a saved host needs: its address, port and account, with the
     * username, password and domain of its identity if it has one, and the ssh of its tunnel
     * when it goes through a jump host.
     *
     * @param width  The size of the desktop for a host that takes the size of the screen.
     */
    @NonNull
    public static com.termux.app.terminow.rdp.TerminowRdpSession.Target rdpTarget(@NonNull TerminowStore store,
                                                                               @NonNull TerminowStore.Host host,
                                                                               @NonNull java.util.List<TerminowStore.Host> hosts,
                                                                               int width, int height) throws TargetException {
        if (!TerminowStore.isRunnable(host)) throw new TargetException("invalid");
        java.util.List<TerminowStore.Identity> identities = store.getIdentities();
        TerminowStore.Host login = TerminowStore.resolve(host, identities);
        com.termux.app.terminow.rdp.TerminowRdpSession.Target target = new com.termux.app.terminow.rdp.TerminowRdpSession.Target();
        target.title = host.getTitle();
        target.hostKey = host.getKey();
        target.address = host.address;
        target.port = host.port;
        target.username = login.username;
        target.password = login.password;
        target.domain = host.domain;
        target.nla = host.rdpNla;
        target.clipboard = host.rdpClipboard;
        target.width = host.rdpWidth > 0 ? host.rdpWidth : width;
        target.height = host.rdpHeight > 0 ? host.rdpHeight : height;
        if (!host.jumpHost.isEmpty()) {
            TerminowStore.Host jump = TerminowStore.findHost(hosts, host.jumpHost);
            if (jump == null || !jump.isTerminal() || jump.telnet) throw new TargetException("jump");
            TerminowStore.Host jumpLogin = TerminowStore.resolve(jump, identities);
            TerminowStore.Host jumpOfJump = jump.jumpHost.isEmpty() ? null : TerminowStore.findHost(hosts, jump.jumpHost);
            if (jumpOfJump != null) jumpOfJump = TerminowStore.resolve(jumpOfJump, identities);
            String proxyConnector = "";
            if (!jump.proxyType.isEmpty()) {
                try {
                    proxyConnector = TerminowProxy.install(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)).getPath();
                } catch (IOException e) {
                    throw new TargetException("proxy");
                }
            }
            target.tunnelCommand = jumpLogin.getTunnelCommand(jumpOfJump, proxyConnector, store.getKeepAliveInterval(), store.getKeepAliveCountMax());
            target.tunnelTitle = jump.getTitle();
        }
        return target;
    }

    /**
     * The Linux environment that WinRM runs in: an Ubuntu one if there is, else another
     * installed one, {@code null} when none is installed.
     */
    @androidx.annotation.Nullable
    public static String winrmContainer() {
        TerminowMcpTargets targets = new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        String found = null;
        for (TerminowMcpTargets.Container container : targets.getContainers()) {
            if (container.isUbuntu) return container.name;
            if (found == null) found = container.name;
        }
        return found;
    }

    /**
     * Run a program in a Linux environment as root, with its arguments as they are: none is a
     * credential, which only ever goes to its standard input.
     */
    @NonNull
    public static Process startInContainer(@NonNull Context context, @NonNull String container, @NonNull java.util.List<String> program)
        throws IOException {
        if (!TerminowMcpTargets.isValidContainerName(container)) throw new IOException("Invalid environment");
        java.util.List<String> command = new java.util.ArrayList<>(Arrays.asList(
            new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/proot-distro").getPath(), "login", container, "--"));
        command.addAll(program);
        return TerminowClaudeSetup.startInTerminal(context, command, Collections.<String, String>emptyMap(),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
    }

    /**
     * Keep the username, domain and password that the user typed for a host in the host,
     * encrypted with the other passwords of TermiNow. Only when the user asked for it.
     */
    public static boolean saveCredentials(@NonNull Context context, @NonNull String hostKey, @NonNull String username,
                                          @NonNull String domain, @NonNull String password) {
        TerminowStore store = new TerminowStore(context);
        java.util.List<TerminowStore.Host> hosts = store.getHosts();
        for (TerminowStore.Host host : hosts) {
            if (!host.getKey().equals(hostKey)) continue;
            // An identity keeps its own credentials: they are not split into the host
            if (!host.identity.isEmpty()) return false;
            if (!TerminowStore.isValidUsername(username) || !TerminowStore.isValidDomain(domain)) return false;
            host.username = username;
            host.domain = domain;
            host.password = password;
            store.setHosts(hosts);
            return true;
        }
        return false;
    }

    /**
     * Run a command line of TermiNow, like the ssh of a tunnel, with the environment of its
     * terminal. Only command lines that TermiNow made of validated host fields are run here.
     */
    @NonNull
    public static Process startInTerminal(@NonNull Context context, @NonNull String commandLine) throws IOException {
        return TerminowClaudeSetup.startInTerminal(context, Arrays.asList("bash", "-c", commandLine),
            Collections.<String, String>emptyMap(), new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
    }
}
