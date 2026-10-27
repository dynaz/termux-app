package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the Local AI Bridge for the app: the per-environment tokens, the authorization bridge, and
 * the socket listener, and the setup that connects a Linux environment's Claude Code to it. It is
 * the one place the rest of the app talks to. Credentials never pass through it to Claude: it only
 * resolves them inside {@link TerminowAiBridge} for an SSH operation.
 */
public final class TerminowAiBridgeManager {

    /** Whether the keychain is unlocked, so credentials can be resolved. */
    public interface Unlocked {
        boolean get();
    }

    public static final class Status {
        public boolean enabled;
        public boolean listening;
        public int connectedEnvironments;
        public int openConnections;
        public int activeGrants;
        public int port;
        @Nullable public String error;
    }

    public static final class SetupResult {
        public final boolean ok;
        public final String message;

        SetupResult(boolean ok, @NonNull String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private final Context mContext;
    private final TerminowStore mStore;
    private final TerminowMcpTargets mTargets;
    private final Unlocked mUnlocked;
    private final File mSshHome;

    private final TerminowAiSession mSession;
    private final TerminowAiBridge mBridge;
    private final TerminowAiBridgeServer mServer;

    public TerminowAiBridgeManager(@NonNull Context context, @NonNull TerminowStore store, @NonNull TerminowMcpTargets targets,
                                   @NonNull Unlocked unlocked) {
        mContext = context;
        mStore = store;
        mTargets = targets;
        mUnlocked = unlocked;
        mSshHome = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);

        mSession = new TerminowAiSession(new TerminowAiSession.Store() {
            @Override public void save(@NonNull String envKey, @NonNull String token) { mStore.setAiToken(envKey, token); }
            @NonNull @Override public String load(@NonNull String envKey) { return mStore.getAiTokens().getOrDefault(envKey, ""); }
            @Override public void remove(@NonNull String envKey) { mStore.removeAiToken(envKey); }
            @NonNull @Override public Map<String, String> all() { return mStore.getAiTokens(); }
        });

        mBridge = new TerminowAiBridge(new TerminowAiBridge.Deps() {
            @NonNull @Override public List<TerminowStore.Host> hosts() { return mStore.getHosts(); }
            @NonNull @Override public List<TerminowStore.Identity> identities() { return mStore.getIdentities(); }
            @Override public boolean keychainUnlocked() { return mUnlocked.get(); }
            @Nullable @Override public String savedPassphrase(@NonNull String keyName) {
                String value = mStore.getKeyPassphrase(keyName);
                return value.isEmpty() ? null : value;
            }
            @NonNull @Override public File sshHome() { return mSshHome; }
        });

        mServer = new TerminowAiBridgeServer(mSession,
            env -> new TerminowAiMcpServer(mBridge, env, this::secrets, BuildConfig.VERSION_NAME));
    }

    // ---------------------------------------------------------------------------------------
    // Process-lifetime ownership
    //
    // The listener must outlive the GUI Activity: Claude Code reaches the bridge from a Linux
    // environment while TerminowHome is backgrounded or recreated, and the Termux foreground
    // service keeps the process alive while sessions run. Owning the manager here as a process
    // singleton keeps one listener on a stable port across Activity recreation, instead of a
    // per-Activity server that died on teardown and drifted to a fresh random port when the next
    // Activity re-bound (the old socket still held the saved port). The unlock check is backed by
    // the store, not an Activity, so it stays valid when no Activity is attached.
    // ---------------------------------------------------------------------------------------

    @Nullable private static volatile TerminowAiBridgeManager sInstance;

    /** The one manager for the process, created on first use with the application context. */
    @NonNull
    public static TerminowAiBridgeManager getInstance(@NonNull Context context, @NonNull TerminowStore store,
                                                      @NonNull TerminowMcpTargets targets) {
        TerminowAiBridgeManager instance = sInstance;
        if (instance == null) {
            synchronized (TerminowAiBridgeManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new TerminowAiBridgeManager(context.getApplicationContext(), store, targets,
                        storeUnlocked(store));
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /**
     * A keychain-unlocked check backed only by the store, valid at process scope with no Activity.
     * Mirrors {@link TerminowVaults#isUnlocked()}: the vault password was entered on this device
     * for the signed-in account.
     */
    @NonNull
    private static Unlocked storeUnlocked(@NonNull TerminowStore store) {
        return () -> {
            JSONObject vault = store.getVaultAccount();
            TerminowAccount.Session session = store.getAccount();
            return vault != null && session != null
                && vault.optString("email").equalsIgnoreCase(session.email);
        };
    }

    @Nullable private String mLastError;

    @NonNull
    public TerminowAiBridge getBridge() {
        return mBridge;
    }

    /** The Local AI Bridge is a Premium feature. */
    private boolean premium() {
        return TerminowAccountUi.isPremium(mStore);
    }

    /** Start the listener if the bridge is enabled and Premium is active. */
    public synchronized void startIfEnabled() {
        if (mStore.isAiBridgeEnabled() && premium() && !mServer.isRunning()) startServer();
    }

    /** Try to listen; remembers the port on success and the full error on failure. */
    private String startServer() {
        String error = mServer.start(mStore.getAiBridgePort());
        if (error == null) {
            mStore.setAiBridgePort(mServer.getPort());
            mLastError = null;
            // The port may have changed on a rebind; refresh it for every connected environment so
            // the helper reconnects to the live port instead of a stale one.
            writeConnectedPortFiles(mServer.getPort());
        } else {
            mLastError = error;
        }
        return error;
    }

    public synchronized SetupResult setEnabled(boolean enabled) {
        if (enabled && !premium()) return new SetupResult(false, "The Local AI Bridge needs TermiNow Premium");
        if (enabled) {
            String error = startServer();
            if (error != null) {
                // Not left half-on: the switch shows off and the reason is kept
                mStore.setAiBridgeEnabled(false);
                return new SetupResult(false, error);
            }
            mStore.setAiBridgeEnabled(true);
            return new SetupResult(true, "The bridge is listening on 127.0.0.1:" + mServer.getPort());
        }
        mStore.setAiBridgeEnabled(false);
        mServer.stop();
        return new SetupResult(true, "The bridge is off");
    }

    /** Stop and re-start the listener, for the UI restart action. Returns the resulting state. */
    public synchronized SetupResult restart() {
        if (!mStore.isAiBridgeEnabled()) return new SetupResult(false, "The bridge is off");
        if (!premium()) return new SetupResult(false, "The Local AI Bridge needs TermiNow Premium");
        mServer.stop();
        String error = startServer();
        if (error != null) return new SetupResult(false, error);
        return new SetupResult(true, "The bridge is listening on 127.0.0.1:" + mServer.getPort());
    }

    public synchronized boolean isEnabled() {
        return mStore.isAiBridgeEnabled();
    }

    @NonNull
    public synchronized Status status() {
        Status status = new Status();
        status.enabled = mStore.isAiBridgeEnabled();
        status.listening = mServer.isRunning();
        status.port = mServer.getPort();
        status.connectedEnvironments = mSession.count();
        status.openConnections = mServer.openConnectionCount();
        status.activeGrants = mBridge.activeGrantCount();
        status.error = mLastError;
        return status;
    }

    /** Stop every connection and forget every token and grant. */
    public synchronized void revokeAll() {
        mBridge.revokeAll();
        for (String env : new HashSet<>(mStore.getAiTokens().keySet())) mServer.closeConnectionsOf(env);
        mSession.revokeAll();
    }

    /** A host was saved: let the bridge drop grants that no longer match its endpoint or credentials. */
    public void onHostSaved(@NonNull TerminowStore.Host host) {
        mBridge.onHostSaved(host);
    }

    public void onHostRemoved(@NonNull String hostUuid) {
        mBridge.onHostRemoved(hostUuid);
    }

    /** Grant an environment a host for a while, from the approval UI ("allow for this session"). */
    public void grant(@NonNull String environmentKey, @NonNull TerminowStore.Host host, long ttlMillis) {
        mBridge.grant(environmentKey, host, ttlMillis);
    }

    // ---------------------------------------------------------------------------------------
    // Connecting Claude Code of an environment
    // ---------------------------------------------------------------------------------------

    /**
     * Install the helper into the environment, give it a fresh token, and register it with Claude
     * Code, without disturbing the other MCP servers. Idempotent: running it again updates only
     * this server. The environment key is the proot container name, the user its login user.
     */
    @NonNull
    public synchronized SetupResult connectClaude(@NonNull String environmentKey, @NonNull String user) {
        if (!premium()) return new SetupResult(false, "The Local AI Bridge needs TermiNow Premium");
        TerminowMcpTargets.Container container = mTargets.getContainer(environmentKey);
        if (container == null) return new SetupResult(false, "The environment " + environmentKey + " is not installed");
        String guestHome = TerminowMcpTargets.getGuestHome(container, user);
        File rootfs = container.rootfs;
        File guestHelper = underRootfs(rootfs, guestHome, TerminowAiHelper.GUEST_HELPER_REL);
        File guestTokenFile = underRootfs(rootfs, guestHome, TerminowAiHelper.GUEST_TOKEN_REL);
        File guestPortFile = underRootfs(rootfs, guestHome, TerminowAiHelper.GUEST_PORT_REL);
        File claudeConfig = underRootfs(rootfs, guestHome, TerminowClaudeConfig.USER_CONFIG_NAME);

        // The bridge must be listening for the helper to reach it
        if (!mServer.isRunning()) {
            String error = startServer();
            if (error != null) return new SetupResult(false, "The bridge could not start: " + error);
        }
        mStore.setAiBridgeEnabled(true);

        try {
            installAsset(TerminowAiHelper.HELPER_ASSET, guestHelper);
            // The `terminow` CLI wrapper, so `terminow bridge status|doctor` works in the environment.
            installAsset(TerminowAiHelper.CLI_ASSET, underRootfs(rootfs, guestHome, TerminowAiHelper.GUEST_CLI_REL));
            String token = mSession.mint(environmentKey);
            writeTokenFile(guestTokenFile, token);
            writePortFile(guestPortFile, mServer.getPort());

            String guestHelperPath = TerminowAiHelper.guestHelperPath(guestHome);
            String guestTokenPath = TerminowAiHelper.guestTokenPath(guestHome);
            String guestPortPath = TerminowAiHelper.guestPortPath(guestHome);
            TerminowAiHelper.Transport transport = TerminowAiHelper.Transport.loopback(mServer.getPort());
            JSONObject entry = TerminowAiHelper.registrationEntry(guestHelperPath, transport, guestTokenPath, guestPortPath);

            String existing = claudeConfig.isFile() ? TerminowClaudeConfig.read(claudeConfig) : null;
            TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planAdd(existing, "user", guestHome,
                TerminowAiHelper.SERVER_NAME, entry, null, true);
            if (plan.action == TerminowClaudeConfig.Action.CONFLICT)
                return new SetupResult(false, "A different " + TerminowAiHelper.SERVER_NAME + " server is already configured; fix it first");
            if (plan.newText != null) TerminowClaudeConfig.writeAtomically(claudeConfig, plan.newText);
            return new SetupResult(true, "Claude Code in " + container.label + " is connected. Restart Claude Code to load it.");
        } catch (TerminowClaudeConfig.ConfigException | IOException | org.json.JSONException | RuntimeException e) {
            return new SetupResult(false, String.valueOf(e.getMessage()));
        }
    }

    /** Remove the registration and the token of an environment, and stop its connections. */
    @NonNull
    public synchronized SetupResult disconnectClaude(@NonNull String environmentKey, @NonNull String user) {
        TerminowMcpTargets.Container container = mTargets.getContainer(environmentKey);
        mSession.revoke(environmentKey);
        mServer.closeConnectionsOf(environmentKey);
        if (container == null) return new SetupResult(true, "The token was revoked");
        String guestHome = TerminowMcpTargets.getGuestHome(container, user);
        File claudeConfig = underRootfs(container.rootfs, guestHome, TerminowClaudeConfig.USER_CONFIG_NAME);
        File guestTokenFile = underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_TOKEN_REL);
        File guestPortFile = underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_PORT_REL);
        try {
            if (guestTokenFile.exists() && !guestTokenFile.delete()) { /* best effort */ }
            if (guestPortFile.exists() && !guestPortFile.delete()) { /* best effort */ }
            if (claudeConfig.isFile()) {
                String existing = TerminowClaudeConfig.read(claudeConfig);
                TerminowClaudeConfig.Plan plan = TerminowClaudeConfig.planRemove(existing, "user", guestHome,
                    TerminowAiHelper.SERVER_NAME, null, true);
                if (plan.newText != null) TerminowClaudeConfig.writeAtomically(claudeConfig, plan.newText);
            }
            return new SetupResult(true, "Disconnected");
        } catch (TerminowClaudeConfig.ConfigException | RuntimeException e) {
            return new SetupResult(false, String.valueOf(e.getMessage()));
        }
    }

    public boolean hasToken(@NonNull String environmentKey) {
        return mSession.hasToken(environmentKey);
    }

    /** The installed Linux environments, for the setup list. */
    @NonNull
    public List<TerminowMcpTargets.Container> environments() {
        return mTargets.getContainers();
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private static File underRootfs(@NonNull File rootfs, @NonNull String guestAbsPath, @NonNull String guestRel) {
        String home = guestAbsPath.startsWith("/") ? guestAbsPath.substring(1) : guestAbsPath;
        return new File(new File(rootfs, home), guestRel);
    }

    private void installAsset(@NonNull String assetName, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (InputStream in = mContext.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        destination.setReadable(true, false);
        destination.setExecutable(true, false);
    }

    /** Write the bridge's current port for every connected environment, so a rebind is picked up. */
    private void writeConnectedPortFiles(int port) {
        for (String env : mStore.getAiTokens().keySet()) {
            TerminowMcpTargets.Container container = mTargets.getContainer(env);
            if (container == null) continue;
            String guestHome = TerminowMcpTargets.getGuestHome(container, "root");
            try {
                writePortFile(underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_PORT_REL), port);
            } catch (IOException ignored) {
                // Best effort; the env reconnects when its port file is next written
            }
        }
    }

    /** Write the current loopback port into the guest port file (not a secret), owner-readable. */
    private void writePortFile(@NonNull File portFile, int port) throws IOException {
        File parent = portFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (OutputStream out = new FileOutputStream(portFile)) {
            out.write(String.valueOf(port).getBytes(StandardCharsets.UTF_8));
        }
        portFile.setReadable(false, false);
        portFile.setReadable(true, true);
    }

    private void writeTokenFile(@NonNull File tokenFile, @NonNull String token) throws IOException {
        File parent = tokenFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (OutputStream out = new FileOutputStream(tokenFile)) {
            out.write(token.getBytes(StandardCharsets.UTF_8));
        }
        // Only the owner may read the token
        tokenFile.setReadable(false, false);
        tokenFile.setWritable(false, false);
        tokenFile.setReadable(true, true);
        tokenFile.setWritable(true, true);
    }

    /** The secrets to redact from anything sent back: stored passwords and key passphrases. */
    @NonNull
    private Collection<String> secrets() {
        Set<String> secrets = new HashSet<>();
        for (TerminowStore.Host host : mStore.getHosts()) if (!host.password.isEmpty()) secrets.add(host.password);
        for (TerminowStore.Identity identity : mStore.getIdentities()) if (!identity.password.isEmpty()) secrets.add(identity.password);
        for (String token : mStore.getAiTokens().values()) secrets.add(token);
        return secrets;
    }

    // ---------------------------------------------------------------------------------------
    // Verification and diagnostics
    //
    // A bound socket proves nothing about the integration. selfTest() connects to our own listener
    // as the helper would and runs the real MCP handshake + hosts_list (+ optional ssh_test), so the
    // Android side (listener, auth, MCP, tool discovery, host authorization) is actually verified.
    // diagnose() walks every layer and returns a sanitized, copyable report so the failing layer is
    // named rather than assumed. Neither reveals a token or a credential.
    // ---------------------------------------------------------------------------------------

    public enum Step { OK, WARN, FAIL }

    public static final class Diag {
        public final String label;
        public final Step step;
        public final String detail;

        Diag(@NonNull String label, @NonNull Step step, @NonNull String detail) {
            this.label = label;
            this.step = step;
            this.detail = detail;
        }
    }

    public static final class Diagnostics {
        public final List<Diag> steps;
        @Nullable public final TerminowAiBridgeProbe.Result selfTest;
        public final long at;

        Diagnostics(@NonNull List<Diag> steps, @Nullable TerminowAiBridgeProbe.Result selfTest, long at) {
            this.steps = steps;
            this.selfTest = selfTest;
            this.at = at;
        }

        public boolean ok() {
            for (Diag d : steps) if (d.step == Step.FAIL) return false;
            return true;
        }

        /** A sanitized plain-text report for the "Copy diagnostics" action. No token or credential. */
        @NonNull
        public String toText() {
            StringBuilder sb = new StringBuilder("TermiNow Local AI Bridge diagnostics\n");
            for (Diag d : steps)
                sb.append(d.step == Step.OK ? "[OK]   " : d.step == Step.WARN ? "[WARN] " : "[FAIL] ")
                  .append(d.label).append(": ").append(d.detail).append('\n');
            return sb.toString();
        }
    }

    /** The outcome of the last helper verification (self-test) for an environment, kept apart from its registration. */
    public static final class Verification {
        public final boolean ok;
        public final long at;            // epoch millis when the probe ran
        @Nullable public final String error;

        Verification(boolean ok, long at, @Nullable String error) {
            this.ok = ok;
            this.at = at;
            this.error = error;
        }
    }

    private final Map<String, Verification> mLastVerify = new HashMap<>();

    /** The last self-test outcome for an environment, or {@code null} if it has never been tested. */
    @Nullable
    public synchronized Verification lastVerification(@NonNull String environmentKey) {
        return mLastVerify.get(environmentKey);
    }

    /**
     * Run the real MCP handshake against the live listener with the environment's token (minting one
     * if needed for the test), optionally testing one host. Verifies the Android side end to end.
     */
    @NonNull
    public synchronized TerminowAiBridgeProbe.Result selfTest(@NonNull String environmentKey, @Nullable String sshTestHostId) {
        if (!mServer.isRunning()) startServer();
        String token = mSession.tokenOf(environmentKey);
        if (token.isEmpty()) token = mSession.mint(environmentKey);
        TerminowAiBridgeProbe.Result result = TerminowAiBridgeProbe.probe("127.0.0.1", mServer.getPort(), token, sshTestHostId);
        // Record the verification apart from registration: a configured environment whose helper is not
        // reachable must never read as "Connected".
        mLastVerify.put(environmentKey, new Verification(result.ok(), System.currentTimeMillis(), result.error));
        return result;
    }

    /** Walk every layer of the bridge path and return a sanitized, copyable report. */
    @NonNull
    public synchronized Diagnostics diagnose(@NonNull String environmentKey, @NonNull String user) {
        List<Diag> steps = new ArrayList<>();
        steps.add(mServer.isRunning()
            ? new Diag("Bridge service", Step.OK, "Listening on 127.0.0.1:" + mServer.getPort())
            : new Diag("Bridge service", Step.FAIL, mLastError != null ? mLastError : "Stopped"));
        steps.add(premium()
            ? new Diag("Premium", Step.OK, "active")
            : new Diag("Premium", Step.FAIL, "The Local AI Bridge needs TermiNow Premium"));

        TerminowMcpTargets.Container container = mTargets.getContainer(environmentKey);
        if (container == null) {
            steps.add(new Diag("Environment", Step.FAIL, environmentKey + " is not installed"));
            return new Diagnostics(steps, null, System.currentTimeMillis());
        }
        steps.add(new Diag("Environment", Step.OK, container.label + " (" + environmentKey + ", user " + user + ")"));
        String guestHome = TerminowMcpTargets.getGuestHome(container, user);

        File helper = underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_HELPER_REL);
        steps.add(!helper.isFile()
            ? new Diag("Ubuntu helper", Step.FAIL, "Missing — run Connect & Test")
            : helper.canExecute()
                ? new Diag("Ubuntu helper", Step.OK, "Installed at ~/" + TerminowAiHelper.GUEST_HELPER_REL)
                : new Diag("Ubuntu helper", Step.WARN, "Installed but not executable"));

        String python = guestPython3(container.rootfs);
        steps.add(python != null
            ? new Diag("python3", Step.OK, python)
            : new Diag("python3", Step.FAIL, "Not found in the environment — install it (e.g. apt install python3)"));

        boolean hasToken = mSession.hasToken(environmentKey);
        File tokenFile = underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_TOKEN_REL);
        steps.add(hasToken && tokenFile.isFile()
            ? new Diag("Token", Step.OK, "Provisioned")
            : hasToken
                ? new Diag("Token", Step.WARN, "Minted but the guest token file is missing — run Connect & Test")
                : new Diag("Token", Step.FAIL, "Not provisioned — run Connect & Test"));

        File portFile = underRootfs(container.rootfs, guestHome, TerminowAiHelper.GUEST_PORT_REL);
        steps.add(portFile.isFile()
            ? new Diag("Port file", Step.OK, "present")
            : new Diag("Port file", Step.WARN, "Missing — the helper falls back to the registered port"));

        File config = underRootfs(container.rootfs, guestHome, TerminowClaudeConfig.USER_CONFIG_NAME);
        steps.add(configHasServer(config)
            ? new Diag("Claude MCP", Step.OK, "Configured (" + TerminowAiHelper.SERVER_NAME + ") — restart Claude Code to load it")
            : new Diag("Claude MCP", Step.FAIL, "Not configured — run Connect & Test"));

        boolean connected = mServer.openConnectionCount() > 0 && mSession.hasToken(environmentKey);
        steps.add(connected
            ? new Diag("Claude connection", Step.OK, "A helper is connected now")
            : new Diag("Claude connection", Step.WARN, "No helper connected yet (open Claude Code after Connect & Test)"));

        TerminowAiBridgeProbe.Result probe = selfTest(environmentKey, null);
        if (probe.ok())
            steps.add(new Diag("Bridge self-test", Step.OK,
                "MCP " + probe.protocolVersion + ", tools " + probe.tools + ", " + probe.hostCount + " authorized host(s)"));
        else
            steps.add(new Diag("Bridge self-test", Step.FAIL, probe.error != null ? probe.error : "failed"));

        return new Diagnostics(steps, probe, System.currentTimeMillis());
    }

    /** The guest path of python3 if one is present in the environment, else null (checked on the rootfs). */
    @Nullable
    private static String guestPython3(@NonNull File rootfs) {
        for (String rel : new String[]{"usr/bin/python3", "bin/python3", "usr/local/bin/python3"}) {
            if (new File(rootfs, rel).exists()) return "/" + rel;
        }
        return null;
    }

    /** Whether the guest's user Claude config registers our MCP server (top-level, user scope). */
    private static boolean configHasServer(@NonNull File config) {
        if (!config.isFile()) return false;
        try {
            String text = TerminowClaudeConfig.read(config);
            if (text == null || text.isEmpty()) return false;
            JSONObject root = new JSONObject(text);
            JSONObject servers = root.optJSONObject("mcpServers");
            return servers != null && servers.has(TerminowAiHelper.SERVER_NAME);
        } catch (TerminowClaudeConfig.ConfigException | org.json.JSONException | RuntimeException e) {
            return false;
        }
    }
}
