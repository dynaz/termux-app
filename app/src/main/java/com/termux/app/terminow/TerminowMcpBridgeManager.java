package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
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
    private final TerminowAiBridgeServer mServer;
    private final TerminowMcpBridgeRelay mRelay;

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

        TerminowMcpBridgeExecutor executor = new TerminowMcpBridgeExecutor(
            new TerminowClaudeSetup(mContext, mTargets), mTargets);

        mBridge = new TerminowMcpBridge(new TerminowMcpBridge.Deps() {
            @NonNull @Override public List<TerminowMcpBridge.Share> shares() { return loadShares(); }
            @Override public boolean clientPaired(@NonNull String clientKey) { return mClients.hasToken(clientKey); }
            @Override public int maxQueue() { return mStore.getMcpBridgeMaxQueue(); }
        }, executor, new TerminowMcpBridge.Persistence() {
            @Override public void save(@NonNull List<TerminowMcpBridgeTask> tasks) { saveTasks(tasks); }
            @NonNull @Override public List<TerminowMcpBridgeTask> load() { return loadTasks(); }
        });

        mServer = new TerminowAiBridgeServer(mClients,
            clientKey -> new TerminowMcpBridgeMcpServer(mBridge, clientKey, this::secrets, BuildConfig.VERSION_NAME));

        // The relay dials out to a remote MCP endpoint; its traffic is answered as the relay's own
        // paired client, so the same per-session authorization applies to remote AI clients.
        mRelay = new TerminowMcpBridgeRelay(
            () -> new TerminowMcpBridgeMcpServer(mBridge, relayClientKey(), this::secrets, BuildConfig.VERSION_NAME));
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
        return new PairResult(clientId, token, safeLabel);
    }

    /** Revoke a client: its token stops working and its open connections are closed. Never gated. */
    public synchronized void revokeClient(@NonNull String clientId) {
        mClients.revoke(clientId);
        mServer.closeConnectionsOf(clientId);
    }

    public synchronized void revokeAllClients() {
        for (String id : new HashSet<>(mStore.getMcpClientTokens().keySet())) mServer.closeConnectionsOf(id);
        mClients.revokeAll();
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

    /** Add or replace a shared session. If its target changed, bound tasks are interrupted. */
    public synchronized void putShare(@NonNull TerminowMcpBridge.Share share) {
        if (!premium()) throw new IllegalStateException("The MCP Bridge needs TermiNow Premium");
        List<TerminowMcpBridge.Share> shares = loadShares();
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
        if (!clientId.isEmpty()) {
            mClients.revoke(clientId);
            mServer.closeConnectionsOf(clientId);
        }
        mStore.setMcpRelay(new JSONObject());
    }

    private void startRelayIfConfigured() {
        JSONObject relay = mStore.getMcpRelay();
        String url = relay.optString("url");
        String clientId = relay.optString("clientId");
        String token = clientId.isEmpty() ? "" : mStore.getMcpClientTokens().getOrDefault(clientId, "");
        if (!url.isEmpty() && !token.isEmpty()) mRelay.start(url, token);
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

    /** From the UI: cancel a task regardless of the client. Never gated. */
    public void cancelTask(@NonNull String taskId) {
        mBridge.cancelFromDevice(taskId);
    }

    /** Emergency stop: turn the bridge off, drop the relay, and revoke every client at once. */
    public synchronized void emergencyStop() {
        mStore.setMcpBridgeEnabled(false);
        mServer.stop();
        mRelay.stop();
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
                shares.add(new TerminowMcpBridge.Share(
                    json.optString("id"), json.optString("label"), json.optBoolean("managed", true),
                    json.optString("environment"), json.optString("user"), json.optString("folder"), caps));
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
                    .put("capabilities", new JSONArray(share.capabilities)));
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
}
