package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowStore.Host;

import java.util.List;

/**
 * Turning a discovered device into a saved {@link Host}, reusing the existing Host model. Saving
 * prefills the address, the detected port and a protocol hint, and tags it {@code Discovered}; it never
 * guesses credentials. A device whose address matches an existing host is offered as a possible
 * duplicate to open or update rather than a new entry — a suggestion the user confirms, not an identity
 * claim, since the same private IP on a different network may be a different device.
 */
public final class TerminowDiscoveryHosts {

    public static final String TAG_DISCOVERED = "Discovered";

    private TerminowDiscoveryHosts() {}

    /** An existing host with the same address, to offer open/update instead of a duplicate, or null. */
    @Nullable
    public static Host findPossibleDuplicate(@NonNull List<Host> hosts, @NonNull String deviceIp) {
        for (Host h : hosts) if (deviceIp.equals(h.address)) return h;
        return null;
    }

    /**
     * A new Host prefilled from a device: its address, the detected port and a protocol hint (SSH when
     * 22 is open, RDP when only 3389 is open), and a {@code Discovered} tag. Credentials are left empty.
     */
    @NonNull
    public static Host prefill(@NonNull TerminowDiscoveredDevice device) {
        Host host = new Host();
        host.address = device.ip;
        host.label = device.hostname; // empty when the hostname was not discovered
        if (device.openPorts.contains(22)) {
            host.protocol = Host.PROTOCOL_SSH;
            host.port = 22;
        } else if (device.openPorts.contains(3389)) {
            host.protocol = Host.PROTOCOL_RDP;
            host.port = 3389;
        } else {
            host.protocol = Host.PROTOCOL_SSH;
            host.port = device.openPorts.isEmpty() ? 22 : device.openPorts.iterator().next();
        }
        if (!host.tags.contains(TAG_DISCOVERED)) host.tags.add(TAG_DISCOVERED);
        return host;
    }
}
