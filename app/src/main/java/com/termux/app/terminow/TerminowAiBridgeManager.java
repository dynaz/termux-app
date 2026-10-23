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

        String socketPath = new File(mSshHome, ".terminow/ai/bridge.sock").getAbsolutePath();
        mServer = new TerminowAiBridgeServer(context, socketPath, mSession,
            env -> new TerminowAiMcpServer(mBridge, env, this::secrets, BuildConfig.VERSION_NAME));
    }

    @NonNull
    public TerminowAiBridge getBridge() {
        return mBridge;
    }

    @NonNull
    public String getSocketPath() {
        return mServer.getSocketPath();
    }

    /** The directory of the socket, to bind into an environment so the helper can reach it. */
    @NonNull
    public String getSocketDir() {
        File parent = new File(mServer.getSocketPath()).getParentFile();
        return parent != null ? parent.getAbsolutePath() : mServer.getSocketPath();
    }

    /** The Local AI Bridge is a Premium feature. */
    private boolean premium() {
        return TerminowAccountUi.isPremium(mStore);
    }

    /** Start the listener if the bridge is enabled and Premium is active. */
    public synchronized void startIfEnabled() {
        if (mStore.isAiBridgeEnabled() && premium() && !mServer.isRunning()) mServer.start();
    }

    public synchronized SetupResult setEnabled(boolean enabled) {
        if (enabled && !premium()) return new SetupResult(false, "The Local AI Bridge needs TermiNow Premium");
        mStore.setAiBridgeEnabled(enabled);
        if (enabled) {
            String error = mServer.start();
            return error == null ? new SetupResult(true, "The bridge is on") : new SetupResult(false, error);
        }
        mServer.stop();
        return new SetupResult(true, "The bridge is off");
    }

    public synchronized boolean isEnabled() {
        return mStore.isAiBridgeEnabled();
    }

    @NonNull
    public synchronized Status status() {
        Status status = new Status();
        status.enabled = mStore.isAiBridgeEnabled();
        status.listening = mServer.isRunning();
        status.connectedEnvironments = mSession.count();
        status.openConnections = mServer.openConnectionCount();
        status.activeGrants = mBridge.activeGrantCount();
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
        File claudeConfig = underRootfs(rootfs, guestHome, TerminowClaudeConfig.USER_CONFIG_NAME);

        // The bridge must be on for the helper to reach it
        if (!mStore.isAiBridgeEnabled()) {
            String error = mServer.start();
            if (error != null) return new SetupResult(false, "The bridge could not start: " + error);
            mStore.setAiBridgeEnabled(true);
        } else if (!mServer.isRunning()) {
            String error = mServer.start();
            if (error != null) return new SetupResult(false, "The bridge could not start: " + error);
        }

        try {
            installAsset(TerminowAiHelper.HELPER_ASSET, guestHelper);
            String token = mSession.mint(environmentKey);
            writeTokenFile(guestTokenFile, token);

            String guestHelperPath = TerminowAiHelper.guestHelperPath(guestHome);
            String guestTokenPath = TerminowAiHelper.guestTokenPath(guestHome);
            TerminowAiHelper.Transport transport = TerminowAiHelper.Transport.unix(mServer.getSocketPath());
            JSONObject entry = TerminowAiHelper.registrationEntry(guestHelperPath, transport, guestTokenPath);

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
        try {
            if (guestTokenFile.exists() && !guestTokenFile.delete()) { /* best effort */ }
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
}
