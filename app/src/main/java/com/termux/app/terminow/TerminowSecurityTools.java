package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The catalogue of individual security tools TermiNow can install in a Linux environment (Kali or
 * any apt-based distro), and detection of which are already there. Each entry installs only its own
 * apt package(s) through {@link TerminowToolInstaller#installPackage} — never a {@code kali-linux-*}
 * metapackage — so the user gets exactly the one tool they chose.
 *
 * <p>Security tools are a Premium capability and are meant for authorised testing only (see the
 * information screen). This class is pure data and shell-free helpers, so it is unit-testable.</p>
 */
public final class TerminowSecurityTools {

    public enum Category { RECON, WEB, PASSWORD, WIRELESS, CAPTURE }

    /** How "Open" presents a tool that needs arguments: never a bare auto-run scan. */
    public enum Composer { NONE, HELP, NMAP_TCP, TSHARK_PCAP }

    /** Whether a tool/environment combination can be installed and meaningfully used. */
    public enum Support { SUPPORTED, LIMITED, UNSUPPORTED }

    /** Why a combination is limited or unsupported, mapped to a localized explanation by the UI. */
    public enum Reason { NONE, NO_APT, UNSUPPORTED_ARCH, GUI_DISPLAY, WIRELESS_MONITOR, RAW_SOCKET }

    /** One installable tool: a stable id, a label, the apt package(s), the binary that proves it, and its traits. */
    public static final class Tool {
        public final String id;
        public final String label;
        public final String binName;
        public final List<String> aptPackages;
        public final Category category;
        public final String description;
        /** Needs a graphical display server to run (e.g. Wireshark's GUI); CLI tools are false. */
        public final boolean gui;
        /** Official documentation page (HTTPS), shown as a link in the details sheet. */
        public final String documentationUrl;
        /** Uses raw sockets / live capture / elevated kernel capabilities for its full feature set. */
        public final boolean privileged;
        /** Some core modes cannot work in this environment (e.g. raw-only scanners, Wi-Fi monitor). */
        public final boolean limited;
        /** How "Open" presents it once installed. */
        public final Composer composer;

        Tool(@NonNull String id, @NonNull String label, @NonNull String binName, @NonNull List<String> aptPackages,
             @NonNull Category category, @NonNull String description, boolean gui,
             @NonNull String documentationUrl, boolean privileged, boolean limited, @NonNull Composer composer) {
            this.id = id;
            this.label = label;
            this.binName = binName;
            this.aptPackages = Collections.unmodifiableList(aptPackages);
            this.category = category;
            this.description = description;
            this.gui = gui;
            this.documentationUrl = documentationUrl;
            this.privileged = privileged;
            this.limited = limited;
            this.composer = composer;
        }
    }

    /**
     * What the target environment can do, read from the environment (never from saved UI state).
     * Built from {@link TerminowToolInstaller.Detection}. Local Linux environments always run under
     * PRoot, so {@code inProot} is true and {@code uid=0} is a PRoot fake-root that grants NO Android
     * kernel capability — raw sockets, live capture and Wi-Fi monitor are not unlocked by it.
     */
    public static final class Capabilities {
        public final String arch;
        public final String os;
        public final boolean hasApt;
        public final boolean isRoot;
        public final boolean inProot;

        public Capabilities(@NonNull String arch, @NonNull String os, boolean hasApt, boolean isRoot, boolean inProot) {
            this.arch = arch == null ? "" : arch;
            this.os = os == null ? "" : os;
            this.hasApt = hasApt;
            this.isRoot = isRoot;
            this.inProot = inProot;
        }

        /** uid=0 is only a real kernel privilege when NOT inside PRoot. */
        public boolean kernelPrivileged() { return !inProot && isRoot; }

        public boolean arm64() {
            String a = arch.toLowerCase(java.util.Locale.ROOT);
            return a.equals("aarch64") || a.equals("arm64");
        }

        /** arm64 and x86_64 have Kali/Debian security packages; an unknown arch is given the benefit of the doubt. */
        public boolean archSupported() {
            if (arch.isEmpty()) return true;
            switch (arch.toLowerCase(java.util.Locale.ROOT)) {
                case "aarch64": case "arm64": case "x86_64": case "amd64": return true;
                default: return false;
            }
        }
    }

    /** A support verdict for a tool in an environment, with a reason the UI explains. */
    public static final class Status {
        public final Support support;
        public final Reason reason;
        Status(@NonNull Support support, @NonNull Reason reason) { this.support = support; this.reason = reason; }
    }

    /**
     * Whether this tool can be installed and used in the given environment. Install is UNSUPPORTED only
     * when apt is absent or the CPU has no packages; GUI, Wi-Fi monitor and raw-only scanners are
     * LIMITED (they install but a core mode will not work here), everything else SUPPORTED. The details
     * sheet carries the specific limitation text; this only picks the badge and whether Install is offered.
     */
    @NonNull
    public static Status support(@NonNull Tool tool, @NonNull Capabilities caps) {
        if (!caps.hasApt) return new Status(Support.UNSUPPORTED, Reason.NO_APT);
        if (!caps.archSupported()) return new Status(Support.UNSUPPORTED, Reason.UNSUPPORTED_ARCH);
        if (tool.gui) return new Status(Support.LIMITED, Reason.GUI_DISPLAY);
        if (tool.category == Category.WIRELESS) return new Status(Support.LIMITED, Reason.WIRELESS_MONITOR);
        if (tool.limited) return new Status(Support.LIMITED, Reason.RAW_SOCKET);
        return new Status(Support.SUPPORTED, Reason.NONE);
    }

    /** apt package and executable names: lowercase, digits and a few punctuation marks only. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9+._-]*");

    private static final List<Tool> CATALOG = buildCatalog();

    private TerminowSecurityTools() {}

    @NonNull
    public static List<Tool> catalog() {
        return CATALOG;
    }

    @NonNull
    public static List<Tool> inCategory(@NonNull Category category) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : CATALOG) if (t.category == category) out.add(t);
        return out;
    }

    @androidx.annotation.Nullable
    public static Tool byId(@NonNull String id) {
        for (Tool t : CATALOG) if (t.id.equals(id)) return t;
        return null;
    }

    /** Every security tool is a Premium capability. */
    public static boolean requiresPremium(@NonNull Tool tool) {
        return true;
    }

    public static boolean isValidName(@NonNull String name) {
        return !name.isEmpty() && name.length() <= 100 && NAME.matcher(name).matches();
    }

    /** A catalog entry is well formed: a valid binary and at least one valid apt package. */
    public static boolean isValid(@NonNull Tool tool) {
        if (!isValidName(tool.binName) || tool.aptPackages.isEmpty()) return false;
        for (String p : tool.aptPackages) if (!isValidName(p)) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Detection — read from the environment, never from saved UI state

    /** A shell script that prints {@code TERMINOW_HAS_<bin>=<version>} for each tool that is present. */
    @NonNull
    static String detectScript() {
        StringBuilder bins = new StringBuilder();
        for (Tool t : CATALOG) bins.append(bins.length() == 0 ? "" : " ").append(t.binName);
        // Each bin is a catalogue literal matching NAME, so this list is safe to embed.
        return "for b in " + bins + "; do\n" +
            "  command -v \"$b\" >/dev/null 2>&1 || continue\n" +
            "  v=$(\"$b\" --version 2>/dev/null | head -n1); [ -n \"$v\" ] || v=installed\n" +
            "  printf 'TERMINOW_HAS_%s=%s\\n' \"$b\" \"$v\"\n" +
            "done\n" +
            "exit 0\n";
    }

    /** Parse {@link #detectScript} output into a map of binary name to its reported version. */
    @NonNull
    static Map<String, String> parseDetection(@NonNull String output) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String line : output.split("\n")) {
            if (!line.startsWith("TERMINOW_HAS_")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String bin = line.substring("TERMINOW_HAS_".length(), eq);
            String version = line.substring(eq + 1).trim();
            if (!bin.isEmpty()) found.put(bin, version);
        }
        return found;
    }

    /** Which tool binaries are installed in the environment, keyed by binary name → version, blocking. */
    @NonNull
    public static Map<String, String> detect(@NonNull TerminowToolInstaller.Runner runner,
                                             @NonNull String alias, @NonNull String user) {
        Process process;
        try {
            process = runner.start(alias, user, detectScript());
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
        StringBuilder out = new StringBuilder();
        Thread errors = new Thread(() -> drain(process.getErrorStream(), null));
        errors.setDaemon(true);
        errors.start();
        Thread reader = new Thread(() -> drain(process.getInputStream(), out));
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(TerminowToolInstaller.DETECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy();
                return new LinkedHashMap<>();
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            return new LinkedHashMap<>();
        }
        return parseDetection(out.toString());
    }

    private static void drain(@NonNull InputStream stream, @androidx.annotation.Nullable StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) if (out != null) out.append(line).append('\n');
        } catch (IOException ignored) {
            // the process ended
        }
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private static List<Tool> buildCatalog() {
        List<Tool> c = new ArrayList<>();
        c.add(new Tool("nmap", "Nmap", "nmap", one("nmap"), Category.RECON,
            "Network and port scanner for mapping hosts and services.", false,
            "https://nmap.org/book/man.html", true, false, Composer.NMAP_TCP));
        c.add(new Tool("masscan", "Masscan", "masscan", one("masscan"), Category.RECON,
            "Very fast Internet-scale port scanner.", false,
            "https://github.com/robertdavidgraham/masscan", true, true, Composer.HELP));
        c.add(new Tool("nikto", "Nikto", "nikto", one("nikto"), Category.WEB,
            "Web server scanner for known vulnerabilities and misconfigurations.", false,
            "https://github.com/sullo/nikto/wiki", false, false, Composer.HELP));
        c.add(new Tool("sqlmap", "sqlmap", "sqlmap", one("sqlmap"), Category.WEB,
            "Automated SQL injection detection and exploitation.", false,
            "https://github.com/sqlmapproject/sqlmap/wiki", false, false, Composer.HELP));
        c.add(new Tool("whatweb", "WhatWeb", "whatweb", one("whatweb"), Category.WEB,
            "Identifies the technologies a web site runs on.", false,
            "https://github.com/urbanadventurer/WhatWeb", false, false, Composer.HELP));
        c.add(new Tool("hydra", "Hydra", "hydra", one("hydra"), Category.PASSWORD,
            "Network logon cracker supporting many protocols.", false,
            "https://github.com/vanhauser-thc/thc-hydra", false, false, Composer.HELP));
        c.add(new Tool("john", "John the Ripper", "john", one("john"), Category.PASSWORD,
            "Offline password hash cracker.", false,
            "https://www.openwall.com/john/doc/", false, false, Composer.HELP));
        c.add(new Tool("hashcat", "hashcat", "hashcat", one("hashcat"), Category.PASSWORD,
            "Advanced password recovery / hash cracker.", false,
            "https://hashcat.net/wiki/", false, false, Composer.HELP));
        c.add(new Tool("aircrack-ng", "Aircrack-ng", "aircrack-ng", one("aircrack-ng"), Category.WIRELESS,
            "Wi-Fi security auditing suite.", false,
            "https://www.aircrack-ng.org/documentation.html", true, true, Composer.HELP));
        c.add(new Tool("tcpdump", "tcpdump", "tcpdump", one("tcpdump"), Category.CAPTURE,
            "Command-line packet capture.", false,
            "https://www.tcpdump.org/manpages/tcpdump.1.html", true, false, Composer.HELP));
        c.add(new Tool("tshark", "TShark", "tshark", one("tshark"), Category.CAPTURE,
            "Wireshark's command-line analyser — capture and read .pcap files.", false,
            "https://www.wireshark.org/docs/man-pages/tshark.html", true, false, Composer.TSHARK_PCAP));
        c.add(new Tool("wireshark", "Wireshark (GUI)", "wireshark", one("wireshark"), Category.CAPTURE,
            "The Wireshark graphical analyser. Needs a graphical display to run.", true,
            "https://www.wireshark.org/docs/", true, true, Composer.NONE));
        return Collections.unmodifiableList(c);
    }

    @NonNull
    private static List<String> one(@NonNull String pkg) {
        List<String> l = new ArrayList<>();
        l.add(pkg);
        return l;
    }

    // ---------------------------------------------------------------------------------------
    // Launch argument builders — every value is validated so it is safe to pass as an argv element,
    // never interpolated into a shell line. The composer collects fields; these turn them into args.

    /** A scan/analysis target: a hostname, IPv4 or IPv6 literal. No spaces, no shell metacharacters. */
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:\\[\\]-]{0,252}[A-Za-z0-9\\]])?");
    /** An Nmap port spec: digits, commas and dashes only (e.g. 22,80,443 or 1-1024). */
    private static final Pattern PORTS = Pattern.compile("[0-9]{1,5}(?:[,-][0-9]{1,5})*");

    public static boolean isValidTarget(@NonNull String target) {
        return !target.isEmpty() && target.length() <= 253 && TARGET.matcher(target).matches();
    }

    public static boolean isValidPorts(@NonNull String ports) {
        return !ports.isEmpty() && ports.length() <= 64 && PORTS.matcher(ports).matches();
    }

    /** A local file path for TShark: no newlines or shell metacharacters; the file flow supplies it. */
    public static boolean isValidFilePath(@NonNull String path) {
        if (path.isEmpty() || path.length() > 4096 || !path.startsWith("/")) return false;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch < 0x20 || "\"'`$\\;|&<>*?()!{}\n\t".indexOf(ch) >= 0) return false;
        }
        return true;
    }

    /**
     * Nmap TCP connect scan ({@code -sT}): works without raw sockets, so it runs under PRoot where
     * {@code -sS} (SYN) and other raw modes cannot. {@code -Pn} skips host discovery (also raw-free).
     * Returns null if the target or ports are not valid.
     */
    @androidx.annotation.Nullable
    public static List<String> nmapTcpArgs(@NonNull String target, @NonNull String ports) {
        if (!isValidTarget(target)) return null;
        List<String> args = new ArrayList<>();
        args.add("-sT");
        args.add("-Pn");
        if (!ports.trim().isEmpty()) {
            if (!isValidPorts(ports.trim())) return null;
            args.add("-p");
            args.add(ports.trim());
        }
        args.add(target);
        return args;
    }

    /** TShark reading an existing capture file: {@code tshark -r <file>}. Null if the path is unsafe. */
    @androidx.annotation.Nullable
    public static List<String> tsharkReadArgs(@NonNull String pcapPath) {
        if (!isValidFilePath(pcapPath)) return null;
        List<String> args = new ArrayList<>();
        args.add("-r");
        args.add(pcapPath);
        return args;
    }

    /** Single-quote one argv element for a POSIX shell: wrap in '…' and escape embedded quotes. */
    @NonNull
    public static String shellQuote(@NonNull String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * A shell command line that runs the tool's binary with the given, already-validated arguments,
     * each single-quoted. Used by "Open" to launch in a terminal. The binary name is a catalog literal
     * matching {@link #NAME}; arguments are quoted so names/paths/ports can never inject.
     */
    @NonNull
    public static String launchCommand(@NonNull String binName, @NonNull List<String> args) {
        StringBuilder cmd = new StringBuilder(binName);
        for (String a : args) cmd.append(' ').append(shellQuote(a));
        return cmd.toString();
    }

    @NonNull
    public static Capabilities capabilitiesOf(@NonNull TerminowToolInstaller.Detection detection, boolean inProot) {
        return new Capabilities(detection.arch, detection.os, detection.hasApt, detection.isRoot(), inProot);
    }
}
