package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads exported PuTTY session registry files ({@code .reg}, from <em>regedit</em> under
 * {@code HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions}) into normalised
 * {@link TerminowImportConnection}s.
 *
 * <p>The file is treated purely as data: it is never run, never written back to a registry, and no
 * referenced file is read. Session names are PuTTY-escaped with {@code %XX}, so they are unescaped;
 * {@code PortNumber} is a {@code dword:} hex value; string values have doubled backslashes and
 * escaped quotes. Protocols map by name (ssh, telnet, rlogin, raw, serial) and only SSH is imported
 * as a usable connection — the rest are reported, never converted. A {@code PublicKeyFile} ({@code .ppk})
 * is kept as a reference only: a {@code .ppk} is not an OpenSSH key and must go through an explicit
 * conversion, never a rename.
 */
public final class TerminowPuttyImport {

    private static final String SOURCE = "PuTTY";
    private static final String SESSIONS_MARKER = "\\PuTTY\\Sessions\\";
    private static final int MAX_ENTRIES = 5000;

    private TerminowPuttyImport() {}

    public static boolean looksLikePutty(@NonNull String text) {
        return text.contains(SESSIONS_MARKER)
            || text.startsWith("Windows Registry Editor") || text.startsWith("REGEDIT4");
    }

    /** @throws IOException if the file is not a PuTTY sessions registry export. */
    @NonNull
    public static List<TerminowImportConnection> parse(@NonNull String regText) throws IOException {
        String text = regText.startsWith("\uFEFF") ? regText.substring(1) : regText;
        if (!looksLikePutty(text)) throw new IOException("Not a PuTTY sessions registry export");

        List<TerminowImportConnection> result = new ArrayList<>();
        String[] lines = text.split("\r\n|\n|\r");

        String sessionName = null;
        String hostName = "", user = "", protocol = "", keyFile = "";
        int port = 0;
        boolean inSession = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                // A new key: flush the previous session, then start (or leave) the Sessions tree.
                if (inSession) flush(result, sessionName, hostName, user, protocol, port, keyFile);
                hostName = user = protocol = keyFile = "";
                port = 0;
                int marker = line.indexOf(SESSIONS_MARKER);
                if (marker >= 0) {
                    String leaf = line.substring(marker + SESSIONS_MARKER.length());
                    if (leaf.endsWith("]")) leaf = leaf.substring(0, leaf.length() - 1);
                    sessionName = unescapeSessionName(leaf);
                    inSession = true;
                } else {
                    inSession = false;
                    sessionName = null;
                }
                if (result.size() >= MAX_ENTRIES) break;
                continue;
            }
            if (!inSession || !line.startsWith("\"")) continue;

            int nameEnd = line.indexOf("\"=", 1);
            if (nameEnd < 0) continue;
            String name = line.substring(1, nameEnd);
            String value = line.substring(nameEnd + 2);

            switch (name) {
                case "HostName": hostName = unescapeString(value); break;
                case "UserName": user = unescapeString(value); break;
                case "Protocol": protocol = unescapeString(value); break;
                case "PublicKeyFile": keyFile = unescapeString(value); break;
                case "PortNumber": port = parseDword(value); break;
                default: break; // every other PuTTY setting is ignored, never executed
            }
        }
        if (inSession) flush(result, sessionName, hostName, user, protocol, port, keyFile);
        return result;
    }

    private static void flush(@NonNull List<TerminowImportConnection> result, String sessionName,
                              @NonNull String host, @NonNull String user, @NonNull String protocol,
                              int port, @NonNull String keyFile) {
        // "Default Settings" holds PuTTY defaults, not a real host.
        if (sessionName == null || "Default Settings".equals(sessionName)) return;
        if (result.size() >= MAX_ENTRIES) return;

        TerminowImportConnection c = new TerminowImportConnection(SOURCE);
        c.name = sessionName;
        c.host = host;
        c.username = user;
        c.port = port;
        c.protocol = protocolOf(protocol);
        if (!c.protocol.supported && c.protocol != TerminowImportConnection.Protocol.UNKNOWN)
            c.note = "PuTTY " + c.protocol.label() + " session; only SSH is imported as a connection.";

        if (!keyFile.isEmpty()) {
            c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
            c.keyReference = keyFile;
            c.note = append(c.note, "PuTTY .ppk key is a reference; convert it explicitly to a Keychain key.");
        }
        result.add(c);
    }

    @NonNull
    private static TerminowImportConnection.Protocol protocolOf(@NonNull String raw) {
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "ssh": return TerminowImportConnection.Protocol.SSH;
            case "telnet": return TerminowImportConnection.Protocol.TELNET;
            case "rlogin": return TerminowImportConnection.Protocol.RLOGIN;
            case "raw": return TerminowImportConnection.Protocol.RAW;
            case "serial": return TerminowImportConnection.Protocol.SERIAL;
            default: return TerminowImportConnection.Protocol.UNKNOWN;
        }
    }

    /** PuTTY escapes unsafe characters in a session name as {@code %XX}; reverse that. */
    @NonNull
    static String unescapeSessionName(@NonNull String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '%' && i + 2 < name.length()) {
                int hi = Character.digit(name.charAt(i + 1), 16);
                int lo = Character.digit(name.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    sb.append((char) (hi * 16 + lo));
                    i += 2;
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** A {@code .reg} string value: strip the quotes, then undo doubled backslashes and escaped quotes. */
    @NonNull
    static String unescapeString(@NonNull String value) {
        String v = value.trim();
        if (v.startsWith("\"")) v = v.substring(1);
        if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char next = v.charAt(i + 1);
                if (next == '\\' || next == '"') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** {@code dword:00000016} is hex; PuTTY stores the port that way. */
    static int parseDword(@NonNull String value) {
        String v = value.trim();
        if (!v.toLowerCase(Locale.ROOT).startsWith("dword:")) return 0;
        try {
            long parsed = Long.parseLong(v.substring("dword:".length()).trim(), 16);
            return (parsed > 0 && parsed <= 65535) ? (int) parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NonNull
    private static String append(@NonNull String existing, @NonNull String add) {
        return existing.isEmpty() ? add : existing + " " + add;
    }
}
