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

        /** The subnet mask in dotted form, e.g. {@code 255.255.255.0}. */
        @NonNull
        public String netmask() {
            int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return TerminowCidr.toDotted(mask);
        }

        /** The broadcast address of this network, e.g. {@code 192.168.1.255}, or null if invalid. */
        @Nullable
        public String broadcast() {
            TerminowCidr.Cidr c = TerminowCidr.parse(ipv4 + "/" + prefix);
            if (c == null) return null;
            int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return TerminowCidr.toDotted(c.base | ~mask);
        }
    }

    /**
     * The IPv4 default gateway, read from {@code /proc/net/route} (the row whose destination is
     * {@code 0.0.0.0}). Best-effort: returns null when the file is unreadable or has no default route.
     * The gateway address is stored little-endian hex in that file.
     */
    @Nullable
    public static String defaultGateway() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/route"));
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] f = line.split("\\s+");
                if (f.length < 3) continue;
                if (!"00000000".equalsIgnoreCase(f[1])) continue;       // not the default route
                if ("00000000".equalsIgnoreCase(f[2])) continue;        // no gateway
                long g = Long.parseLong(f[2], 16);
                int be = Integer.reverseBytes((int) g);                 // file is little-endian
                return TerminowCidr.toDotted(be);
            }
        } catch (Exception e) {
            // Unreadable on some Android versions; the header just omits the gateway line.
        } finally {
            if (r != null) try { r.close(); } catch (java.io.IOException ignored) {}
        }
        return null;
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
