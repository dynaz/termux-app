package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.files.TerminowSftpFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The authorization core of the Local AI Bridge: it decides whether a local AI (Claude Code in a
 * Linux environment of this device) may use a saved host, and carries out the small set of
 * allowed operations by reusing the app's own host, credential and SSH services. No secret ever
 * leaves it: credentials are resolved and used inside the app, and only non-secret facts and
 * results are returned.
 *
 * <p>Every request is authorized here, in the app, not in the Ubuntu helper or the UI, so a
 * compromised helper still can not reach a host the user did not allow. Grants are bound to a
 * host's stable id and to the endpoint and credentials that were allowed; changing the address,
 * port, username or credentials, disabling access, or removing the host invalidates them.</p>
 *
 * <p>This class holds no transport: it is driven by {@link #hostsList} and {@link #sshTest}, which
 * the socket or loopback transport calls once a connection is authenticated. It is pure enough to
 * test without a device, apart from the real SSH connection in {@link #sshTest}.</p>
 */
public final class TerminowAiBridge {

    /** Time, so grant expiry can be tested without waiting. */
    public interface Clock {
        long now();
    }

    /** What the bridge reads from the rest of the app; a test passes fakes. */
    public interface Deps {
        @NonNull List<TerminowStore.Host> hosts();

        @NonNull List<TerminowStore.Identity> identities();

        /** Whether the keychain (vault) is unlocked, so credentials can be resolved. */
        boolean keychainUnlocked();

        /** The passphrase of a key file the keychain keeps, {@code null} if none. */
        @Nullable String savedPassphrase(@NonNull String keyName);

        /** The home that holds {@code .ssh/known_hosts} and the key files, for SSH. */
        @NonNull File sshHome();
    }

    /**
     * A session allowance to use a host for one capability, from one authenticated client/session.
     * It is bound to (environment, client, host, capability) and to the credentials in force when it
     * was made; changing the endpoint or credentials, disabling access or removing the host drops it,
     * and a decision in one session never reaches another because the client id is part of the key.
     */
    static final class Grant {
        final String envKey;
        @Nullable final String clientId;
        final String hostUuid;
        final String capability;
        final String fingerprint;
        final long expiresAt;

        Grant(@NonNull String envKey, @Nullable String clientId, @NonNull String hostUuid, @NonNull String capability,
              @NonNull String fingerprint, long expiresAt) {
            this.envKey = envKey;
            this.clientId = clientId;
            this.hostUuid = hostUuid;
            this.capability = capability;
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }
    }

    /** How long a "for this session" grant lasts as a safety cap; a disconnect or revoke ends it sooner. */
    public static final long SESSION_GRANT_TTL_MS = 8 * 60 * 60 * 1000L;

    /**
     * The authenticated caller of a tool: the environment (proven by the session token) and, when the
     * client sent a {@code CLIENT} line, its per-process id and the labels a prompt shows. A session
     * grant can be bound only when {@link #sessionBindable()} holds.
     */
    public static final class Caller {
        public final String envKey;
        @Nullable public final String clientId;
        public final String clientName;
        public final String sessionLabel;

        public Caller(@NonNull String envKey, @Nullable String clientId, @NonNull String clientName,
                      @NonNull String sessionLabel) {
            this.envKey = envKey;
            this.clientId = clientId;
            this.clientName = clientName;
            this.sessionLabel = sessionLabel;
        }

        /** A session grant can be remembered only when the session can be identified: a non-empty client id. */
        public boolean sessionBindable() {
            return clientId != null && !clientId.isEmpty();
        }
    }

    /**
     * Where an approval decision comes from. The real gate parks the request in
     * {@link TerminowAiApprovals} and blocks until the user decides (or it times out / is cancelled);
     * the default denies at once, so a build with no approval UI fails closed rather than hanging.
     */
    public interface ApprovalGate {
        @NonNull TerminowAiApprovals.Decision await(@NonNull TerminowAiApprovals.Spec spec);
    }

    private final Deps mDeps;
    private final Clock mClock;
    /** Interactive, per-session grants: key (env, client, host, capability). */
    private final Map<String, Grant> mGrants = new HashMap<>();
    /** Env-wide grants for managed tasks (no interactive client): key (env, host, capability). */
    private final Map<String, Grant> mEnvGrants = new HashMap<>();
    @NonNull private volatile ApprovalGate mGate = spec -> TerminowAiApprovals.Decision.DENY;
    private long mApprovalTimeoutMs = 2 * 60 * 1000L;

    public TerminowAiBridge(@NonNull Deps deps, @NonNull Clock clock) {
        mDeps = deps;
        mClock = clock;
    }

    public TerminowAiBridge(@NonNull Deps deps) {
        this(deps, System::currentTimeMillis);
    }

    /** Install the approval gate (the app's, backed by {@link TerminowAiApprovals} and the UI). */
    public void setApprovalGate(@NonNull ApprovalGate gate) {
        mGate = gate;
    }

    /** How long a parked approval waits before it times out; the prompt shows this window. */
    public void setApprovalTimeoutMs(long timeoutMs) {
        if (timeoutMs > 0) mApprovalTimeoutMs = timeoutMs;
    }

    public long approvalTimeoutMs() {
        return mApprovalTimeoutMs;
    }

    @NonNull
    private static String grantKey(@NonNull String envKey, @Nullable String clientId, @NonNull String hostUuid,
                                   @NonNull String capability) {
        return envKey + "|" + (clientId == null ? "" : clientId) + "|" + hostUuid + "|" + capability;
    }

    // ---------------------------------------------------------------------------------------
    // Grants and revocation
    // ---------------------------------------------------------------------------------------

    /**
     * Remember that a client/session may use a host for one capability until {@code now + ttlMillis},
     * bound to the credentials now. Only bound when the session can be identified; a null/empty
     * client id is not stored, since it could not be isolated from another session.
     */
    public synchronized void grantSession(@NonNull String envKey, @Nullable String clientId,
                                          @NonNull TerminowStore.Host host, @NonNull String capability, long ttlMillis) {
        if (clientId == null || clientId.isEmpty()) return;
        mGrants.put(grantKey(envKey, clientId, host.uuid, capability),
            new Grant(envKey, clientId, host.uuid, capability, TerminowStore.aiFingerprint(host), mClock.now() + ttlMillis));
    }

    @NonNull
    private static String envGrantKey(@NonNull String envKey, @NonNull String hostUuid, @NonNull String capability) {
        return envKey + "|" + hostUuid + "|" + capability;
    }

    /**
     * Grant an environment (not an interactive session) a host for both capabilities until
     * {@code now + ttlMillis}, for a managed task that runs as that environment. Bound to the
     * credentials now; a disconnect, revoke or credential change drops it. This is the only
     * non-interactive path and does not need a client id.
     */
    public synchronized void grantEnv(@NonNull String envKey, @NonNull TerminowStore.Host host, long ttlMillis) {
        long expiry = mClock.now() + ttlMillis;
        String fingerprint = TerminowStore.aiFingerprint(host);
        for (String capability : new String[]{TerminowStore.AiAccess.CAP_TEST_CONNECTION, TerminowStore.AiAccess.CAP_RUN_COMMAND}) {
            mEnvGrants.put(envGrantKey(envKey, host.uuid, capability),
                new Grant(envKey, null, host.uuid, capability, fingerprint, expiry));
        }
    }

    public synchronized void revoke(@NonNull String envKey, @Nullable String clientId, @NonNull String hostUuid,
                                    @NonNull String capability) {
        mGrants.remove(grantKey(envKey, clientId, hostUuid, capability));
    }

    /** Drop every session grant of a client/session: on that client disconnecting. */
    public synchronized void revokeClient(@NonNull String envKey, @Nullable String clientId) {
        mGrants.values().removeIf(grant -> grant.envKey.equals(envKey)
            && java.util.Objects.equals(grant.clientId, clientId));
    }

    /** Drop every grant of an environment (session and env-wide): on its token being revoked. */
    public synchronized void revokeEnv(@NonNull String envKey) {
        mGrants.values().removeIf(grant -> grant.envKey.equals(envKey));
        mEnvGrants.values().removeIf(grant -> grant.envKey.equals(envKey));
    }

    /** Drop every grant for a host, for all sessions and environments: on disable, delete, or credential change. */
    public synchronized void revokeHost(@NonNull String hostUuid) {
        mGrants.values().removeIf(grant -> grant.hostUuid.equals(hostUuid));
        mEnvGrants.values().removeIf(grant -> grant.hostUuid.equals(hostUuid));
        mSelections.values().removeIf(selection -> selection.hostUuid.equals(hostUuid));
    }

    public synchronized void revokeAll() {
        mGrants.clear();
        mEnvGrants.clear();
    }

    public synchronized int activeGrantCount() {
        long now = mClock.now();
        mGrants.values().removeIf(grant -> grant.expiresAt <= now);
        mEnvGrants.values().removeIf(grant -> grant.expiresAt <= now);
        return mGrants.size() + mEnvGrants.size();
    }

    /**
     * The host was saved: if AI access is off, or the endpoint or credentials changed since a
     * grant was made, that grant is no longer honoured. Called by the host editor on save.
     */
    public synchronized void onHostSaved(@NonNull TerminowStore.Host host) {
        if (!host.aiAccess.enabled) {
            revokeHost(host.uuid);
            return;
        }
        String fingerprint = TerminowStore.aiFingerprint(host);
        mGrants.values().removeIf(grant -> grant.hostUuid.equals(host.uuid) && !grant.fingerprint.equals(fingerprint));
        mEnvGrants.values().removeIf(grant -> grant.hostUuid.equals(host.uuid) && !grant.fingerprint.equals(fingerprint));
    }

    public synchronized void onHostRemoved(@NonNull String hostUuid) {
        revokeHost(hostUuid);
    }

    private synchronized boolean hasValidGrant(@NonNull String envKey, @Nullable String clientId,
                                               @NonNull TerminowStore.Host host, @NonNull String capability) {
        String fingerprint = TerminowStore.aiFingerprint(host);
        long now = mClock.now();
        String key = grantKey(envKey, clientId, host.uuid, capability);
        Grant grant = mGrants.get(key);
        if (grant != null) {
            if (grant.expiresAt <= now) {
                mGrants.remove(key);
            } else if (grant.fingerprint.equals(fingerprint)) {
                return true; // a live per-session grant for other credentials does not carry over
            }
        }
        // Fall back to an env-wide grant (a managed task), same expiry and credential binding.
        String envKeyStr = envGrantKey(envKey, host.uuid, capability);
        Grant envGrant = mEnvGrants.get(envKeyStr);
        if (envGrant == null) return false;
        if (envGrant.expiresAt <= now) {
            mEnvGrants.remove(envKeyStr);
            return false;
        }
        return envGrant.fingerprint.equals(fingerprint);
    }

    // ---------------------------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------------------------

    /**
     * The static, no-prompt checks: the host exists and is available to this environment, the
     * capability is allowed, the endpoint/credentials still match what was allowed, and the keychain
     * is unlocked. Returns the host (not yet resolved). Throws the exact reason otherwise. These are
     * re-run after an approval, so nothing that changed while the user decided slips through.
     */
    @NonNull
    private TerminowStore.Host staticChecks(@NonNull String envKey, @NonNull String hostUuid, @NonNull String capability)
            throws TerminowAiError.Exception {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mDeps.hosts(), hostUuid);
        if (host == null || !host.aiAccess.allowsEnv(envKey))
            throw TerminowAiError.HOST_NOT_AUTHORIZED.of("The host is not available to this environment");
        if (!host.aiAccess.allows(capability))
            throw TerminowAiError.CAPABILITY_DENIED.of("This host does not allow " + capability);
        if (!host.aiAccess.fingerprint.isEmpty() && !host.aiAccess.fingerprint.equals(TerminowStore.aiFingerprint(host)))
            throw TerminowAiError.APPROVAL_REQUIRED.of("The host's address or credentials changed since access was allowed");
        if (!mDeps.keychainUnlocked())
            throw TerminowAiError.KEYCHAIN_LOCKED.of("Unlock TermiNow to use saved credentials");
        return host;
    }

    /**
     * Whether this caller may use the host for this capability now, and the resolved host if so. For
     * the "rules" policy the saved capabilities are enough. For "ask"/"session" a live session grant
     * is honoured; otherwise the user is prompted (the call blocks on {@link #mGate}) and, on
     * approval, the permission is re-validated before the host is returned. {@code command} is the
     * exact command for a run_command prompt, so the user approves what will actually run; it is null
     * for a connection test. Throws the exact reason when not allowed; the reasons never mix up
     * policy, a locked keychain, a denial and a cancellation.
     */
    @NonNull
    TerminowStore.Host authorize(@NonNull Caller caller, @NonNull String hostUuid, @NonNull String capability,
                                 @Nullable String command) throws TerminowAiError.Exception {
        TerminowStore.Host host = staticChecks(caller.envKey, hostUuid, capability);
        if ("rules".equals(host.aiAccess.approvalPolicy)) return TerminowStore.resolve(host, mDeps.identities());
        if (hasValidGrant(caller.envKey, caller.clientId, host, capability))
            return TerminowStore.resolve(host, mDeps.identities());

        // Not pre-authorized: park the request and wait for the user. This blocks the caller's
        // connection thread only, not other sessions, and resolves on a decision, timeout or cancel.
        TerminowAiApprovals.Spec spec = new TerminowAiApprovals.Spec(
            caller.envKey, caller.clientId, caller.clientName, caller.sessionLabel,
            host.uuid, host.getTitle(), host.address, host.port, capability,
            command, TerminowStore.aiFingerprint(host), caller.sessionBindable(), mApprovalTimeoutMs);
        TerminowAiApprovals.Decision decision = mGate.await(spec);
        switch (decision) {
            case ALLOW_SESSION:
                grantSession(caller.envKey, caller.clientId, host, capability, SESSION_GRANT_TTL_MS);
                break;
            case ALLOW_ONCE:
                break; // authorize only this parked call; remember nothing
            case DENY:
                throw TerminowAiError.APPROVAL_REQUIRED.of("Access to this host was denied");
            case TIMEOUT:
                throw TerminowAiError.APPROVAL_REQUIRED.of("No approval before the request timed out; ask again");
            case CANCELLED:
            default:
                throw TerminowAiError.CANCELLED.of("The approval was cancelled before access was granted");
        }
        // Re-validate against the live state: access or credentials may have changed while we waited.
        return TerminowStore.resolve(staticChecks(caller.envKey, hostUuid, capability), mDeps.identities());
    }

    // ---------------------------------------------------------------------------------------
    // Per-client host selection
    //
    // A registered client (one Claude Code process, named by a per-process client id) may choose a
    // "current" host so ssh_exec/ssh_test can omit host_id. A selection is scoped to (environment,
    // client) and is NEVER shared with another client: one tab's choice can never retarget another's,
    // and there is no silent default. Selecting a host does NOT authorize it; every call still goes
    // through authorize(), which the env token gates. A selection is dropped after SELECTION_TTL_MS
    // idle and refreshed each time it is used, so an exited client leaves nothing stale behind.
    // ---------------------------------------------------------------------------------------

    /** How long an unused selection is kept before it is forgotten. */
    public static final long SELECTION_TTL_MS = 30 * 60 * 1000L;

    private static final class Selection {
        final String hostUuid;
        long expiresAt;

        Selection(@NonNull String hostUuid, long expiresAt) {
            this.hostUuid = hostUuid;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Selection> mSelections = new HashMap<>();

    @NonNull
    private static String selectionKey(@NonNull String envKey, @NonNull String clientId) {
        return envKey + "\u0000" + clientId;
    }

    /**
     * Choose the current host for this client. Validates the host is available to this environment
     * and AI-enabled; it does not grant it (ssh_exec still authorizes). Returns a non-secret
     * descriptor. Requires a registered client, so selection can never be attributed to "the
     * environment" at large.
     */
    @NonNull
    public synchronized JSONObject selectHost(@NonNull String envKey, @Nullable String clientId, @NonNull String hostUuid)
            throws TerminowAiError.Exception {
        if (clientId == null || clientId.isEmpty())
            throw TerminowAiError.BAD_REQUEST.of("Register a client before selecting a host");
        TerminowStore.Host host = TerminowStore.findHostByUuid(mDeps.hosts(), hostUuid);
        if (host == null || !host.aiAccess.allowsEnv(envKey))
            throw TerminowAiError.HOST_NOT_AUTHORIZED.of("The host is not available to this environment");
        mSelections.put(selectionKey(envKey, clientId), new Selection(hostUuid, mClock.now() + SELECTION_TTL_MS));
        return hostDescriptor(host);
    }

    /**
     * A host that some client of {@code envKey} currently has selected, for status display, or
     * {@code null} if none. With one client per environment (the usual case) this is that client's
     * target; it never reveals a credential, only the stable host id.
     */
    @Nullable
    public synchronized String selectedHostIdFor(@NonNull String envKey) {
        long now = mClock.now();
        String prefix = envKey + "\u0000";
        for (Map.Entry<String, Selection> entry : mSelections.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().expiresAt > now) return entry.getValue().hostUuid;
        }
        return null;
    }

    /** Forget this client's selection, if any. */
    public synchronized void clearSelection(@NonNull String envKey, @Nullable String clientId) {
        if (clientId == null || clientId.isEmpty()) return;
        mSelections.remove(selectionKey(envKey, clientId));
    }

    /**
     * The host id a call targets: the explicit one if given, otherwise this client's selection
     * (refreshed), otherwise an error. It never falls back to another client's selection and never
     * picks a default, so an ambiguous request fails loudly instead of acting on the wrong host.
     */
    @NonNull
    synchronized String resolveHostId(@NonNull String envKey, @Nullable String clientId, @NonNull String explicitHostId)
            throws TerminowAiError.Exception {
        if (!explicitHostId.isEmpty()) return explicitHostId;
        if (clientId != null && !clientId.isEmpty()) {
            Selection selection = mSelections.get(selectionKey(envKey, clientId));
            long now = mClock.now();
            if (selection != null && selection.expiresAt > now) {
                selection.expiresAt = now + SELECTION_TTL_MS;
                return selection.hostUuid;
            }
            if (selection != null) mSelections.remove(selectionKey(envKey, clientId));
        }
        throw TerminowAiError.BAD_REQUEST.of("No host selected; pass host_id or call select_host first");
    }

    /** This client's bridge status: whether it is registered and which host it has selected. No secrets. */
    @NonNull
    public synchronized JSONObject bridgeStatus(@NonNull String envKey, @Nullable String clientId) {
        JSONObject json = new JSONObject();
        try {
            json.put("environment", envKey);
            json.put("client_registered", clientId != null && !clientId.isEmpty());
            String selectedId = null;
            if (clientId != null && !clientId.isEmpty()) {
                Selection selection = mSelections.get(selectionKey(envKey, clientId));
                if (selection != null && selection.expiresAt > mClock.now()) selectedId = selection.hostUuid;
            }
            TerminowStore.Host host = selectedId == null ? null : TerminowStore.findHostByUuid(mDeps.hosts(), selectedId);
            json.put("selected_host", host != null && host.aiAccess.allowsEnv(envKey) ? hostDescriptor(host) : JSONObject.NULL);
        } catch (org.json.JSONException ignored) {
            // A partial object is still usable
        }
        return json;
    }

    /** The non-secret facts about a host, as select_host and bridge_status return them. */
    @NonNull
    private static JSONObject hostDescriptor(@NonNull TerminowStore.Host host) {
        JSONObject json = new JSONObject();
        try {
            json.put("host_id", host.uuid);
            json.put("alias", host.getTitle());
            json.put("protocol", host.telnet ? "telnet" : host.mosh ? "mosh" : host.protocol);
            json.put("hostname", host.address);
            json.put("port", host.port);
            json.put("username", host.username);
            json.put("capabilities", new JSONArray(host.aiAccess.capabilities));
        } catch (org.json.JSONException ignored) {
            // best effort; a partial descriptor is still non-secret
        }
        return json;
    }

    // ---------------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------------

    /**
     * The hosts this environment may use, with only non-secret facts: the stable id, the alias,
     * the protocol, the address, port and username, whether a credential is stored (never which),
     * and the capabilities allowed. A locked keychain still lists them, since no secret is read.
     */
    @NonNull
    public JSONArray hostsList(@NonNull String envKey) {
        JSONArray array = new JSONArray();
        for (TerminowStore.Host host : mDeps.hosts()) {
            if (!host.aiAccess.allowsEnv(envKey)) continue;
            JSONObject json = new JSONObject();
            try {
                json.put("host_id", host.uuid);
                json.put("alias", host.getTitle());
                json.put("protocol", host.telnet ? "telnet" : host.mosh ? "mosh" : host.protocol);
                json.put("hostname", host.address);
                json.put("port", host.port);
                json.put("username", host.username);
                json.put("credential_available", credentialAvailable(host));
                json.put("capabilities", new JSONArray(host.aiAccess.capabilities));
            } catch (org.json.JSONException ignored) {
                continue;
            }
            array.put(json);
        }
        return array;
    }

    /** Whether the host has a stored credential, said as a plain yes/no, never the credential. */
    static boolean credentialAvailable(@NonNull TerminowStore.Host host) {
        return !host.password.isEmpty() || !host.keyPath.isEmpty() || !host.identity.isEmpty();
    }

    /**
     * Test the SSH connection to a host: reachable, the host key known, and authentication. It
     * uses the app's own SSH with the saved credentials and the same known-hosts file as the
     * terminal, and never accepts an unknown or changed host key on its own. No secret is
     * returned; the result only says what happened.
     */
    @NonNull
    public JSONObject sshTest(@NonNull Caller caller, @NonNull String hostUuid) throws TerminowAiError.Exception {
        TerminowStore.Host host = authorize(caller, hostUuid, TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
        if (host.telnet || !host.isTerminal())
            throw TerminowAiError.BAD_REQUEST.of("This host is not an SSH connection");
        TestPrompts prompts = new TestPrompts(hostUuid);
        TerminowSftpFs fs = new TerminowSftpFs(mDeps.sshHome(), "aitest:" + hostUuid, host.getTitle(), host.address,
            host.port, host.username, host.password, host.keyPath, prompts);
        long started = mClock.now();
        try {
            fs.connect();
        } catch (TerminowSftpFs.HostKeyChangedException e) {
            throw TerminowAiError.HOST_KEY_CHANGED.of("The host key changed since it was saved; verify it in TermiNow");
        } catch (IOException | RuntimeException e) {
            if (prompts.unknownHostKey)
                throw TerminowAiError.HOST_KEY_UNKNOWN.of("The host key is not known yet; connect once in TermiNow to verify it");
            throw mapConnectError(e);
        } finally {
            TerminowSftpFs closing = fs;
            new Thread(closing::close, "TermiNow ai ssh-test close").start();
        }
        JSONObject result = new JSONObject();
        try {
            result.put("host_id", hostUuid);
            result.put("reachable", true);
            result.put("authenticated", true);
            result.put("host_key", "known");
            result.put("duration_ms", mClock.now() - started);
        } catch (org.json.JSONException ignored) {
            // A plain object is still returned
        }
        return result;
    }

    /** Output read from a command is capped; the default and most anyone may ask for. */
    public static final int EXEC_OUTPUT_LIMIT = 64 * 1024;
    public static final int EXEC_TIMEOUT_DEFAULT_S = 30;
    public static final int EXEC_TIMEOUT_MAX_S = 300;

    /**
     * Run a command on a host over SSH and return its exit code and output. The command has all
     * the access of the SSH account; the SFTP directory limits do not sandbox it, which the UI
     * makes clear before this is allowed. Output is bounded and the command is stopped at the
     * timeout. No secret is returned.
     */
    @NonNull
    public JSONObject sshExec(@NonNull Caller caller, @NonNull String hostUuid, @NonNull String command, int timeoutSeconds)
            throws TerminowAiError.Exception {
        if (command.trim().isEmpty()) throw TerminowAiError.BAD_REQUEST.of("The command is empty");
        TerminowStore.Host host = authorize(caller, hostUuid, TerminowStore.AiAccess.CAP_RUN_COMMAND, command);
        if (host.telnet || !host.isTerminal()) throw TerminowAiError.BAD_REQUEST.of("This host is not an SSH connection");
        int timeout = timeoutSeconds <= 0 ? EXEC_TIMEOUT_DEFAULT_S : Math.min(timeoutSeconds, EXEC_TIMEOUT_MAX_S);
        TestPrompts prompts = new TestPrompts(hostUuid);
        TerminowSftpFs fs = new TerminowSftpFs(mDeps.sshHome(), "aiexec:" + hostUuid, host.getTitle(), host.address,
            host.port, host.username, host.password, host.keyPath, prompts);
        long started = mClock.now();
        Process process;
        try {
            // A login shell, so PATH and the environment are the user's, and the command as one argument
            process = fs.exec("/bin/sh -lc " + TerminowStore.shellQuote(command));
        } catch (TerminowSftpFs.HostKeyChangedException e) {
            closeQuietly(fs);
            throw TerminowAiError.HOST_KEY_CHANGED.of("The host key changed since it was saved; verify it in TermiNow");
        } catch (IOException | RuntimeException e) {
            closeQuietly(fs);
            if (prompts.unknownHostKey)
                throw TerminowAiError.HOST_KEY_UNKNOWN.of("The host key is not known yet; connect once in TermiNow to verify it");
            throw mapConnectError(e);
        }
        try {
            BoundedReader out = new BoundedReader(process.getInputStream());
            BoundedReader err = new BoundedReader(process.getErrorStream());
            out.start();
            err.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw TerminowAiError.CANCELLED.of("The command was interrupted");
            }
            if (!finished) {
                process.destroy();
                out.joinQuietly();
                err.joinQuietly();
                throw TerminowAiError.TIMEOUT.of("The command ran longer than " + timeout + "s and was stopped");
            }
            out.joinQuietly();
            err.joinQuietly();
            int exit = process.exitValue();
            JSONObject result = new JSONObject();
            result.put("host_id", hostUuid);
            result.put("exit_code", exit);
            result.put("stdout", out.text());
            result.put("stderr", err.text());
            result.put("truncated", out.truncated() || err.truncated());
            result.put("duration_ms", mClock.now() - started);
            return result;
        } catch (org.json.JSONException e) {
            throw TerminowAiError.INTERNAL.of("Could not build the result");
        } finally {
            closeQuietly(fs);
        }
    }

    private static void closeQuietly(@NonNull TerminowSftpFs fs) {
        new Thread(fs::close, "TermiNow ai ssh close").start();
    }

    /** Reads a stream into memory up to {@link #EXEC_OUTPUT_LIMIT}, dropping the rest but noting it. */
    private static final class BoundedReader extends Thread {
        private final java.io.InputStream mStream;
        private final java.io.ByteArrayOutputStream mBuffer = new java.io.ByteArrayOutputStream();
        private volatile boolean mTruncated;

        BoundedReader(@NonNull java.io.InputStream stream) {
            mStream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = mStream.read(chunk)) != -1) {
                    int room = EXEC_OUTPUT_LIMIT - mBuffer.size();
                    if (room <= 0) {
                        mTruncated = true;
                        continue; // drain so the command is not blocked on a full pipe
                    }
                    mBuffer.write(chunk, 0, Math.min(read, room));
                    if (read > room) mTruncated = true;
                }
            } catch (IOException ignored) {
                // The stream closed with the channel
            }
        }

        void joinQuietly() {
            try {
                join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @NonNull
        String text() {
            return new String(mBuffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return mTruncated;
        }
    }

    @NonNull
    private static TerminowAiError.Exception mapConnectError(@NonNull Throwable e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("auth fail") || message.contains("authentication") || message.contains("not accepted") ||
            message.contains("permission denied") || message.contains("no more authentication"))
            return TerminowAiError.AUTH_FAILED.of("The server did not accept the saved credentials");
        if (message.contains("timed out") || message.contains("timeout"))
            return TerminowAiError.TIMEOUT.of("The server did not answer in time");
        if (message.contains("unknownhost") || message.contains("not known") || message.contains("unreachable") ||
            message.contains("refused") || message.contains("no route") || message.contains("resolve") ||
            message.contains("connect"))
            return TerminowAiError.NETWORK_UNREACHABLE.of("The server could not be reached");
        return TerminowAiError.NETWORK_UNREACHABLE.of("The server could not be reached");
    }

    /** Non-interactive questions for a test: an unknown or changed host key is never accepted. */
    private final class TestPrompts implements TerminowSftpFs.Prompts {
        final String hostUuid;
        volatile boolean unknownHostKey;

        TestPrompts(@NonNull String hostUuid) {
            this.hostUuid = hostUuid;
        }

        @Override
        public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            unknownHostKey = true;
            return false;
        }

        @Nullable
        @Override
        public String askPassword(@NonNull String prompt) {
            return null;
        }

        @Nullable
        @Override
        public String askPassphrase(@NonNull String keyName) {
            return null;
        }

        @Nullable
        @Override
        public String savedPassphrase(@NonNull String keyName) {
            return mDeps.savedPassphrase(keyName);
        }
    }

    /** The grants right now, for tests. */
    List<Grant> grantsForTest() {
        return new ArrayList<>(mGrants.values());
    }
}
