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

    /** An allowance to use a host, for a while, from one environment: the policy "ask"/"session" needs it. */
    static final class Grant {
        final String envKey;
        final String hostUuid;
        final String fingerprint;
        final long expiresAt;

        Grant(@NonNull String envKey, @NonNull String hostUuid, @NonNull String fingerprint, long expiresAt) {
            this.envKey = envKey;
            this.hostUuid = hostUuid;
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }
    }

    private final Deps mDeps;
    private final Clock mClock;
    private final Map<String, Grant> mGrants = new HashMap<>();

    public TerminowAiBridge(@NonNull Deps deps, @NonNull Clock clock) {
        mDeps = deps;
        mClock = clock;
    }

    public TerminowAiBridge(@NonNull Deps deps) {
        this(deps, System::currentTimeMillis);
    }

    @NonNull
    private static String grantKey(@NonNull String envKey, @NonNull String hostUuid) {
        return envKey + "|" + hostUuid;
    }

    // ---------------------------------------------------------------------------------------
    // Grants and revocation
    // ---------------------------------------------------------------------------------------

    /** Allow an environment to use a host until {@code now + ttlMillis}, bound to its credentials now. */
    public synchronized void grant(@NonNull String envKey, @NonNull TerminowStore.Host host, long ttlMillis) {
        mGrants.put(grantKey(envKey, host.uuid), new Grant(envKey, host.uuid, TerminowStore.aiFingerprint(host), mClock.now() + ttlMillis));
    }

    public synchronized void revoke(@NonNull String envKey, @NonNull String hostUuid) {
        mGrants.remove(grantKey(envKey, hostUuid));
    }

    /** Drop every grant for a host, for all environments: on disable, delete, or credential change. */
    public synchronized void revokeHost(@NonNull String hostUuid) {
        mGrants.values().removeIf(grant -> grant.hostUuid.equals(hostUuid));
        mSelections.values().removeIf(selection -> selection.hostUuid.equals(hostUuid));
    }

    public synchronized void revokeAll() {
        mGrants.clear();
    }

    public synchronized int activeGrantCount() {
        long now = mClock.now();
        mGrants.values().removeIf(grant -> grant.expiresAt <= now);
        return mGrants.size();
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
    }

    public synchronized void onHostRemoved(@NonNull String hostUuid) {
        revokeHost(hostUuid);
    }

    private synchronized boolean hasValidGrant(@NonNull String envKey, @NonNull TerminowStore.Host host) {
        Grant grant = mGrants.get(grantKey(envKey, host.uuid));
        if (grant == null) return false;
        if (grant.expiresAt <= mClock.now()) {
            mGrants.remove(grantKey(envKey, host.uuid));
            return false;
        }
        // A grant made for other credentials does not carry over
        return grant.fingerprint.equals(TerminowStore.aiFingerprint(host));
    }

    // ---------------------------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------------------------

    /**
     * The single authoritative decision for "may {@code envKey} use this host for {@code capability}
     * right now?", with the exact reason. Both the execution path ({@link #authorize}) and the UI
     * (the AI Bridge sheet's per-host label and test action) read THIS, so what the UI shows can
     * never disagree with what execution enforces — the mismatch that made a host look "Allowed"
     * yet return APPROVAL_REQUIRED on a test. Pure and non-throwing, so it is unit-testable and safe
     * to call while drawing a list.
     */
    public enum AuthDecision {
        /** Fully authorized: the call would run now. */
        ALLOWED,
        /** The host is not AI-enabled or not shared with this environment. */
        HOST_NOT_AUTHORIZED,
        /** Host access is allowed, but this capability is not on the host's AI rules. */
        CAPABILITY_DENIED,
        /** The endpoint or credentials changed since access was allowed; the user must approve again. */
        FINGERPRINT_CHANGED,
        /** The keychain is locked, so saved credentials cannot be resolved. */
        KEYCHAIN_LOCKED,
        /** Host access + capability are allowed, but the policy ("ask"/"session") needs a current grant. */
        APPROVAL_NEEDED;

        /** Whether host access itself is in place (so only a per-request approval, not access, is missing). */
        public boolean hostAccessAllowed() {
            return this == ALLOWED || this == APPROVAL_NEEDED || this == FINGERPRINT_CHANGED;
        }
    }

    /** Evaluate authorization without side effects or throwing; the one source of truth for UI and execution. */
    @NonNull
    public synchronized AuthDecision evaluate(@Nullable TerminowStore.Host host, @NonNull String envKey,
                                              @NonNull String capability) {
        if (host == null || !host.aiAccess.allowsEnv(envKey)) return AuthDecision.HOST_NOT_AUTHORIZED;
        if (!host.aiAccess.allows(capability)) return AuthDecision.CAPABILITY_DENIED;
        // A grant or saved rules must match the endpoint and credentials allowed; otherwise agree again.
        if (!host.aiAccess.fingerprint.isEmpty() && !host.aiAccess.fingerprint.equals(TerminowStore.aiFingerprint(host)))
            return AuthDecision.FINGERPRINT_CHANGED;
        if (!mDeps.keychainUnlocked()) return AuthDecision.KEYCHAIN_LOCKED;
        // "rules": the saved capabilities are enough. "ask"/"session": a current grant is needed.
        if (!"rules".equals(host.aiAccess.approvalPolicy) && !hasValidGrant(envKey, host))
            return AuthDecision.APPROVAL_NEEDED;
        return AuthDecision.ALLOWED;
    }

    /**
     * Whether {@code envKey} may use the host {@code hostUuid} for {@code capability} now, and the
     * resolved host if so. Throws with the exact reason otherwise; the reasons never mix up
     * policy, a locked keychain and an unknown host. Delegates the decision to {@link #evaluate} so
     * it stays identical to what the UI shows.
     */
    @NonNull
    TerminowStore.Host authorize(@NonNull String envKey, @NonNull String hostUuid, @NonNull String capability)
            throws TerminowAiError.Exception {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mDeps.hosts(), hostUuid);
        switch (evaluate(host, envKey, capability)) {
            case HOST_NOT_AUTHORIZED:
                throw TerminowAiError.HOST_NOT_AUTHORIZED.of("The host is not available to this environment");
            case CAPABILITY_DENIED:
                throw TerminowAiError.CAPABILITY_DENIED.of("This host does not allow " + capability);
            case FINGERPRINT_CHANGED:
                throw TerminowAiError.APPROVAL_REQUIRED.of("The host's address or credentials changed since access was allowed");
            case KEYCHAIN_LOCKED:
                throw TerminowAiError.KEYCHAIN_LOCKED.of("Unlock TermiNow to use saved credentials");
            case APPROVAL_NEEDED:
                throw TerminowAiError.APPROVAL_REQUIRED.of("Approve this host for the AI first");
            case ALLOWED:
            default:
                return TerminowStore.resolve(host, mDeps.identities());
        }
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
    public JSONObject sshTest(@NonNull String envKey, @NonNull String hostUuid) throws TerminowAiError.Exception {
        TerminowStore.Host host = authorize(envKey, hostUuid, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
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
    public JSONObject sshExec(@NonNull String envKey, @NonNull String hostUuid, @NonNull String command, int timeoutSeconds)
            throws TerminowAiError.Exception {
        if (command.trim().isEmpty()) throw TerminowAiError.BAD_REQUEST.of("The command is empty");
        TerminowStore.Host host = authorize(envKey, hostUuid, TerminowStore.AiAccess.CAP_RUN_COMMAND);
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
