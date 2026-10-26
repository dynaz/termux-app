package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;

/**
 * The pure decisions of the import pipeline, kept apart from parsing and from persistence so they
 * can be tested on their own: which category an entry falls in, whether it is a duplicate of a host
 * already saved, and whether it should be written given the chosen duplicate policy.
 *
 * <p>Duplicates are compared the way the app already compares hosts — normalised address, port and
 * username — so an import lines up with the existing identity rules. The default policy is Skip, and
 * credentials of an existing host are never overwritten unless the person explicitly chooses to.
 */
public final class TerminowImportPlan {

    /** The single bucket an entry is shown under in the preview (precedence high to low). */
    public enum Category {UNSUPPORTED, INVALID, DUPLICATE, CREDENTIALS_REQUIRED, VALID}

    /** What to do with an entry that matches a host already saved. */
    public enum DuplicatePolicy {SKIP, KEEP_BOTH, UPDATE}

    private TerminowImportPlan() {}

    /** The category of an entry, given whether it duplicates an existing host. */
    @NonNull
    public static Category categorize(@NonNull TerminowImportConnection c, boolean duplicate) {
        if (!c.protocol.supported) return Category.UNSUPPORTED;
        if (!validHostPort(c)) return Category.INVALID;
        if (duplicate) return Category.DUPLICATE;
        if (c.credential == TerminowImportConnection.Credential.REQUIRED) return Category.CREDENTIALS_REQUIRED;
        return Category.VALID;
    }

    /** Whether an entry of this category may be written under the chosen policy. */
    public static boolean importable(@NonNull Category category, @NonNull DuplicatePolicy policy) {
        switch (category) {
            case UNSUPPORTED:
            case INVALID:
                return false;
            case DUPLICATE:
                return policy != DuplicatePolicy.SKIP;
            default:
                // VALID and CREDENTIALS_REQUIRED are imported; the latter brings connection details only.
                return true;
        }
    }

    /** A host is reachable-shaped: it has an address and a port in range. */
    public static boolean validHostPort(@NonNull TerminowImportConnection c) {
        if (c.host.trim().isEmpty()) return false;
        int port = c.effectivePort();
        return port >= 1 && port <= 65535;
    }

    /** Whether {@code c} is the same connection as the saved host {@code h}, by the app's identity rule. */
    public static boolean sameIdentity(@NonNull TerminowImportConnection c, @NonNull TerminowStore.Host h) {
        return normalize(c.host).equals(normalize(h.address))
            && c.effectivePort() == effectivePort(h)
            && normalize(c.username).equals(normalize(h.username));
    }

    /** Whether {@code c} duplicates any host in {@code existing} (or any already accepted this run). */
    public static boolean isDuplicate(@NonNull TerminowImportConnection c, @NonNull List<TerminowStore.Host> existing) {
        for (TerminowStore.Host h : existing) if (sameIdentity(c, h)) return true;
        return false;
    }

    private static int effectivePort(@NonNull TerminowStore.Host h) {
        return h.port > 0 ? h.port : 22;
    }

    @NonNull
    private static String normalize(@NonNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
