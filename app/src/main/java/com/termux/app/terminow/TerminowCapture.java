package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the commands for packet capture and analysis — {@code tcpdump} to capture to a {@code .pcap}
 * with hard duration and size limits, and {@code tshark} to read, analyse and export an existing
 * capture. It is pure logic (no I/O), so every limit, filter check and argument is unit-testable.
 *
 * <p>Design rules this enforces: a capture is always bounded (a duration cap via {@code timeout}, a
 * size cap via {@code -C <MB> -W 1}, and an optional packet count), so a run can never fill storage
 * or run forever; filters are validated against a safe character set and rejected otherwise, so a
 * filter can never inject a shell command on a remote host; and analysis always streams through
 * {@code tshark} rather than loading a capture into app memory.</p>
 */
public final class TerminowCapture {

    public static final int DURATION_MIN = 1, DURATION_MAX = 3600, DURATION_DEFAULT = 60;
    public static final int SIZE_MIN_MB = 1, SIZE_MAX_MB = 1024, SIZE_DEFAULT_MB = 10;
    public static final int PACKETS_MAX = 10_000_000;
    public static final int SNAPLEN_DEFAULT = 262144;

    // A capture (BPF) filter: tcpdump primitives — letters, digits, spaces and the few punctuation
    // marks BPF uses (dot, colon, slash for CIDR, parens, brackets, comparisons). No shell metacharacters.
    private static final Pattern CAPTURE_FILTER = Pattern.compile("[A-Za-z0-9 ._:/()\\[\\]<>=!&|+-]*");
    // A Wireshark display filter (tshark -Y): adds quotes and dots for field names and string matches.
    private static final Pattern DISPLAY_FILTER = Pattern.compile("[A-Za-z0-9 ._:/()\\[\\]<>=!&|+\"'\\-,]*");
    private static final Pattern INTERFACE = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private TerminowCapture() {}

    /** A bounded capture request. Values are clamped on construction, so limits cannot be exceeded. */
    public static final class Spec {
        public final String iface;
        public final String filter;
        public final int durationSec;
        public final int maxSizeMb;
        public final int packetLimit;   // 0 = no explicit packet cap (time and size still bound it)
        public final int snaplen;

        public Spec(@NonNull String iface, @NonNull String filter, int durationSec, int maxSizeMb, int packetLimit) {
            this.iface = iface;
            this.filter = filter.trim();
            this.durationSec = clamp(durationSec, DURATION_MIN, DURATION_MAX, DURATION_DEFAULT);
            this.maxSizeMb = clamp(maxSizeMb, SIZE_MIN_MB, SIZE_MAX_MB, SIZE_DEFAULT_MB);
            this.packetLimit = packetLimit <= 0 ? 0 : Math.min(packetLimit, PACKETS_MAX);
            this.snaplen = SNAPLEN_DEFAULT;
        }

        public boolean isValid() {
            return isValidInterface(iface) && isValidCaptureFilter(filter);
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min) return value <= 0 ? fallback : min;
        return Math.min(value, max);
    }

    public static boolean isValidInterface(@NonNull String iface) {
        return INTERFACE.matcher(iface).matches();
    }

    // ---------------------------------------------------------------------------------------
    // Capture capability — whether live capture is actually possible, not merely whether uid is 0

    /**
     * The result of probing whether live capture can work in an environment. In a PRoot/Termux guest
     * {@code id -u} reports 0, but the Linux kernel still denies {@code CAP_NET_RAW} to the sandbox,
     * so a raw-socket capture fails with "Operation not permitted" even as "root". This is why live
     * capture is gated on an actual probe rather than on the user id.
     */
    public enum CapturePrivilege {
        /** tcpdump opened a capture socket (a packet arrived, or the probe timed out while listening). */
        OK,
        /** tcpdump was denied the capture socket — typical of a PRoot guest without CAP_NET_RAW. */
        DENIED,
        /** The probed interface does not exist (capture may still work on another interface). */
        NO_DEVICE,
        /** tcpdump is not installed. */
        NO_TCPDUMP,
        /** The probe produced no decisive signal. */
        UNKNOWN
    }

    /**
     * A shell script that probes capture capability by attempting a single-packet capture to
     * {@code /dev/null}, bounded by {@code timeout}, and prints {@code TERMINOW_CAP_PRIV=<result>}
     * plus a short {@code TERMINOW_CAP_DETAIL=} line of tcpdump's own message. It never writes a real
     * capture file and is safe to run repeatedly. {@code iface} is validated and falls back to
     * {@code any}; it is only ever a value matching {@link #INTERFACE}, so it is safe to embed.
     */
    @NonNull
    public static String probeScript(@NonNull String iface) {
        String dev = isValidInterface(iface) ? iface : "any";
        return "if ! command -v tcpdump >/dev/null 2>&1; then printf 'TERMINOW_CAP_PRIV=notcpdump\\n'; exit 0; fi\n"
            + "out=$(timeout 5 tcpdump -i " + dev + " -c 1 -w /dev/null 2>&1); code=$?\n"
            + "if [ \"$code\" = 0 ] || [ \"$code\" = 124 ]; then printf 'TERMINOW_CAP_PRIV=ok\\n'\n"
            + "elif printf '%s' \"$out\" | grep -qiE 'permitted|permission|denied|cap_net_raw'; then printf 'TERMINOW_CAP_PRIV=denied\\n'\n"
            + "elif printf '%s' \"$out\" | grep -qiE 'no such device|bad interface|no suitable device|siocgifindex|not found'; then printf 'TERMINOW_CAP_PRIV=nodev\\n'\n"
            + "else printf 'TERMINOW_CAP_PRIV=unknown\\n'; fi\n"
            + "printf 'TERMINOW_CAP_DETAIL=%s\\n' \"$(printf '%s' \"$out\" | tr '\\n' ' ' | cut -c1-300)\"\n"
            + "exit 0\n";
    }

    /** Parse {@link #probeScript} output into a {@link CapturePrivilege}; UNKNOWN if no marker is seen. */
    @NonNull
    public static CapturePrivilege parseCapturePrivilege(@NonNull String output) {
        String value = markerValue(output, "TERMINOW_CAP_PRIV=");
        if (value == null) return CapturePrivilege.UNKNOWN;
        switch (value.trim()) {
            case "ok": return CapturePrivilege.OK;
            case "denied": return CapturePrivilege.DENIED;
            case "nodev": return CapturePrivilege.NO_DEVICE;
            case "notcpdump": return CapturePrivilege.NO_TCPDUMP;
            default: return CapturePrivilege.UNKNOWN;
        }
    }

    /** The short tcpdump message the probe captured, for showing the user why capture is unavailable. */
    @NonNull
    public static String parseProbeDetail(@NonNull String output) {
        String value = markerValue(output, "TERMINOW_CAP_DETAIL=");
        return value == null ? "" : value.trim();
    }

    @Nullable
    private static String markerValue(@NonNull String output, @NonNull String marker) {
        for (String line : output.split("\n")) {
            int at = line.indexOf(marker);
            if (at >= 0) return line.substring(at + marker.length());
        }
        return null;
    }

    public static boolean isValidCaptureFilter(@NonNull String filter) {
        return filter.length() <= 1024 && CAPTURE_FILTER.matcher(filter).matches();
    }

    public static boolean isValidDisplayFilter(@NonNull String filter) {
        return filter.length() <= 1024 && DISPLAY_FILTER.matcher(filter).matches();
    }

    /** A capture output path TermiNow chose (not user text): a plain .pcap/.pcapng under a known dir. */
    public static boolean isValidCapturePath(@NonNull String path) {
        if (path.length() > 4096 || path.contains("..") || path.contains("\n")) return false;
        if (!path.startsWith("/")) return false;
        return path.endsWith(".pcap") || path.endsWith(".pcapng");
    }

    // ---------------------------------------------------------------------------------------
    // Capture (tcpdump)

    /**
     * The argv for a bounded local capture: {@code timeout <dur> tcpdump -i IFACE -s SNAP -w FILE
     * -C <MB> -W 1 [-c COUNT] [FILTER...]}. {@code -C <MB> -W 1} stops after one file of that size and
     * {@code timeout} stops at the duration, so the capture is bounded in both size and time. The
     * filter is split into its tokens as separate arguments (tcpdump's expected form).
     *
     * @throws IllegalArgumentException if the spec, output path, or filter is not valid
     */
    @NonNull
    public static List<String> tcpdumpArgv(@NonNull Spec spec, @NonNull String outFile) {
        requireValid(spec, outFile);
        List<String> a = new ArrayList<>();
        a.add("timeout");
        a.add(String.valueOf(spec.durationSec));
        a.add("tcpdump");
        a.add("-i"); a.add(spec.iface);
        a.add("-s"); a.add(String.valueOf(spec.snaplen));
        a.add("-w"); a.add(outFile);
        a.add("-C"); a.add(String.valueOf(spec.maxSizeMb));
        a.add("-W"); a.add("1");
        if (spec.packetLimit > 0) { a.add("-c"); a.add(String.valueOf(spec.packetLimit)); }
        if (!spec.filter.isEmpty()) for (String token : spec.filter.split("\\s+")) if (!token.isEmpty()) a.add(token);
        return a;
    }

    /**
     * A single shell command string for a bounded capture on a remote host over SSH. Every value is
     * validated above and then single-quoted, so the remote shell treats each as one literal word and
     * no value can inject a command. The whole filter is passed as one single-quoted argument —
     * tcpdump joins its trailing arguments into the filter expression — so BPF grouping parentheses
     * and spaces are safe and are never interpreted by the remote shell.
     */
    @NonNull
    public static String remoteTcpdumpCommand(@NonNull Spec spec, @NonNull String outFile) {
        requireValid(spec, outFile);
        StringBuilder sb = new StringBuilder();
        sb.append("timeout ").append(spec.durationSec)
            .append(" tcpdump -i ").append(sq(spec.iface))
            .append(" -s ").append(spec.snaplen)
            .append(" -w ").append(sq(outFile))
            .append(" -C ").append(spec.maxSizeMb).append(" -W 1");
        if (spec.packetLimit > 0) sb.append(" -c ").append(spec.packetLimit);
        if (!spec.filter.isEmpty()) sb.append(' ').append(sq(spec.filter));   // one quoted argument: no shell parsing
        return sb.toString();
    }

    private static void requireValid(@NonNull Spec spec, @NonNull String outFile) {
        if (!spec.isValid()) throw new IllegalArgumentException("invalid capture interface or filter");
        if (!isValidCapturePath(outFile)) throw new IllegalArgumentException("invalid capture output path");
    }

    // ---------------------------------------------------------------------------------------
    // Analysis (tshark) — always streams the file, never loads it whole

    /** {@code tshark -r FILE} with an optional display filter: the packet list, streamed. */
    @NonNull
    public static List<String> tsharkReadArgv(@NonNull String file, @NonNull String displayFilter, int maxPackets) {
        List<String> a = baseRead(file);
        if (!displayFilter.trim().isEmpty()) {
            if (!isValidDisplayFilter(displayFilter)) throw new IllegalArgumentException("invalid display filter");
            a.add("-Y"); a.add(displayFilter.trim());
        }
        if (maxPackets > 0) { a.add("-c"); a.add(String.valueOf(Math.min(maxPackets, PACKETS_MAX))); }
        return a;
    }

    /** A protocol-hierarchy summary ({@code -q -z io,phs}): bounded output, reads the file once. */
    @NonNull
    public static List<String> tsharkSummaryArgv(@NonNull String file) {
        List<String> a = baseRead(file);
        a.add("-q");
        a.add("-z"); a.add("io,phs");
        return a;
    }

    /** Export to another format ({@code -T} fields/json/ek/pdml) to a file the caller then downloads. */
    @NonNull
    public static List<String> tsharkExportArgv(@NonNull String file, @NonNull String format, @NonNull String displayFilter) {
        if (!isValidFormat(format)) throw new IllegalArgumentException("unsupported export format");
        List<String> a = baseRead(file);
        if (!displayFilter.trim().isEmpty()) {
            if (!isValidDisplayFilter(displayFilter)) throw new IllegalArgumentException("invalid display filter");
            a.add("-Y"); a.add(displayFilter.trim());
        }
        a.add("-T"); a.add(format);
        if (format.equals("fields")) {
            for (String f : new String[]{"frame.number", "frame.time_relative", "ip.src", "ip.dst", "_ws.col.Protocol", "_ws.col.Info"}) {
                a.add("-e"); a.add(f);
            }
            a.add("-E"); a.add("header=y");
            a.add("-E"); a.add("separator=,");
        }
        return a;
    }

    public static boolean isValidFormat(@NonNull String format) {
        switch (format) {
            case "fields": case "json": case "ek": case "pdml": case "psml": case "tabs": return true;
            default: return false;
        }
    }

    @NonNull
    private static List<String> baseRead(@NonNull String file) {
        if (!isValidCapturePath(file)) throw new IllegalArgumentException("invalid capture path");
        List<String> a = new ArrayList<>();
        a.add("tshark");
        a.add("-r"); a.add(file);
        a.add("-n");   // no name resolution: no DNS, no network side effects during analysis
        return a;
    }

    /** Single-quote a value for a POSIX shell, escaping embedded single quotes. */
    @NonNull
    static String sq(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
