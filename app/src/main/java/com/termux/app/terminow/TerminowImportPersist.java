package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Turns an accepted {@link TerminowImportConnection} into a {@link TerminowStore.Host}, the one
 * place the normalised model meets the app's host record. Kept pure (no store, no Android) so the
 * mapping — protocol, port, group, tags and credentials — can be tested on its own, and so parsing
 * and persistence stay separate: the caller saves the returned host through {@link TerminowStore}.
 *
 * <p>Credentials follow the rules: a readable password is copied only when the person opted in; an
 * external key is set only when it resolves to a local Keychain key, otherwise it is left for the
 * person to map. The host record encrypts the password at rest through the existing keystore, so no
 * credential is written anywhere in clear.
 */
public final class TerminowImportPersist {

    /** Resolves an external key reference (a path or name from the source) to a local key path, or null. */
    public interface KeyResolver {
        @Nullable String resolve(@NonNull String reference);
    }

    private TerminowImportPersist() {}

    @NonNull
    public static TerminowStore.Host toHost(@NonNull TerminowImportConnection c, boolean importPasswords,
                                            @NonNull KeyResolver keys, @NonNull String defaultGroup) {
        TerminowStore.Host host = new TerminowStore.Host();
        host.label = clip(c.name.replaceAll("\\p{Cntrl}", " ").trim(), 100);
        host.address = c.host.trim();
        host.username = c.username.trim();

        // Only SSH/SFTP reach here as importable; both are TermiNow SSH hosts (SFTP uses the SSH login).
        host.protocol = TerminowStore.Host.PROTOCOL_SSH;
        host.port = c.effectivePort();

        String group = c.groupPath().isEmpty() ? defaultGroup : c.groupPath();
        host.group = clip(group.replaceAll("\\p{Cntrl}", " ").trim(), 64);

        addTag(host, "Imported");
        addTag(host, c.source);
        // An SFTP site is not promised a terminal shell; mark it rather than assume one.
        if (c.protocol == TerminowImportConnection.Protocol.SFTP) addTag(host, "SFTP");

        if (c.credential == TerminowImportConnection.Credential.PASSWORD && importPasswords && !c.password.isEmpty())
            host.password = c.password;

        if (c.credential == TerminowImportConnection.Credential.KEY_REFERENCE && !c.keyReference.isEmpty()) {
            String local = keys.resolve(c.keyReference);
            if (local != null) host.keyPath = local;
        }
        return host;
    }

    /** Update an existing host in place from an import, without ever clearing a credential it already has. */
    public static void updateExisting(@NonNull TerminowStore.Host existing, @NonNull TerminowImportConnection c,
                                      boolean importPasswords, @NonNull KeyResolver keys) {
        if (!c.name.trim().isEmpty()) existing.label = clip(c.name.replaceAll("\\p{Cntrl}", " ").trim(), 100);
        existing.port = c.effectivePort();
        if (!c.groupPath().isEmpty()) existing.group = clip(c.groupPath(), 64);
        addTag(existing, "Imported");
        addTag(existing, c.source);

        // Only fill a credential that the person chose to import; never overwrite an existing one blindly.
        if (importPasswords && c.credential == TerminowImportConnection.Credential.PASSWORD
            && !c.password.isEmpty() && existing.password.isEmpty())
            existing.password = c.password;
        if (c.credential == TerminowImportConnection.Credential.KEY_REFERENCE && existing.keyPath.isEmpty()) {
            String local = keys.resolve(c.keyReference);
            if (local != null) existing.keyPath = local;
        }
    }

    private static void addTag(@NonNull TerminowStore.Host host, @NonNull String tag) {
        tag = tag.trim();
        if (TerminowStore.isValidTag(tag) && !host.tags.contains(tag) && host.tags.size() < 20) host.tags.add(tag);
    }

    @NonNull
    private static String clip(@NonNull String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
