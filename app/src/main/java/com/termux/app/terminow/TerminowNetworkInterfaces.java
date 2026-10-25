package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The local IPv4 interfaces and their networks, for choosing a scan target. The network prefix is the
 * real one reported by the interface — a network is never assumed to be {@code /24} — and each
 * interface (including a VPN's) is listed separately so the user picks the right one rather than the
 * app guessing when several are active.
 */
public final class TerminowNetworkInterfaces {

    private TerminowNetworkInterfaces() {}

    public static final class Iface {
        @NonNull public final String name;
        @NonNull public final String ipv4;
        public final int prefix;

        public Iface(@NonNull String name, @NonNull String ipv4, int prefix) {
            this.name = name;
            this.ipv4 = ipv4;
            this.prefix = prefix;
        }

        /** The network of this interface as a CIDR, e.g. {@code 192.168.1.0/24}, or null if invalid. */
        @Nullable
        public String cidr() {
            TerminowCidr.Cidr c = TerminowCidr.parse(ipv4 + "/" + prefix);
            return c == null ? null : c.baseAddress() + "/" + c.prefix;
        }
    }

    /** The up, non-loopback IPv4 interfaces of this device, each with its real prefix length. */
    @NonNull
    public static List<Iface> list() {
        List<Iface> out = new ArrayList<>();
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() == null || ia.getAddress().getAddress().length != 4) continue; // IPv4 only
                    String ip = ia.getAddress().getHostAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    if (ip != null && prefix >= 0 && prefix <= 32) out.add(new Iface(ni.getName(), ip, prefix));
                }
            }
        } catch (Exception e) {
            // Interface enumeration is best-effort; the UI falls back to manual CIDR input.
        }
        return out;
    }
}
