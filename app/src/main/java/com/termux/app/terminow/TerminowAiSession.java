package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The per-environment secret that lets one Linux environment's MCP helper talk to the Local AI
 * Bridge, and nothing else. It is minted when the user connects Claude Code for an environment,
 * stored (encrypted) so a later reconnection works, and matched when a helper connects. It is
 * never placed in a command line, a project file or a log: the helper reads it from its own
 * environment. Revoking it, or disabling the bridge, stops that helper at once.
 *
 * <p>The session token only proves which environment is calling. It does not grant any host:
 * every host is still authorized per call by {@link TerminowAiBridge}, and host grants are kept
 * only in memory, so they do not survive the app being killed.</p>
 */
public final class TerminowAiSession {

    /** Where tokens are kept between runs; the app backs this with encrypted storage. */
    public interface Store {
        void save(@NonNull String envKey, @NonNull String token);

        @NonNull String load(@NonNull String envKey);

        void remove(@NonNull String envKey);

        /** Every environment that has a token, to its token. */
        @NonNull Map<String, String> all();
    }

    private final Store mStore;

    public TerminowAiSession(@NonNull Store store) {
        mStore = store;
    }

    /** A new token for the environment, replacing any it had. 32 random bytes as hex. */
    @NonNull
    public synchronized String mint(@NonNull String envKey) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        String value = token.toString();
        mStore.save(envKey, value);
        return value;
    }

    @NonNull
    public synchronized String tokenOf(@NonNull String envKey) {
        return mStore.load(envKey);
    }

    public synchronized boolean hasToken(@NonNull String envKey) {
        return !mStore.load(envKey).isEmpty();
    }

    /** The environment a token belongs to, compared without leaking timing, {@code null} if none. */
    @Nullable
    public synchronized String environmentOf(@NonNull String token) {
        if (token.isEmpty()) return null;
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : mStore.all().entrySet()) {
            byte[] known = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(known, given)) return entry.getKey();
        }
        return null;
    }

    public synchronized void revoke(@NonNull String envKey) {
        mStore.remove(envKey);
    }

    public synchronized void revokeAll() {
        for (String envKey : new LinkedHashMap<>(mStore.all()).keySet()) mStore.remove(envKey);
    }

    public synchronized int count() {
        return mStore.all().size();
    }
}
