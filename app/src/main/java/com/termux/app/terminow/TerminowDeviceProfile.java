package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * What kind of device a host is and what actions make sense for it, so that an SSH connection is not
 * treated as a Linux shell everywhere. This is the capability model behind the "Add Environment"
 * remote devices and virtual labs: a template only sets the device type, icon, help text and the
 * capabilities below — it never creates a new SSH engine.
 *
 * <p><b>Safety:</b> network and hypervisor devices (MikroTik RouterOS, Cisco IOS, VMware ESXi) never
 * get a Linux bootstrap, {@code apt}, {@code bash} or other Linux tooling sent to them automatically;
 * {@link #allowsLinuxBootstrap} is the single guard the rest of the app must check before doing so.
 *
 * <p>A capability that can not be known from the type alone (does this box speak SFTP?) is
 * {@link Cap#UNKNOWN} until a connection or an explicit test shows otherwise, and is shown as
 * "not checked" rather than assumed supported.
 */
public final class TerminowDeviceProfile {

    /** The device type stored on a host. The stored key is the enum name lower-cased. */
    public enum Type {
        GENERIC_LINUX, SYNOLOGY, MIKROTIK, CISCO, ESXI, OTHER_SSH
    }

    /** A capability that is either known to be supported, known not to be, or not yet known. */
    public enum Cap { SUPPORTED, UNSUPPORTED, UNKNOWN }

    private TerminowDeviceProfile() {}

    /**
     * The device type stored on a host. An empty or unknown value is {@link Type#GENERIC_LINUX}, so
     * hosts saved before this existed keep behaving as a normal Linux server (migration-safe).
     */
    @NonNull
    public static Type typeOf(String stored) {
        if (stored == null) return Type.GENERIC_LINUX;
        switch (stored.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "synology": return Type.SYNOLOGY;
            case "mikrotik": return Type.MIKROTIK;
            case "cisco": return Type.CISCO;
            case "esxi": return Type.ESXI;
            case "other_ssh": case "other": return Type.OTHER_SSH;
            case "generic_linux": case "generic": case "": return Type.GENERIC_LINUX;
            default: return Type.GENERIC_LINUX;
        }
    }

    /** The value to store for a type. */
    @NonNull
    public static String key(@NonNull Type type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a Linux bootstrap, package manager or POSIX shell tooling may be sent to this device at
     * all. True only for a real Linux host. Synology's DSM is Linux underneath but TermiNow does not
     * install into it automatically, so it is false here too — the user runs its own tools.
     *
     * <p><b>Never</b> true for RouterOS, Cisco IOS or ESXi.
     */
    public static boolean allowsLinuxBootstrap(@NonNull Type type) {
        return type == Type.GENERIC_LINUX;
    }

    /** Whether the SSH session is a POSIX shell where Linux commands make sense. */
    public static boolean isLinuxShell(@NonNull Type type) {
        return type == Type.GENERIC_LINUX || type == Type.SYNOLOGY;
    }

    /** Whether the device speaks its own CLI rather than a POSIX shell (no bash/apt/fish is sent). */
    public static boolean isNativeCli(@NonNull Type type) {
        return type == Type.MIKROTIK || type == Type.CISCO || type == Type.ESXI;
    }

    /** Whether SFTP/file browsing is offered, before an actual connection or test confirms it. */
    @NonNull
    public static Cap sftp(@NonNull Type type) {
        switch (type) {
            case GENERIC_LINUX:
            case SYNOLOGY:
                return Cap.SUPPORTED;
            case MIKROTIK:
            case CISCO:
                return Cap.UNSUPPORTED;
            case ESXI:
            case OTHER_SSH:
            default:
                return Cap.UNKNOWN;
        }
    }

    /** Whether a Web Admin link is typically available (only shown when the host actually has a URL). */
    @NonNull
    public static Cap webAdmin(@NonNull Type type) {
        switch (type) {
            case SYNOLOGY:
            case MIKROTIK:
            case ESXI:
                return Cap.SUPPORTED;
            default:
                return Cap.UNKNOWN;
        }
    }
}
