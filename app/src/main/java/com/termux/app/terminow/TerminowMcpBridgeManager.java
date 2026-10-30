package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.termux.app.terminow.files.TerminowSftpFs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the TermiNow MCP Bridge for the app: the paired-client tokens, the shared sessions, the
 * authorization and task-lifecycle {@link TerminowMcpBridge}, the managed {@link
 * TerminowMcpBridgeExecutor}, and the device-local loopback transport. It is the one place the UI
 * and the relay connector talk to.
 *
 * <p>Activation is a Premium feature and gated through {@link TerminowAccountUi#isPremium}, but
 * stopping the bridge, revoking a client and cancelling a task are never gated, so an emergency
 * stop always works. Pairing tokens are the client's authority: the transport matches a token to a
 * client, and the bridge checks the client, the share and the capability on every call. A revoked
 * token stops a client at once, and its open connections are closed.</p>
 */
public final class TerminowMcpBridgeManager {

    /**
     * The intended public relay, used to prefill the relay field; editable in the UI and
     * overridable at build time. The device dials the base; AI clients use {@code <base>/mcp}.
     */
    public static final String DEFAULT_RELAY_URL = BuildConfig.MCP_RELAY_URL;

    public static final class Status {
        public boolean enabled;
        public boolean listening;
        public int port;
        public int pairedClients;
        public int sharedSessions;
        public int activeTasks;
        public int openConnections;
        @Nullable public String error;
        @Nullable public String relayState;
    }

    public static final class PairResult {
        public final String clientId;
        /** The pairing secret, shown once so the user can give it to the AI client. */
        public final String token;
        public final String label;

        PairResult(@NonNull String clientId, @NonNull String token, @NonNull String label) {
            this.clientId = clientId;
            this.token = token;
            this.label = label;
        }
    }

    private final Context mContext;
    private final TerminowStore mStore;
    private final TerminowMcpTargets mTargets;

    private final TerminowAiSession mClients;
    private final TerminowMcpBridge mBridge;
    private final TerminowMcpBridgeExecutor mExecutor;
    private final TerminowAiBridgeServer mServer;
    private final TerminowMcpBridgeRelay mRelay;
    /** For enrolling this device with the relay using the account's Firebase ID token. */
    private final TerminowAccount mAccount;
    private final ScheduledExecutorService mEnroll = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TerminowMcpBridge enroll");
        t.setDaemon(true);
        return t;
    });
    @Nullable private ScheduledFuture<?> mReenroll;
    private static final long ENROLL_RENEW_BEFORE_MS = 5 * 60 * 1000;
    private static final long ENROLL_RETRY_MS = 2 * 60 * 1000;

    @Nullable private String mLastError;

    public TerminowMcpBridgeManager(@NonNull Context context, @NonNull TerminowStore store,
                                    @NonNull TerminowMcpTargets targets) {
        mContext = context.getApplicationContext();
        mStore = store;
        mTargets = targets;

        // Paired-client tokens, reusing the per-peer token manager with MCP-client storage
        mClients = new TerminowAiSession(new TerminowAiSession.Store() {
            @Override public void save(@NonNull String id, @NonNull String token) { mStore.setMcpClient(id, token, ""); }
            @NonNull @Override public String load(@NonNull String id) { return mStore.getMcpClientTokens().getOrDefault(id, ""); }
            @Override public void remove(@NonNull String id) { mStore.removeMcpClient(id); }
            @NonNull @Override public Map<String, String> all() { return mStore.getMcpClientTokens(); }
        });

        mExecutor = new TerminowMcpBridgeExecutor(new TerminowClaudeSetup(mContext, mTargets), mTargets,
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".terminow/mcp-tasks"),
            this::grantHostForTask, this::startRemoteTask);

        mBridge = new TerminowMcpBridge(new TerminowMcpBridge.Deps() {
            @NonNull @Override public List<TerminowMcpBridge.Share> shares() { return loadShares(); }
            @Override public boolean clientPaired(@NonNull String clientKey) { return mClients.hasToken(clientKey); }
            @Override public int maxQueue() { return mStore.getMcpBridgeMaxQueue(); }
        }, mExecutor, new TerminowMcpBridge.Persistence() {
            @Override public void save(@NonNull List<TerminowMcpBridgeTask> tasks) { saveTasks(tasks); }
            @NonNull @Override public List<TerminowMcpBridgeTask> load() { return loadTasks(); }
        });

        mServer = new TerminowAiBridgeServer(mClients,
            // The relay keys handlers by its own pairing client key; the AI-bridge per-process client
            // id does not apply here, so it is ignored.
            (clientKey, clientId) -> new TerminowMcpBridgeMcpServer(mBridge, clientKey, this::secrets, BuildConfig.VERSION_NAME));

        // The relay dials out to a remote MCP endpoint; its traffic is answered as the relay's own
        // paired client, so the same per-session authorization applies to remote AI clients.
        mRelay = new TerminowMcpBridgeRelay(
            () -> new TerminowMcpBridgeMcpServer(mBridge, relayClientKey(), this::secrets, BuildConfig.VERSION_NAME),
            this::relayPairingCodes,
            this::reenrollRelayDevice);

        mAccount = new TerminowAccount(BuildConfig.TERMINOW_FIREBASE_API_KEY, mContext.getPackageName());
        mAccount.setFirebaseBackend(BuildConfig.TERMINOW_FIREBASE_PROJECT_ID, BuildConfig.TERMINOW_FUNCTIONS_REGION);
    }

    /**
     * The pairing codes to register with the relay: the tokens shown in "Pair a client". The relay's
     * own entry (its clientId is {@link #relayClientKey()}, whose token is the device token) is left
     * out, so the device token is never registered as a pairing code.
     */
    @NonNull
    private synchronized List<String> relayPairingCodes() {
        String relayKey = relayClientKey();
        List<String> codes = new ArrayList<>();
        for (Map.Entry<String, String> entry : mStore.getMcpClientTokens().entrySet())
            if (!entry.getKey().equals(relayKey) && !entry.getValue().isEmpty()) codes.add(entry.getValue());
        return codes;
    }

    // ---------------------------------------------------------------------------------------
    // Process-lifetime ownership (the listener must outlive the GUI Activity; see the AI bridge)
    // ---------------------------------------------------------------------------------------

    @Nullable private static volatile TerminowMcpBridgeManager sInstance;

    @NonNull
    public static TerminowMcpBridgeManager getInstance(@NonNull Context context, @NonNull TerminowStore store,
                                                       @NonNull TerminowMcpTargets targets) {
        TerminowMcpBridgeManager instance = sInstance;
        if (instance == null) {
            synchronized (TerminowMcpBridgeManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new TerminowMcpBridgeManager(context.getApplicationContext(), store, targets);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    @NonNull
    public TerminowMcpBridge getBridge() {
        return mBridge;
    }

    private boolean premium() {
        return TerminowAccountUi.isPremium(mStore);
    }

    // ---------------------------------------------------------------------------------------
    // Enable / disable the local listener
    // ---------------------------------------------------------------------------------------

    public synchronized void startIfEnabled() {
        if (mStore.isMcpBridgeEnabled() && premium()) {
            if (!mServer.isRunning()) startServer();
            startRelayIfConfigured();
        }
    }

    @Nullable
    private String startServer() {
        String error = mServer.start(mStore.getMcpBridgePort());
        if (error == null) {
            mStore.setMcpBridgePort(mServer.getPort());
            mLastError = null;
        } else {
            mLastError = error;
        }
        return error;
    }

    @NonNull
    public synchronized TerminowAiBridgeManager.SetupResult setEnabled(boolean enabled) {
        if (enabled && !premium()) return result(false, "The MCP Bridge needs TermiNow Premium");
        if (enabled) {
            String error = startServer();
            if (error != null) {
                mStore.setMcpBridgeEnabled(false);
                return result(false, error);
            }
            mStore.setMcpBridgeEnabled(true);
            startRelayIfConfigured();
            return result(true, "The MCP Bridge is listening on 127.0.0.1:" + mServer.getPort());
        }
        // Stopping is never gated: an emergency stop always works
        mStore.setMcpBridgeEnabled(false);
        mServer.stop();
        mRelay.stop();
        cancelReenroll();
        return result(true, "The MCP Bridge is off");
    }

    public synchronized boolean isEnabled() {
        return mStore.isMcpBridgeEnabled();
    }

    // ---------------------------------------------------------------------------------------
    // Pairing clients
    // ---------------------------------------------------------------------------------------

    /** Pair a new AI client, returning its one-time token. Gated on Premium. */
    @NonNull
    public synchronized PairResult pairClient(@NonNull String label) {
        if (!premium()) throw new IllegalStateException("The MCP Bridge needs TermiNow Premium");
        String clientId = "client-" + UUID.randomUUID();
        String token = mClients.mint(clientId);
        String safeLabel = label.trim().isEmpty() ? "AI client" : label.trim();
        mStore.setMcpClient(clientId, token, safeLabel);
        mRelay.refreshPairingCodes();
        return new PairResult(clientId, token, safeLabel);
    }

    /** Revoke a client: its token stops working and its open connections are closed. Never gated. */
    public synchronized void revokeClient(@NonNull String clientId) {
        mClients.revoke(clientId);
        mServer.closeConnectionsOf(clientId);
        mRelay.refreshPairingCodes();
    }

    public synchronized void revokeAllClients() {
        for (String id : new HashSet<>(mStore.getMcpClientTokens().keySet())) mServer.closeConnectionsOf(id);
        mClients.revokeAll();
        mRelay.refreshPairingCodes();
    }

    /** The paired clients for the UI: id, label, pairing time. No token is returned. */
    @NonNull
    public synchronized List<JSONObject> pairedClients() {
        List<JSONObject> clients = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : mStore.getMcpClientsMeta().entrySet()) {
            try {
                clients.add(new JSONObject()
                    .put("id", entry.getKey())
                    .put("label", entry.getValue().optString("label"))
                    .put("createdAt", entry.getValue().optLong("createdAt")));
            } catch (JSONException ignored) {
                // Skip a malformed entry
            }
        }
        return clients;
    }

    // ---------------------------------------------------------------------------------------
    // Shared sessions
    // ---------------------------------------------------------------------------------------

    @NonNull
    public synchronized List<TerminowMcpBridge.Share> listShares() {
        return loadShares();
    }

    /**
     * Add or replace a shared session. If its target changed, bound tasks are interrupted. Only
     * narrowing an existing share's capabilities is never gated, so access can always be reduced.
     */
    public synchronized void putShare(@NonNull TerminowMcpBridge.Share share) {
        List<TerminowMcpBridge.Share> shares = loadShares();
        if (!premium() && !narrowsExisting(shares, share))
            throw new IllegalStateException("The MCP Bridge needs TermiNow Premium");
        List<TerminowMcpBridge.Share> next = new ArrayList<>();
        boolean replaced = false;
        for (TerminowMcpBridge.Share existing : shares) {
            if (existing.id.equals(share.id)) {
                next.add(share);
                replaced = true;
                // A changed target must not keep running a task bound to the old one
                if (!existing.fingerprint().equals(share.fingerprint())) mBridge.onShareRemoved(share.id);
            } else {
                next.add(existing);
            }
        }
        if (!replaced) next.add(share);
        saveShares(next);
    }

    /** Whether {@code share} only removes capabilities from an existing share with the same target. */
    static boolean narrowsExisting(@NonNull List<TerminowMcpBridge.Share> shares, @NonNull TerminowMcpBridge.Share share) {
        for (TerminowMcpBridge.Share existing : shares) {
            if (!existing.id.equals(share.id)) continue;
            return existing.fingerprint().equals(share.fingerprint())
                && existing.capabilities.containsAll(share.capabilities);
        }
        return false;
    }

    public synchronized void removeShare(@NonNull String sessionId) {
        List<TerminowMcpBridge.Share> shares = loadShares();
        List<TerminowMcpBridge.Share> next = new ArrayList<>();
        for (TerminowMcpBridge.Share existing : shares) if (!existing.id.equals(sessionId)) next.add(existing);
        saveShares(next);
        // Stopping and not replaying work of an unshared session is never gated
        mBridge.onShareRemoved(sessionId);
    }

    // ---------------------------------------------------------------------------------------
    // Relay: the outbound connection to a remote MCP endpoint
    // ---------------------------------------------------------------------------------------

    /**
     * Configure the relay: store its URL and a freshly minted device token (the relay's paired
     * client credential), then connect if the bridge is enabled. The device token is returned once
     * so it can be placed in the relay server's configuration. Gated on Premium.
     */
    @NonNull
    public synchronized String configureRelay(@NonNull String baseUrl) {
        if (!premium()) throw new IllegalStateException("The MCP Bridge needs TermiNow Premium");
        String clientId = relayClientKey();
        if (clientId.isEmpty()) clientId = "relay-" + UUID.randomUUID();
        String deviceToken = mClients.mint(clientId);
        mStore.setMcpClient(clientId, deviceToken, "Relay");
        try {
            mStore.setMcpRelay(new JSONObject().put("url", normalizeRelayBase(baseUrl)).put("clientId", clientId));
        } catch (JSONException ignored) {
            // The URL still takes effect below
        }
        if (mStore.isMcpBridgeEnabled() && premium()) startRelayIfConfigured();
        return deviceToken;
    }

    /** Turn the relay off and forget its settings and credential. Never gated. */
    public synchronized void clearRelay() {
        String clientId = relayClientKey();
        mRelay.stop();
        cancelReenroll();
        mStore.setMcpRelayCredential("", 0);
        if (!clientId.isEmpty()) {
            mClients.revoke(clientId);
            mServer.closeConnectionsOf(clientId);
        }
        mStore.setMcpRelay(new JSONObject());
    }

    private void startRelayIfConfigured() {
        JSONObject relay = mStore.getMcpRelay();
        String url = relay.optString("url");
        if (url.isEmpty()) return;
        // When the account is signed in, enrol this device per-user with its Firebase ID token so
        // the relay authorizes it against central Premium. Otherwise fall back to the static token.
        if (mStore.getAccount() != null) {
            mEnroll.execute(() -> enrollAndStart(url));
        } else {
            String clientId = relay.optString("clientId");
            String token = clientId.isEmpty() ? "" : mStore.getMcpClientTokens().getOrDefault(clientId, "");
            if (!token.isEmpty() && !mRelay.isActiveWith(url, token)) mRelay.start(url, token);
        }
    }

    /** Enrol (or reuse a live credential), connect the relay with it, and schedule the next renewal. */
    private void enrollAndStart(@NonNull String url) {
        String credential = mStore.getMcpRelayCredential();
        long expiry = mStore.getMcpRelayCredentialExpiry();
        long now = System.currentTimeMillis();
        if (credential.isEmpty() || expiry - now < ENROLL_RENEW_BEFORE_MS) {
            long[] expiryOut = new long[1];
            credential = enrollRelayDevice(url, expiryOut);
            if (credential == null) {
                // Not signed in, not Premium, or revoked: stop the relay and keep nothing.
                mRelay.stop();
                mStore.setMcpRelayCredential("", 0);
                scheduleReenroll(url, ENROLL_RETRY_MS);
                return;
            }
            expiry = expiryOut[0];
            mStore.setMcpRelayCredential(credential, expiry);
        }
        // Only (re)start when not already connected with this credential: a repeat call (activity
        // recreated, renew timer with a still-valid credential) must not churn a healthy connection.
        if (!mRelay.isActiveWith(url, credential)) mRelay.start(url, credential);
        long renewIn = Math.max(ENROLL_RETRY_MS, expiry - System.currentTimeMillis() - ENROLL_RENEW_BEFORE_MS);
        scheduleReenroll(url, renewIn);
    }

    private synchronized void scheduleReenroll(@NonNull String url, long delayMs) {
        if (mReenroll != null) mReenroll.cancel(false);
        if (!mStore.isMcpBridgeEnabled()) return;
        mReenroll = mEnroll.schedule(() -> enrollAndStart(url), delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelReenroll() {
        if (mReenroll != null) {
            mReenroll.cancel(false);
            mReenroll = null;
        }
    }

    /**
     * Re-enrol this device for a fresh relay credential, invoked by the connector when the relay
     * rejects the current one (HTTP 401) — e.g. the credential expired or the relay restarted and
     * had not yet persisted it. Stores and returns the new credential, or {@code null} when the
     * device cannot enrol (not signed in, not Premium, or revoked). This is the device channel only
     * and never touches the user's OAuth sessions. Runs on the connector's background thread; the
     * connector bounds how often it calls this and backs off, so there is no reconnect loop.
     */
    @Nullable
    private String reenrollRelayDevice() {
        String url = relayUrl();
        if (url.isEmpty()) return null;
        long[] expiryOut = new long[1];
        String credential = enrollRelayDevice(url, expiryOut);
        if (credential != null) mStore.setMcpRelayCredential(credential, expiryOut[0]);
        return credential;
    }

    /**
     * Enrol this device with the relay: send a fresh Firebase ID token to /device/enroll and keep
     * the short-lived device credential it returns. Returns the credential (and its expiry in
     * {@code expiryOut}), or {@code null} when not signed in, not Premium, or the relay refused.
     * The ID token and the credential are never logged.
     */
    @Nullable
    private String enrollRelayDevice(@NonNull String url, @NonNull long[] expiryOut) {
        TerminowAccount.Session session = mStore.getAccount();
        if (session == null) return null;
        String idToken;
        try {
            idToken = mAccount.getIdToken(session);
            mStore.setAccount(session); // persist a refreshed refresh token, if it changed
        } catch (TerminowAccount.AccountException e) {
            return null; // signed out or cannot refresh
        }
        if (idToken.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url + "/device/enroll").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            try (OutputStream out = connection.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code != 200) return null; // 401 bad token, 403 not Premium, 5xx try later
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject result = new JSONObject(body.toString());
            String credential = result.optString("device_token");
            long expiresIn = result.optLong("expires_in");
            if (credential.isEmpty() || expiresIn <= 0) return null;
            expiryOut[0] = System.currentTimeMillis() + expiresIn * 1000;
            return credential;
        } catch (java.io.IOException | JSONException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private String relayClientKey() {
        return mStore.getMcpRelay().optString("clientId");
    }

    @NonNull
    public synchronized String relayUrl() {
        return mStore.getMcpRelay().optString("url");
    }

    /** The public MCP endpoint a remote AI client uses, derived from the relay base. */
    @NonNull
    public String publicMcpEndpoint(@NonNull String base) {
        return normalizeRelayBase(base) + "/mcp";
    }

    /** The device dials the relay base; accept a pasted {@code .../mcp} endpoint and strip it. */
    @NonNull
    static String normalizeRelayBase(@NonNull String url) {
        String base = url.trim().replaceAll("/+$", "");
        if (base.endsWith("/mcp")) base = base.substring(0, base.length() - "/mcp".length());
        return base.replaceAll("/+$", "");
    }

    /** The installed Linux environments that can host a managed Claude session, for the share picker. */
    @NonNull
    public List<TerminowMcpTargets.Container> environments() {
        return mTargets.getContainers();
    }

    @NonNull
    public TerminowMcpBridgeRelay.State relayState() {
        return mRelay.state();
    }

    // ---------------------------------------------------------------------------------------
    // Status and device-side task control
    // ---------------------------------------------------------------------------------------

    @NonNull
    public synchronized Status status() {
        Status status = new Status();
        status.enabled = mStore.isMcpBridgeEnabled();
        status.listening = mServer.isRunning();
        status.port = mServer.getPort();
        status.pairedClients = mClients.count();
        status.sharedSessions = loadShares().size();
        status.activeTasks = mBridge.activeTaskCount();
        status.openConnections = mServer.openConnectionCount();
        status.error = mLastError;
        status.relayState = relayUrl().isEmpty() ? null : mRelay.state().name();
        return status;
    }

    @NonNull
    public List<TerminowMcpBridgeTask> tasks() {
        return mBridge.allTasks();
    }

    /** The file a task's stream is mirrored to, for opening it live in a terminal tab. */
    @Nullable
    public File taskLogFile(@NonNull String taskId) {
        return mExecutor.logFile(taskId);
    }

    /** From the UI: cancel a task regardless of the client. Never gated. */
    public void cancelTask(@NonNull String taskId) {
        mBridge.cancelFromDevice(taskId);
    }

    /** From the UI: retry a finished task as a fresh attempt to the same session (linked by lineage). */
    @NonNull
    public TerminowMcpBridgeTask retryTask(@NonNull String taskId) throws TerminowAiError.Exception {
        return mBridge.retryFromDevice(taskId);
    }

    /**
     * Mode A: grant the share's SSH host to its local Claude environment for a running task, scoped
     * with a bounded TTL so it auto-expires — never ambient all-host access. Invoked by the executor
     * just before a Mode-A task runs. Throws if the host is gone, which fails the task.
     */
    private void grantHostForTask(@NonNull TerminowMcpBridge.Share share, int timeoutSeconds) {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mStore.getHosts(), share.hostUuid);
        if (host == null)
            throw new IllegalStateException("The SSH host for this session is no longer in the vault");
        long ttl = Math.max(60_000L, ((long) timeoutSeconds + 60) * 1000L);
        TerminowAiBridgeManager.getInstance(mContext, mStore, mTargets).grant(share.environmentKey, host, ttl);
    }

    /**
     * Mode B: start a task's managed Claude on the share's SSH host over an exec channel, reusing the
     * host's Vault credentials and its verified known-hosts key. Fails closed — it never prompts and
     * never logs in automatically, so an unknown host key or a credential it cannot supply throws and
     * fails the task. The returned process closes its SSH session when it is destroyed.
     */
    @NonNull
    private Process startRemoteTask(@NonNull TerminowMcpBridge.Share share, @NonNull String command) throws IOException {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mStore.getHosts(), share.hostUuid);
        if (host == null) throw new IOException("The SSH host for this session is no longer in the vault");
        if (host.telnet) throw new IOException("Remote Claude needs SSH; this host uses telnet");
        if (host.address.isEmpty()) throw new IOException("The SSH host has no address");
        File home = new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH);
        TerminowSftpFs fs = new TerminowSftpFs(home, "mcp-remote:" + share.id, host.getTitle(),
            host.address, host.port, host.username, host.password, host.keyPath, FAIL_CLOSED_PROMPTS);
        try {
            return new RemoteProcess(fs.exec(command), fs);
        } catch (IOException | RuntimeException e) {
            fs.close();
            throw e;
        }
    }

    /**
     * Mode B preflight: check whether the share's SSH host is ready to run a managed Claude — host key
     * trusted, a shell and the Claude CLI present, the folder there — reusing the host's Vault
     * credentials. Read-only: it never installs or logs in automatically. With {@code trustHostKey}
     * the host's current key is accepted and remembered (the person confirmed it in the UI). Runs on
     * the caller's background thread.
     */
    @NonNull
    TerminowMcpPreflight.Result remotePreflight(@NonNull TerminowMcpBridge.Share share, boolean trustHostKey) {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mStore.getHosts(), share.hostUuid);
        if (host == null) return TerminowMcpPreflight.error("The SSH host for this session is no longer in the vault");
        if (host.telnet) return TerminowMcpPreflight.error("Remote Claude needs SSH; this host uses telnet");
        if (host.address.isEmpty()) return TerminowMcpPreflight.error("The SSH host has no address");
        File home = new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH);
        PreflightPrompts prompts = new PreflightPrompts(trustHostKey);
        TerminowSftpFs fs = new TerminowSftpFs(home, "mcp-preflight:" + share.id, host.getTitle(),
            host.address, host.port, host.username, host.password, host.keyPath, prompts);
        try {
            Process p = fs.exec(TerminowMcpPreflight.buildCommand(share.folder));
            String out = readBounded(p.getInputStream(), TerminowMcpPreflight.OUTPUT_CAP);
            try { p.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return TerminowMcpPreflight.parse(out);
        } catch (IOException | RuntimeException e) {
            if (!trustHostKey && !prompts.hostKeyInfo.isEmpty())
                return TerminowMcpPreflight.untrustedHostKey(prompts.host, prompts.hostKeyInfo);
            return TerminowMcpPreflight.error(String.valueOf(e.getMessage()));
        } finally {
            fs.close();
        }
    }

    private static String readBounded(@NonNull InputStream in, int cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while (buf.size() < cap && (n = in.read(chunk)) != -1) buf.write(chunk, 0, Math.min(n, cap - buf.size()));
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Preflight prompts: capture an unknown host key (so the UI can offer to trust it) and never ask
     *  for a password. {@code trust} accepts and remembers the host's current key. */
    private static final class PreflightPrompts implements TerminowSftpFs.Prompts {
        final boolean trust;
        volatile String host = "";
        volatile String hostKeyInfo = "";
        PreflightPrompts(boolean trust) { this.trust = trust; }
        @Override public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            this.host = host;
            this.hostKeyInfo = keyType + " " + fingerprint;
            return trust;
        }
        @Nullable @Override public String askPassword(@NonNull String prompt) { return null; }
        @Nullable @Override public String askPassphrase(@NonNull String keyName) { return null; }
    }

    /** Never prompts: an unknown host key or a missing credential fails the connection (fail-closed). */
    private static final TerminowSftpFs.Prompts FAIL_CLOSED_PROMPTS = new TerminowSftpFs.Prompts() {
        @Override public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            return false; // an untrusted/unknown host key is never accepted automatically
        }
        @Nullable @Override public String askPassword(@NonNull String prompt) { return null; }
        @Nullable @Override public String askPassphrase(@NonNull String keyName) { return null; }
    };

    /** A remote exec process that also closes its SSH session when it is destroyed. */
    private static final class RemoteProcess extends Process {
        private final Process mInner;
        private final TerminowSftpFs mFs;

        RemoteProcess(@NonNull Process inner, @NonNull TerminowSftpFs fs) { mInner = inner; mFs = fs; }

        @NonNull @Override public OutputStream getOutputStream() { return mInner.getOutputStream(); }
        @NonNull @Override public InputStream getInputStream() { return mInner.getInputStream(); }
        @NonNull @Override public InputStream getErrorStream() { return mInner.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return mInner.waitFor(); }
        @Override public int exitValue() { return mInner.exitValue(); }
        @Override public void destroy() {
            try { mInner.destroy(); } finally { mFs.close(); }
        }
    }

    /** Emergency stop: turn the bridge off, drop the relay, and revoke every client at once. */
    public synchronized void emergencyStop() {
        mStore.setMcpBridgeEnabled(false);
        mServer.stop();
        mRelay.stop();
        cancelReenroll();
        mStore.setMcpRelayCredential("", 0);
        revokeAllClients();
    }

    // ---------------------------------------------------------------------------------------
    // Share persistence (JSON <-> Share)
    // ---------------------------------------------------------------------------------------

    @NonNull
    private List<TerminowMcpBridge.Share> loadShares() {
        List<TerminowMcpBridge.Share> shares = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mStore.getMcpSharesJson());
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                List<String> caps = new ArrayList<>();
                JSONArray capArray = json.optJSONArray("capabilities");
                for (int j = 0; capArray != null && j < capArray.length(); j++) caps.add(capArray.optString(j));
                // An old share has no target_type and loads as a local share (migration-safe).
                shares.add(new TerminowMcpBridge.Share(
                    json.optString("id"), json.optString("label"), json.optBoolean("managed", true),
                    json.optString("environment"), json.optString("user"), json.optString("folder"), caps,
                    json.optString("target_type", TerminowMcpBridge.SHARE_LOCAL),
                    json.optString("executor_mode", TerminowMcpBridge.MODE_LOCAL_TOOLS),
                    json.optString("host_uuid"), json.optString("host_identity")));
            }
        } catch (JSONException e) {
            // A corrupt list is treated as empty rather than crashing the bridge
        }
        return shares;
    }

    private void saveShares(@NonNull List<TerminowMcpBridge.Share> shares) {
        JSONArray array = new JSONArray();
        try {
            for (TerminowMcpBridge.Share share : shares) {
                array.put(new JSONObject()
                    .put("id", share.id)
                    .put("label", share.label)
                    .put("managed", share.managed)
                    .put("environment", share.environmentKey)
                    .put("user", share.user)
                    .put("folder", share.folder)
                    .put("capabilities", new JSONArray(share.capabilities))
                    .put("target_type", share.shareType)
                    .put("executor_mode", share.executorMode)
                    .put("host_uuid", share.hostUuid)
                    .put("host_identity", share.hostIdentity));
            }
        } catch (JSONException ignored) {
            return;
        }
        mStore.setMcpSharesJson(array.toString());
    }

    @NonNull
    private List<TerminowMcpBridgeTask> loadTasks() {
        List<TerminowMcpBridgeTask> tasks = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mStore.getMcpTasksJson());
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) tasks.add(TerminowMcpBridgeTask.fromJson(json));
            }
        } catch (JSONException e) {
            // A corrupt list is treated as empty
        }
        return tasks;
    }

    private void saveTasks(@NonNull List<TerminowMcpBridgeTask> tasks) {
        JSONArray array = new JSONArray();
        try {
            for (TerminowMcpBridgeTask task : tasks) array.put(task.toJson());
        } catch (JSONException ignored) {
            return;
        }
        mStore.setMcpTasksJson(array.toString());
    }

    /** The secrets to redact from anything returned: client tokens and stored host passwords. */
    @NonNull
    private Collection<String> secrets() {
        Set<String> secrets = new HashSet<>();
        secrets.addAll(mStore.getMcpClientTokens().values());
        for (TerminowStore.Host host : mStore.getHosts()) if (!host.password.isEmpty()) secrets.add(host.password);
        return secrets;
    }

    @NonNull
    private static TerminowAiBridgeManager.SetupResult result(boolean ok, @NonNull String message) {
        return new TerminowAiBridgeManager.SetupResult(ok, message);
    }

    /** Convenience for building a managed share with all capabilities from a session target. */
    @NonNull
    public static TerminowMcpBridge.Share managedShare(@NonNull String id, @NonNull String label,
                                                       @NonNull String environmentKey, @NonNull String user,
                                                       @NonNull String folder) {
        return new TerminowMcpBridge.Share(id, label, true, environmentKey, user, folder,
            new ArrayList<>(Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL)));
    }

    /** A managed share targeting a Vault SSH host, run by local Claude via the AI Bridge's SSH tools. */
    @NonNull
    public static TerminowMcpBridge.Share sshHostShare(@NonNull String id, @NonNull String label,
            @NonNull String environmentKey, @NonNull String user, @NonNull String folder,
            @NonNull String hostUuid, @NonNull String hostIdentity, @NonNull String executorMode) {
        return new TerminowMcpBridge.Share(id, label, true, environmentKey, user, folder,
            new ArrayList<>(Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL)),
            TerminowMcpBridge.SHARE_SSH_HOST, executorMode, hostUuid, hostIdentity);
    }
}
