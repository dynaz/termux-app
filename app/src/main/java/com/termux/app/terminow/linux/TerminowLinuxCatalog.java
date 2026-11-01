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
 * of debuerreotype's artifacts that the URL is pinned to, and Kali's SHA256SUMS of the pinned
 * NetHunter rootfs snapshot (kali.download/nethunter-images/kali-2026.3). They were also checked
 * by downloading. Kali's rootfs is a .tar.xz wrapped in one top-level directory; proot-distro of
 * the runtime unpacks it with its own path checks and strips that leading directory.
 *
 * A distribution can have a rootfs for more than one architecture (see {@link RootFs}). The device
 * architecture (from {@link #archOfAbi}) selects which one is installed; there is never a fallback
 * to another architecture's rootfs, so e.g. an x86_64 device never installs an aarch64 rootfs. A
 * distribution with no rootfs for the device architecture reports {@link Support#UNSUPPORTED_ARCH}.
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

    /** One rootfs archive of a distribution, for a single architecture. */
    public static final class RootFs {
        /** The architecture in the words of proot-distro: aarch64, x86_64, arm, i686, riscv64. */
        public final String arch;
        /** Empty for a rootfs that can not be installed (a placeholder for an arch not yet built). */
        public final String url;
        public final String sha256;
        public final long downloadBytes;
        /** About what the rootfs takes once unpacked, for the space check. */
        public final long installedBytes;

        public RootFs(@NonNull String arch, @NonNull String url, @NonNull String sha256,
                      long downloadBytes, long installedBytes) {
            this.arch = arch;
            this.url = url;
            this.sha256 = sha256;
            this.downloadBytes = downloadBytes;
            this.installedBytes = installedBytes;
        }

        public boolean installable() {
            return !url.isEmpty();
        }
    }

    public static class Entry {
        /** The ID of os-release, also the default name of the container. */
        public final String distroId;
        public final String name;
        public final String version;
        public final String description;
        public final boolean experimental;
        /** The rootfs of this distribution, one per architecture. The first is the primary. */
        public final List<RootFs> rootfs;

        // The primary (first) rootfs, mirrored as fields for display defaults and so existing callers
        // that do not resolve an architecture keep working. Architecture-specific code must use
        // rootfsFor(deviceArch) / forArch(deviceArch) instead, so the right architecture is installed.
        public final String arch;
        public final String url;
        public final String sha256;
        public final long downloadBytes;
        public final long installedBytes;

        /** A distribution with rootfs for one or more architectures. */
        Entry(@NonNull String distroId, @NonNull String name, @NonNull String version, @NonNull String description,
              boolean experimental, @NonNull RootFs... rootfs) {
            if (rootfs.length == 0) throw new IllegalArgumentException("an entry needs at least one rootfs");
            this.distroId = distroId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.experimental = experimental;
            this.rootfs = Collections.unmodifiableList(Arrays.asList(rootfs));
            RootFs primary = rootfs[0];
            this.arch = primary.arch;
            this.url = primary.url;
            this.sha256 = primary.sha256;
            this.downloadBytes = primary.downloadBytes;
            this.installedBytes = primary.installedBytes;
        }

        /** The single-architecture form kept for the distributions that have only an aarch64 rootfs. */
        Entry(@NonNull String distroId, @NonNull String name, @NonNull String version, @NonNull String description,
              @NonNull String arch, @NonNull String url, @NonNull String sha256, long downloadBytes, long installedBytes,
              boolean experimental) {
            this(distroId, name, version, description, experimental,
                new RootFs(arch, url, sha256, downloadBytes, installedBytes));
        }

        /** The installable rootfs for a device architecture, {@code null} if there is none. */
        @Nullable
        public RootFs rootfsFor(@NonNull String deviceArch) {
            for (RootFs r : rootfs) if (r.arch.equals(deviceArch) && r.installable()) return r;
            return null;
        }

        private boolean hasInstallableRootFs() {
            for (RootFs r : rootfs) if (r.installable()) return true;
            return false;
        }

        @NonNull
        public Support supportOn(@NonNull String deviceArch) {
            if (experimental || !hasInstallableRootFs()) return Support.EXPERIMENTAL;
            return rootfsFor(deviceArch) != null ? Support.AVAILABLE : Support.UNSUPPORTED_ARCH;
        }

        /**
         * This entry with the rootfs of the given architecture as its primary, so its mirrored fields
         * (url, sha256, arch, downloadBytes, installedBytes) describe exactly what will be installed on
         * that device. {@code null} if the architecture has no installable rootfs.
         */
        @Nullable
        public Entry forArch(@NonNull String deviceArch) {
            RootFs rf = rootfsFor(deviceArch);
            if (rf == null) return null;
            return new Entry(distroId, name, version, description, experimental, rf);
        }
    }

    // Ubuntu Base 24.04.5 LTS, the minimal system with apt, for aarch64 (arm64) and x86_64 (amd64).
    // Both checksums are from Ubuntu's SHA256SUMS of the 24.04 release; sizes are the Content-Length
    // of the pinned archives. aarch64 is first (the primary) so the free distro keeps its arm64 values
    // where an architecture is not resolved.
    private static final Entry UBUNTU = new Entry("ubuntu", "Ubuntu", "24.04.5 LTS",
        "The popular Linux of Canonical. Ubuntu Base, the minimal system, with apt.", false,
        new RootFs("aarch64", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", 29936675L, 101L * 1024 * 1024),
        new RootFs("x86_64", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz",
            "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86", 30028293L, 101L * 1024 * 1024));

    public static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        UBUNTU,
        new Entry("debian", "Debian", "13.7 (trixie)", "The universal operating system, stable and conservative, with apt.",
            "aarch64", "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/oci/blobs/rootfs.tar.gz",
            "df9831efb49cab24dec437e8482f2a9ddbd9e239131fa3ffe095dc1492081e1d", 49761105L, 142L * 1024 * 1024, false),
        new Entry("alpine", "Alpine Linux", "3.24.2", "Small and simple, with musl and BusyBox, and apk for packages.",
            "aarch64", "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
            "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773", 4028030L, 9L * 1024 * 1024, false),
        new Entry("kali", "Kali Linux", "rolling (2026.3)",
            "Security testing distribution, the official NetHunter minimal rootfs. Runs through PRoot: Wi-Fi monitor mode, packet injection and kernel modules are not available.",
            "aarch64", "https://kali.download/nethunter-images/kali-2026.3/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz",
            "96aeced81a857a987715a14417af05c2934e3e19052d6151c398a1c215448b60", 125846088L, 850L * 1024 * 1024, false),
        // Arch Linux ARM (aarch64). Its os-release ID is "archarm" (not "arch"); the display name is kept
        // as "Arch Linux ARM". The archive is Termux proot-distro's own aarch64 Arch image, pinned to the
        // immutable v4.17.3 release asset and the SHA-256 from proot-distro's distro-plugins/archlinux.sh
        // manifest at that tag (verified by downloading: size + SHA-256, os-release ID=archarm, and the
        // ELF of usr/bin/bash is AArch64). Like Kali it is a .tar.xz wrapped in one top-level directory
        // (archlinux-aarch64/), which proot-distro of the runtime strips as it unpacks with its own path
        // checks. It is a base image; the user updates it in-place with pacman (signatures stay verified).
        new Entry("archarm", "Arch Linux ARM", "rolling",
            "A customizable Linux environment for advanced users.",
            "aarch64", "https://github.com/termux/proot-distro/releases/download/v4.17.3/archlinux-aarch64-pd-v4.17.3.tar.xz",
            "dc56b998ffa2663209417396c8d70caf87c8052acf41e9a2c6daf24cbd181533", 144808000L, 720L * 1024 * 1024, false)
    ));

    private TerminowLinuxCatalog() {}

    @Nullable
    public static Entry get(@NonNull String distroId) {
        for (Entry entry : ENTRIES) if (entry.distroId.equals(distroId)) return entry;
        return null;
    }

    /** The stable distribution id that is free; everything else is Premium. */
    public static final String FREE_DISTRO_ID = "ubuntu";

    /**
     * Whether a distribution needs Premium to install or launch, by its stable distro id. Ubuntu is
     * free; every other distribution (Debian, Alpine, Kali, and any added later) needs Premium. Keyed on
     * the id, never a display name or version, so renaming or versioning a distro cannot change its tier.
     */
    public static boolean requiresPremium(@NonNull String distroId) {
        return !FREE_DISTRO_ID.equals(distroId);
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
