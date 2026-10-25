package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A device found by Network Discovery. Unknown information stays unknown: the hostname, MAC and vendor
 * are empty until actually observed and are never fabricated, and an open TCP port is recorded as a
 * port, not as a verified product or vendor.
 *
 * <p>A device's identity is its IP <b>within its scan scope</b> (the source and network it was found
 * on), not the IP alone, so the same private address discovered from two different networks — for
 * example {@code 192.168.1.10} on this phone's Wi-Fi and on a remote host's LAN — is two devices, not
 * one. {@link #dedup} merges only devices with the same identity.
 */
public final class TerminowDiscoveredDevice {

    /** The scan scope: a stable id of the source and network, e.g. {@code local:wlan0:192.168.1.0/24}. */
    @NonNull public final String scopeId;
    @NonNull public final String ip;
    @NonNull public String hostname = "";
    @NonNull public final Set<Integer> openPorts = new TreeSet<>();
    /** Only set when actually observed; never guessed. */
    @NonNull public String mac = "";
    @NonNull public String vendor = "";
    public long firstSeenMs;
    public long lastSeenMs;

    public TerminowDiscoveredDevice(@NonNull String scopeId, @NonNull String ip) {
        this.scopeId = scopeId;
        this.ip = ip;
    }

    /** The identity used for deduplication: the scope and the IP together, never the IP alone. */
    @NonNull
    public String identity() {
        return scopeId + "/" + ip;
    }

    /** Merge another observation of the same device into this one (union of ports, latest last-seen). */
    public void mergeFrom(@NonNull TerminowDiscoveredDevice other) {
        openPorts.addAll(other.openPorts);
        if (!other.hostname.isEmpty()) hostname = other.hostname;
        if (!other.mac.isEmpty()) mac = other.mac;
        if (!other.vendor.isEmpty()) vendor = other.vendor;
        if (other.firstSeenMs != 0 && (firstSeenMs == 0 || other.firstSeenMs < firstSeenMs)) firstSeenMs = other.firstSeenMs;
        if (other.lastSeenMs > lastSeenMs) lastSeenMs = other.lastSeenMs;
    }

    /**
     * Deduplicate a list of observations by {@link #identity()}, merging duplicates, preserving the
     * order in which each device was first seen.
     */
    @NonNull
    public static List<TerminowDiscoveredDevice> dedup(@NonNull List<TerminowDiscoveredDevice> devices) {
        Map<String, TerminowDiscoveredDevice> byIdentity = new LinkedHashMap<>();
        for (TerminowDiscoveredDevice d : devices) {
            TerminowDiscoveredDevice existing = byIdentity.get(d.identity());
            if (existing == null) byIdentity.put(d.identity(), d);
            else existing.mergeFrom(d);
        }
        return new java.util.ArrayList<>(byIdentity.values());
    }

    /**
     * Whether this device matches a saved host: the same address, and — when both have a scope network
     * — the same scope, so a host saved from one network is not matched to a same-IP device on another.
     * Returns the reason classification as "Possible" is left to the UI; this is only address identity.
     */
    public boolean sameAddressAs(@Nullable String hostAddress) {
        return hostAddress != null && hostAddress.equals(ip);
    }
}
