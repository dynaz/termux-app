package com.termux.shared.android;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Utils for Developer Options -> Feature Flags. The page won't show in user/production builds and
 * is only shown in userdebug builds.
 * https://cs.android.com/android/_/android/platform/frameworks/base/+/09dcdad5ebc159861920f090e07da60fac71ac0a:core/java/android/util/FeatureFlagUtils.java
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r31:packages/apps/Settings/src/com/android/settings/development/featureflags/FeatureFlagsPreferenceController.java;l=42
 *
 * The feature flags value can be modified in two ways.
 *
 * 1. sysprops with `setprop` command with root. Will be unset by default.
 *  Set value: `setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false`
 *  Get value: `getprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs`
 *  Unset value: `setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs ""`
 * Running `setprop` command requires root and even adb `shell` user cannot modify the values
 * since selinux will not allow it by default. Some props like `settings_dynamic_system` can be
 * set since they are exempted for `shell` in sepolicy.
 *
 * init: Unable to set property 'persist.sys.fflag.override.settings_enable_monitor_phantom_procs' from uid:2000 gid:2000 pid:9576: SELinux permission check failed
 * [ 1034.877067] type=1107 audit(1644436809.637:34): uid=0 auid=4294967295 ses=4294967295 subj=u:r:init:s0 msg='avc: denied { set } for property=persist.sys.fflag.override.settings_enable_monitor_phantom_procs pid=9576 uid=2000 gid=2000 scontext=u:r:shell:s0 tcontext=u:object_r:system_prop:s0 tclass=property_service permissive=0'
 *
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r4:system/sepolicy/private/property_contexts;l=71
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r4:system/sepolicy/private/shell.te;l=149
 *
 * 2. settings global list with adb or root. Will be unset by default. This takes precedence over
 * sysprop value since `FeatureFlagUtils.isEnabled()`
 * checks its value first. Override precedence: Settings.Global -> sys.fflag.override.* -> static list.
 * Set value: `adb shell settings put global settings_enable_monitor_phantom_procs false`
 * Get value: adb shell settings get global settings_enable_monitor_phantom_procs`
 * Unset value: `adb shell settings delete global settings_enable_monitor_phantom_procs`
 *
 * https://cs.android.com/android/_/android/platform/frameworks/base/+/refs/tags/android-12.0.0_r31:core/java/android/util/FeatureFlagUtils.java;l=113
 *
 * The feature flag values can be modified in user builds with settings global list, but since the
 * developer options feature flags page is not shown and considering that getprop values for features
 * will be unset by default and settings global list will not be set either and there is no shell API,
 * it would require an android app process to read the hidden {@code android.util.FeatureFlagUtils}
 * class, which is a non-SDK interface. TermiNow does not use non-SDK interfaces, so the values are
 * reported as unknown.
 */
public class FeatureFlagUtils {

    public enum FeatureFlagValue {

        /** Unknown like due to exception raised while getting value. */
        UNKNOWN("<unknown>"),

        /** Flag is unsupported on current android build. */
        UNSUPPORTED("<unsupported>"),

        /** Flag is enabled. */
        TRUE("true"),

        /** Flag is not enabled. */
        FALSE("false");

        private final String name;

        FeatureFlagValue(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

    }

    /**
     * Get {@link FeatureFlagValue} for a feature.
     *
     * Android has no public API to read its feature flags ({@code android.util.FeatureFlagUtils}
     * is not part of the SDK), and TermiNow does not use non-SDK interfaces, so the value is
     * always {@link FeatureFlagValue#UNKNOWN}. It was only shown in the debug info of reports.
     *
     * @param context The {@link Context} for operations.
     * @param feature The {@link String} name for feature.
     * @return Returns {@link FeatureFlagValue#UNKNOWN}.
     */
    @NonNull
    public static FeatureFlagValue getFeatureFlagValueString(@NonNull Context context, @NonNull String feature) {
        return FeatureFlagValue.UNKNOWN;
    }

}
