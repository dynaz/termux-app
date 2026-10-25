package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * IPv4 address and CIDR parsing for Network Discovery. A network is never assumed to be {@code /24};
 * the prefix is taken from the CIDR, the address count is computed from it, and a scan is bounded by a
 * caller-supplied maximum so an oversized range (for example a {@code /16}) is refused with a clear
 * count rather than enumerated. Everything here is pure and has no sockets or threads.
 */
public final class TerminowCidr {

    private TerminowCidr() {}

    /** A parsed CIDR: the network base address and the prefix length. */
    public static final class Cidr {
        public final int base;       // network address (host bits cleared), as a 32-bit int
        public final int prefix;     // 0..32

        Cidr(int base, int prefix) {
            this.base = base;
            this.prefix = prefix;
        }

        /** The total number of addresses in the block, e.g. 256 for /24, 128 for /25, 2 for /31. */
        public long addressCount() {
            return 1L << (32 - prefix);
        }

        public boolean withinLimit(int maxAddresses) {
            return addressCount() <= maxAddresses;
        }

        /** The network base as dotted quad. */
        @NonNull
        public String baseAddress() {
            return toDotted(base);
        }

        /**
         * The addresses of the block, in order. Throws {@link IllegalStateException} if the block has
         * more than {@code maxAddresses}, so a scan never enumerates an oversized range by accident.
         */
        @NonNull
        public List<String> addresses(int maxAddresses) {
            long count = addressCount();
            if (count > maxAddresses) {
                throw new IllegalStateException("Range of " + count + " addresses exceeds the limit of " + maxAddresses);
            }
            List<String> out = new ArrayList<>((int) count);
            for (long i = 0; i < count; i++) out.add(toDotted((int) (base + i)));
            return out;
        }
    }

    /** Parse {@code a.b.c.d/p}; returns {@code null} if the address or prefix is malformed. */
    @Nullable
    public static Cidr parse(@Nullable String text) {
        if (text == null) return null;
        String s = text.trim();
        int slash = s.indexOf('/');
        if (slash < 0) return null;
        Integer addr = parseIpv4(s.substring(0, slash));
        if (addr == null) return null;
        int prefix;
        try {
            prefix = Integer.parseInt(s.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > 32) return null;
        int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
        return new Cidr(addr & mask, prefix);
    }

    /** True for a valid dotted-quad IPv4 address. */
    public static boolean isValidIpv4(@Nullable String s) {
        return parseIpv4(s) != null;
    }

    /** True for a TCP/UDP port in 1..65535. */
    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /** The IPv4 address as a 32-bit int, or {@code null} if it is not four octets of 0..255. */
    @Nullable
    public static Integer parseIpv4(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        String[] parts = t.split("\\.", -1);
        if (parts.length != 4) return null;
        int value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return null;
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) < '0' || part.charAt(i) > '9') return null;
            int octet = Integer.parseInt(part);
            if (octet > 255) return null;
            value = (value << 8) | octet;
        }
        return value;
    }

    @NonNull
    public static String toDotted(int addr) {
        return ((addr >>> 24) & 0xFF) + "." + ((addr >>> 16) & 0xFF) + "." + ((addr >>> 8) & 0xFF) + "." + (addr & 0xFF);
    }
}
