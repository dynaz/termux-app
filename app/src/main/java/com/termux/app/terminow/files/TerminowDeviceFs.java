package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * A storage volume of the device, like Internal Storage or an SD card, browsed by path. This
 * needs All files access on Android 11 and higher, and the storage permission before, which only
 * the build outside Google Play asks for.
 *
 * It covers shared storage only. Android keeps `Android/data` and `Android/obb`, the private
 * files of other apps, out of reach even with All files access, and they are shown as protected,
 * never as empty. Links are not followed out of the volume.
 */
public class TerminowDeviceFs extends TerminowLocalFs {

    /** A folder that Android protects from all apps, like the private files of other apps. */
    public static class ProtectedException extends IOException {
        public ProtectedException(@NonNull String path) {
            super(path + " is protected by Android: it holds the private files of other apps, which TermiNow can not access");
        }
    }

    public TerminowDeviceFs(@NonNull File root, @NonNull String id, @NonNull String title) throws IOException {
        super(root, id, title);
    }

    /** Whether a path in the volume is one that Android protects, `Android/data` and `Android/obb`. */
    public static boolean isProtected(@NonNull String id) {
        String path = id.toLowerCase(Locale.ROOT);
        return path.equals("android/data") || path.startsWith("android/data/") ||
            path.equals("android/obb") || path.startsWith("android/obb/");
    }

    @NonNull
    @Override
    public Capabilities getCapabilities() {
        Capabilities capabilities = new Capabilities();
        capabilities.terminal = true;
        capabilities.directShare = true;
        return capabilities;
    }

    @NonNull
    @Override
    public List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException {
        if (isProtected(directory.id)) throw new ProtectedException(getTitle() + "/" + directory.id);
        return super.list(directory, cancel);
    }

    @NonNull
    @Override
    public String getDisplayPath(@NonNull List<Node> breadcrumbs) {
        Node last = breadcrumbs.isEmpty() ? null : breadcrumbs.get(breadcrumbs.size() - 1);
        return last == null || last.id.isEmpty() ? getTitle() : getTitle() + "/" + last.id;
    }

    /**
     * The folder of the volume that a SAF tree document id like "primary:Download/x" or
     * "1A2B-3C4D:Music" points to, as a path relative to the volume, {@code null} if it is
     * not on the volume with this uuid ("primary" for Internal Storage).
     */
    @Nullable
    public static String treeDocumentPath(@NonNull String treeDocumentId, @NonNull String volumeUuid) {
        int colon = treeDocumentId.indexOf(':');
        if (colon < 0) return null;
        if (!treeDocumentId.substring(0, colon).equalsIgnoreCase(volumeUuid)) return null;
        String path = treeDocumentId.substring(colon + 1);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

}
