package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a SecureCRT session configuration ({@code Session.ini}) into a normalised
 * {@link TerminowImportConnection}. SecureCRT stores one session per {@code .ini} file as typed
 * lines: {@code S:"Key"=string} and {@code D:"Key"=hexdword}. The folder hierarchy lives in the
 * directory tree of the Config/Sessions folder, so a single exported file carries one session whose
 * folder path the caller supplies (empty when picking a lone file).
 *
 * <p>Logon commands and scripts are ignored, never run. Passwords are stored encrypted and are not
 * decoded: a session with a saved password becomes "credentials required". An {@code Identity
 * Filename} is kept as a key reference to map to a Keychain key.
 */
public final class TerminowSecureCrtImport {

    private static final String SOURCE = "SecureCRT";
    // S:"Key"=value  or  D:"Key"=0000abcd
    private static final Pattern LINE = Pattern.compile("^([SDZB]):\"([^\"]*)\"=(.*)$");

    private TerminowSecureCrtImport() {}

    public static boolean looksLikeSecureCrt(@NonNull String text) {
        return text.contains("S:\"Hostname\"=") || text.contains("S:\"Protocol Name\"=");
    }

    /**
     * Parse one SecureCRT session file. {@code folderPath} is the session's folder within the
     * SecureCRT tree (slash-separated), and {@code fallbackName} names the session when the file
     * does not (usually the file name without its extension).
     *
     * @throws IOException if the text is not a SecureCRT session file.
     */
    @NonNull
    public static List<TerminowImportConnection> parse(@NonNull String ini, @NonNull String folderPath,
                                                       @NonNull String fallbackName) throws IOException {
        if (!looksLikeSecureCrt(ini)) throw new IOException("Not a SecureCRT session file");

        String host = "", user = "", protocolName = "", identity = "";
        boolean hasSavedPassword = false;
        int port = 0;

        for (String raw : ini.split("\r\n|\n|\r")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            String type = m.group(1);
            String key = m.group(2);
            String value = m.group(3);

            if ("S".equals(type)) {
                switch (key) {
                    case "Hostname": host = value; break;
                    case "Username": user = value; break;
                    case "Protocol Name": protocolName = value; break;
                    case "Identity Filename V2":
                    case "Identity Filename": if (identity.isEmpty()) identity = value; break;
                    case "Password":
                    case "Password V2": if (!value.isEmpty()) hasSavedPassword = true; break;
                    default: break;
                }
            } else if ("D".equals(type) && key.contains("Port")) {
                int parsed = parseHexDword(value);
                if (parsed > 0) port = parsed;
            }
        }

        List<TerminowImportConnection> result = new ArrayList<>();
        TerminowImportConnection c = new TerminowImportConnection(SOURCE);
        if (!folderPath.isEmpty()) for (String part : folderPath.split("/")) if (!part.isEmpty()) c.folders.add(part);
        c.host = host;
        c.username = user;
        c.port = port;
        c.name = !fallbackName.isEmpty() ? fallbackName : host;
        c.protocol = protocolOf(protocolName);
        if (!c.protocol.supported && c.protocol != TerminowImportConnection.Protocol.UNKNOWN)
            c.note = "SecureCRT " + c.protocol.label() + " session; only SSH is imported as a connection.";

        if (!identity.isEmpty()) {
            c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
            c.keyReference = identity;
            c.note = append(c.note, "Identity file is a reference; map it to a Keychain key.");
        } else if (hasSavedPassword) {
            c.credential = TerminowImportConnection.Credential.REQUIRED;
            c.note = append(c.note, "SecureCRT stores the password encrypted; supply a credential.");
        }
        result.add(c);
        return result;
    }

    @NonNull
    private static TerminowImportConnection.Protocol protocolOf(@NonNull String raw) {
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "ssh2": case "ssh1": return TerminowImportConnection.Protocol.SSH;
            case "telnet": return TerminowImportConnection.Protocol.TELNET;
            case "rlogin": return TerminowImportConnection.Protocol.RLOGIN;
            case "serial": return TerminowImportConnection.Protocol.SERIAL;
            default: return TerminowImportConnection.Protocol.UNKNOWN;
        }
    }

    /** SecureCRT stores ports as an 8-hex-digit DWORD value. */
    static int parseHexDword(@NonNull String value) {
        try {
            long parsed = Long.parseLong(value.trim(), 16);
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
