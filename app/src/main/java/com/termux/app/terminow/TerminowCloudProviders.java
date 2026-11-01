package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Resolves a configured cloud destination (S3 or a connected OAuth account) into a ready-to-use
 * {@link TerminowBackupProvider}, so manual backup, scheduled backup, listing and restore all build
 * their provider the same way. For OAuth providers it wires a refreshing token source that persists
 * refreshed tokens back into the store, so a backup never fails merely because the access token aged.
 */
final class TerminowCloudProviders {

    static final String S3 = "s3";

    private TerminowCloudProviders() {}

    /** A provider for {@code kind}, or {@code null} if that destination is not set up / connected. */
    @Nullable
    static TerminowBackupProvider resolve(@NonNull TerminowStore store, @NonNull String kind) {
        switch (kind) {
            case S3: {
                TerminowS3Config c = store.getS3Config();
                return (c != null && c.isComplete()) ? c.toProvider() : null;
            }
            case TerminowOAuthClients.DROPBOX: {
                TerminowHttpProvider.TokenSource src = source(store, kind);
                return src == null ? null : new TerminowDropboxProvider(src);
            }
            case TerminowOAuthClients.ONEDRIVE: {
                TerminowHttpProvider.TokenSource src = source(store, kind);
                return src == null ? null : new TerminowOneDriveProvider(src);
            }
            case TerminowOAuthClients.GDRIVE: {
                TerminowHttpProvider.TokenSource src = source(store, kind);
                return src == null ? null : new TerminowDriveProvider(src);
            }
            default:
                return null;
        }
    }

    /** True when {@code kind} can be used as a backup destination right now. */
    static boolean isConnected(@NonNull TerminowStore store, @NonNull String kind) {
        if (S3.equals(kind)) {
            TerminowS3Config c = store.getS3Config();
            return c != null && c.isComplete();
        }
        return store.isOAuthConnected(kind) && TerminowOAuthClients.endpoints(kind) != null;
    }

    /** The provider for whichever destination the user selected, or {@code null} if none is ready. */
    @Nullable
    static TerminowBackupProvider selected(@NonNull TerminowStore store) {
        String kind = store.getCloudDestination();
        return kind.isEmpty() ? null : resolve(store, kind);
    }

    /** The backup-encryption password to use, or {@code null} for an unencrypted backup. */
    @Nullable
    static char[] password(@NonNull TerminowStore store) {
        String global = store.getCloudPassword();
        if (!global.isEmpty()) return global.toCharArray();
        TerminowS3Config s3 = store.getS3Config();   // back-compat: password set on an older S3 config
        if (s3 != null && !s3.encryptionPassword.isEmpty()) return s3.encryptionPassword.toCharArray();
        return null;
    }

    @Nullable
    private static TerminowHttpProvider.TokenSource source(@NonNull TerminowStore store, @NonNull String kind) {
        TerminowOAuth.Tokens tokens = store.getOAuthTokens(kind);
        TerminowOAuth.Endpoints ep = TerminowOAuthClients.endpoints(kind);
        if (tokens == null || ep == null) return null;
        return TerminowOAuth.source(ep, tokens, refreshed -> store.setOAuthTokens(kind, refreshed));
    }
}
