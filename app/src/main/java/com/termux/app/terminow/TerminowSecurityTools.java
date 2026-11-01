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

        Tool(@NonNull String id, @NonNull String label, @NonNull String binName, @NonNull List<String> aptPackages,
             @NonNull Category category, @NonNull String description, boolean gui) {
            this.id = id;
            this.label = label;
            this.binName = binName;
            this.aptPackages = Collections.unmodifiableList(aptPackages);
            this.category = category;
            this.description = description;
            this.gui = gui;
        }
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
            "Network and port scanner for mapping hosts and services.", false));
        c.add(new Tool("masscan", "Masscan", "masscan", one("masscan"), Category.RECON,
            "Very fast Internet-scale port scanner.", false));
        c.add(new Tool("nikto", "Nikto", "nikto", one("nikto"), Category.WEB,
            "Web server scanner for known vulnerabilities and misconfigurations.", false));
        c.add(new Tool("sqlmap", "sqlmap", "sqlmap", one("sqlmap"), Category.WEB,
            "Automated SQL injection detection and exploitation.", false));
        c.add(new Tool("whatweb", "WhatWeb", "whatweb", one("whatweb"), Category.WEB,
            "Identifies the technologies a web site runs on.", false));
        c.add(new Tool("hydra", "Hydra", "hydra", one("hydra"), Category.PASSWORD,
            "Network logon cracker supporting many protocols.", false));
        c.add(new Tool("john", "John the Ripper", "john", one("john"), Category.PASSWORD,
            "Offline password hash cracker.", false));
        c.add(new Tool("hashcat", "hashcat", "hashcat", one("hashcat"), Category.PASSWORD,
            "Advanced password recovery / hash cracker.", false));
        c.add(new Tool("aircrack-ng", "Aircrack-ng", "aircrack-ng", one("aircrack-ng"), Category.WIRELESS,
            "Wi-Fi security auditing suite.", false));
        c.add(new Tool("tcpdump", "tcpdump", "tcpdump", one("tcpdump"), Category.CAPTURE,
            "Command-line packet capture.", false));
        c.add(new Tool("tshark", "TShark", "tshark", one("tshark"), Category.CAPTURE,
            "Wireshark's command-line analyser — capture and read .pcap files.", false));
        c.add(new Tool("wireshark", "Wireshark (GUI)", "wireshark", one("wireshark"), Category.CAPTURE,
            "The Wireshark graphical analyser. Needs a graphical display to run.", true));
        return Collections.unmodifiableList(c);
    }

    @NonNull
    private static List<String> one(@NonNull String pkg) {
        List<String> l = new ArrayList<>();
        l.add(pkg);
        return l;
    }
}
