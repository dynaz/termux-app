package com.termux.shared.android;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;


public class UserUtils {

    public static final String LOG_TAG = "UserUtils";

    /**
     * Get the user name for user id with a call to {@link #getNameForUidFromPackageManager(Context, int)}
     * and if that fails, the name of a fixed Android system user id, see {@link #getNameForSystemUid(int)}.
     *
     * @param context The {@link Context} for operations.
     * @param uid The user id.
     * @return Returns the user name if found, otherwise {@code null}.
     */
    @Nullable
    public static String getNameForUid(@NonNull Context context, int uid) {
        String name = getNameForUidFromPackageManager(context, uid);
        if (name == null)
            name = getNameForSystemUid(uid);
        return name;
    }

    /**
     * Get the user name for user id with a call to {@link PackageManager#getNameForUid(int)}.
     *
     * This will not return user names for non app user id like for root user 0, use {@link #getNameForSystemUid(int)}
     * for those.
     *
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/content/pm/PackageManager.java;l=5556
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ApplicationPackageManager.java;l=1028
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java;l=10293
     *
     * @param context The {@link Context} for operations.
     * @param uid The user id.
     * @return Returns the user name if found, otherwise {@code null}.
     */
    @Nullable
    public static String getNameForUidFromPackageManager(@NonNull Context context, int uid) {
        if (uid < 0) return null;

        try {
            String name = context.getPackageManager().getNameForUid(uid);
            if (name != null && name.endsWith(":" + uid))
                name = name.replaceAll(":" + uid + "$", ""); // Remove ":<uid>" suffix
            return name;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get name for uid \"" + uid + "\" from package manager", e);
            return null;
        }
    }

    /**
     * The name of a fixed Android system user id, which {@link PackageManager#getNameForUid(int)}
     * does not know. The ids are fixed by Android in android_filesystem_config.h. Other non app
     * user ids return {@code null}, as there is no public API for `getpwuid()` and TermiNow does
     * not use non-SDK interfaces.
     *
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:system/core/libcutils/include/private/android_filesystem_config.h;l=43
     *
     * @param uid The user id.
     * @return Returns the user name if known, otherwise {@code null}.
     */
    @Nullable
    public static String getNameForSystemUid(int uid) {
        switch (uid) {
            case 0: return "root";
            case 1000: return "system";
            case 2000: return "shell";
            default: return null;
        }
    }

}
