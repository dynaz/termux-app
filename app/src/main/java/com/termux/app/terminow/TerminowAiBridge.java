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
     * Whether {@code envKey} may use the host {@code hostUuid} for {@code capability} now, and the
     * resolved host if so. Throws with the exact reason otherwise; the reasons never mix up
     * policy, a locked keychain and an unknown host.
     */
    @NonNull
    TerminowStore.Host authorize(@NonNull String envKey, @NonNull String hostUuid, @NonNull String capability)
            throws TerminowAiError.Exception {
        TerminowStore.Host host = TerminowStore.findHostByUuid(mDeps.hosts(), hostUuid);
        if (host == null || !host.aiAccess.allowsEnv(envKey))
            throw TerminowAiError.HOST_NOT_AUTHORIZED.of("The host is not available to this environment");
        if (!host.aiAccess.allows(capability))
            throw TerminowAiError.CAPABILITY_DENIED.of("This host does not allow " + capability);
        // A grant or saved rules must match the endpoint and credentials allowed; otherwise agree again
        if (!host.aiAccess.fingerprint.isEmpty() && !host.aiAccess.fingerprint.equals(TerminowStore.aiFingerprint(host)))
            throw TerminowAiError.APPROVAL_REQUIRED.of("The host's address or credentials changed since access was allowed");
        if (!mDeps.keychainUnlocked())
            throw TerminowAiError.KEYCHAIN_LOCKED.of("Unlock TermiNow to use saved credentials");
        // "rules": the saved capabilities are enough. "ask"/"session": a current grant is needed.
        if (!"rules".equals(host.aiAccess.approvalPolicy) && !hasValidGrant(envKey, host))
            throw TerminowAiError.APPROVAL_REQUIRED.of("Approve this host for the AI first");
        return TerminowStore.resolve(host, mDeps.identities());
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
