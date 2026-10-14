package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Linux distributions that TermiNow can install, each from an official release archive of
 * the distribution itself, pinned to one file and checked with its SHA-256 before anything is
 * done with it. Nothing is downloaded and run as a script: proot-distro of the runtime installs
 * the checked archive as a local file.
 *
 * The checksums are the ones the distributions publish: Ubuntu's SHA256SUMS of the release,
 * Alpine's latest-releases.yaml, and the layer digest of Debian's official image in the commit
 * of debuerreotype's artifacts that the URL is pinned to. They were also checked by downloading.
 */
public final class TerminowLinuxCatalog {

    public enum Support {
        /** Can be installed on this device. */
        AVAILABLE,
        /** No verified archive for the architecture of this device. */
        UNSUPPORTED_ARCH,
        /** Listed, but installing it has not been verified with the TermiNow runtime. */
        EXPERIMENTAL
    }

    public static class Entry {
        /** The ID of os-release, also the default name of the container. */
        public final String distroId;
        public final String name;
        public final String version;
        public final String description;
        public final String arch;
        /** Empty for an entry that can not be installed. */
        public final String url;
        public final String sha256;
        public final long downloadBytes;
        /** About what the rootfs takes once unpacked, for the space check. */
        public final long installedBytes;
        public final boolean experimental;

        Entry(@NonNull String distroId, @NonNull String name, @NonNull String version, @NonNull String description,
              @NonNull String arch, @NonNull String url, @NonNull String sha256, long downloadBytes, long installedBytes,
              boolean experimental) {
            this.distroId = distroId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.arch = arch;
            this.url = url;
            this.sha256 = sha256;
            this.downloadBytes = downloadBytes;
            this.installedBytes = installedBytes;
            this.experimental = experimental;
        }

        @NonNull
        public Support supportOn(@NonNull String deviceArch) {
            if (experimental || url.isEmpty()) return Support.EXPERIMENTAL;
            return arch.equals(deviceArch) ? Support.AVAILABLE : Support.UNSUPPORTED_ARCH;
        }
    }

    public static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        new Entry("ubuntu", "Ubuntu", "24.04.5 LTS", "The popular Linux of Canonical. Ubuntu Base, the minimal system, with apt.",
            "aarch64", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", 29936675L, 101L * 1024 * 1024, false),
        new Entry("debian", "Debian", "13.7 (trixie)", "The universal operating system, stable and conservative, with apt.",
            "aarch64", "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/oci/blobs/rootfs.tar.gz",
            "df9831efb49cab24dec437e8482f2a9ddbd9e239131fa3ffe095dc1492081e1d", 49761105L, 142L * 1024 * 1024, false),
        new Entry("alpine", "Alpine Linux", "3.24.2", "Small and simple, with musl and BusyBox, and apk for packages.",
            "aarch64", "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
            "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773", 4028030L, 9L * 1024 * 1024, false),
        new Entry("kali", "Kali Linux", "rolling", "Security testing distribution. Experimental: its installation has not been verified with TermiNow yet.",
            "aarch64", "", "", 0, 0, true)
    ));

    private TerminowLinuxCatalog() {}

    @Nullable
    public static Entry get(@NonNull String distroId) {
        for (Entry entry : ENTRIES) if (entry.distroId.equals(distroId)) return entry;
        return null;
    }

    /** The architecture of this device in the words of proot-distro, from the first ABI of Android. */
    @NonNull
    public static String archOfAbi(@NonNull String abi) {
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "arm";
            case "x86_64": return "x86_64";
            case "x86": return "i686";
            case "riscv64": return "riscv64";
            default: return abi;
        }
    }


    /** The entry of a distribution, {@code null} if there is none. */
    @androidx.annotation.Nullable
    public static Entry find(@androidx.annotation.NonNull String distroId) {
        for (Entry entry : ENTRIES) if (entry.distroId.equals(distroId)) return entry;
        return null;
    }

}
