package com.termux.app.terminow;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The storage volumes of the device, and the permission to browse them by path.
 *
 * Only the build outside Google Play ({@link BuildConfig#TERMINOW_ALL_FILES_ACCESS}) declares
 * the permissions: All files access on Android 11 and higher, the storage permission on
 * Android 6 to 10. The Google Play build uses folders that the user picks with the Storage
 * Access Framework instead. Nothing here works around Android's restrictions.
 */
public class TerminowStorageAccess {

    public static final int REQUEST_STORAGE_PERMISSION = 2040;

    public static class Volume {
        /** "primary" for Internal Storage, the uuid of others, like "1A2B-3C4D". */
        public final String uuid;
        public final String title;
        @Nullable public final File directory;
        public final boolean primary;
        public final boolean removable;
        public final String state;

        Volume(@NonNull String uuid, @NonNull String title, @Nullable File directory, boolean primary, boolean removable, @NonNull String state) {
            this.uuid = uuid;
            this.title = title;
            this.directory = directory;
            this.primary = primary;
            this.removable = removable;
            this.state = state;
        }

        public boolean isMounted() {
            return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
        }

        /** The id of its file system in the File Manager. */
        @NonNull
        public String getFsId() {
            return "device:" + uuid;
        }

        /** Used, free and total bytes, {@code null} if not known. */
        @Nullable
        public long[] getSpace() {
            if (directory == null) return null;
            try {
                StatFs stat = new StatFs(directory.getPath());
                long total = stat.getTotalBytes();
                long free = stat.getAvailableBytes();
                return new long[]{total - free, free, total};
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** Whether the app can list it by path now. */
        public boolean isReadable() {
            return directory != null && isMounted() && directory.canRead() && directory.list() != null;
        }
    }

    /** Whether this build has the broad file-management mode at all. */
    public static boolean isSupported() {
        return BuildConfig.TERMINOW_ALL_FILES_ACCESS;
    }

    /** Whether the app may read and write shared storage by path now. */
    public static boolean hasAccess(@NonNull Context context) {
        if (!isSupported()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    /**
     * Ask for access: the All files access screen of Android 11 and higher, where the user turns
     * it on or off, or the storage permission dialog before.
     */
    public static void request(@NonNull Activity activity) {
        if (!isSupported()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                // Some devices only have the list of all apps
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }
    }

    /** The mounted volumes of shared storage, Internal Storage first. */
    @NonNull
    public static List<Volume> getVolumes(@NonNull Context context) {
        List<Volume> volumes = new ArrayList<>();
        StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            File primary = Environment.getExternalStorageDirectory();
            volumes.add(new Volume("primary", "Internal Storage", primary, true, false, Environment.getExternalStorageState()));
            return volumes;
        }
        for (StorageVolume storageVolume : manager.getStorageVolumes()) {
            boolean primary = storageVolume.isPrimary();
            String uuid = primary ? "primary" : storageVolume.getUuid();
            if (uuid == null) continue;
            String title = storageVolume.getDescription(context);
            Volume volume = new Volume(uuid, title != null ? title : uuid, directoryOf(storageVolume), primary,
                storageVolume.isRemovable(), storageVolume.getState());
            if (volume.isMounted()) {
                if (primary) volumes.add(0, volume);
                else volumes.add(volume);
            }
        }
        return volumes;
    }

    @Nullable
    private static File directoryOf(@NonNull StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.getDirectory();
        if (volume.isPrimary()) return Environment.getExternalStorageDirectory();
        // Before Android 11 the path of a volume is only available like this, the usual mount point
        String uuid = volume.getUuid();
        if (uuid == null) return null;
        File guess = new File("/storage/" + uuid);
        return guess.isDirectory() ? guess : null;
    }

}
