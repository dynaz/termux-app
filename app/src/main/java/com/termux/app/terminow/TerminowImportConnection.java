package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * One connection parsed from an import source, normalised so every adapter (FileZilla, PuTTY,
 * MobaXterm, SecureCRT, CSV, OpenSSH and, later, cloud providers) produces the same shape. The
 * shared pipeline validates, de-duplicates and previews these before any of them becomes a TermiNow
 * host; parsing stays separate from persistence.
 *
 * <p>Secrets are handled with care: {@link #password} is only ever filled when the person opted in
 * to importing saved passwords, and {@link #toString} and {@link #masked} never reveal it, so an
 * entry can be logged or shown in a preview without leaking a credential.
 */
public final class TerminowImportConnection {

    /** The wire protocol of a source entry, and whether TermiNow can import it. */
    public enum Protocol {
        SSH(true, 22, "SSH"),
        SFTP(true, 22, "SFTP"),
        FTP(false, 21, "FTP"),
        FTPS(false, 21, "FTPS"),
        FTPES(false, 21, "FTPES"),
        FTP_INSECURE(false, 21, "FTP (plain)"),
        TELNET(false, 23, "Telnet"),
        RLOGIN(false, 513, "Rlogin"),
        RAW(false, 0, "Raw"),
        SERIAL(false, 0, "Serial"),
        RDP(false, 3389, "RDP"),
        UNKNOWN(false, 0, "Unknown");

        public final boolean supported;
        public final int defaultPort;
        private final String label;

        Protocol(boolean supported, int defaultPort, String label) {
            this.supported = supported;
            this.defaultPort = defaultPort;
            this.label = label;
        }

        @NonNull
        public String label() {
            return label;
        }
    }

    /** What is known about the entry's credentials, which drives the "credentials required" count. */
    public enum Credential {
        /** No credential needed or none present (e.g. an anonymous or key-agent login). */
        NONE,
        /** A readable password is available (only kept when the person opted in). */
        PASSWORD,
        /** The entry references an external key file that must be mapped to a local Keychain key. */
        KEY_REFERENCE,
        /** A credential exists but is encrypted or unavailable; the person must supply one. */
        REQUIRED
    }

    /** The import source that produced this entry, for the preview and for repeat-import matching. */
    public final String source;

    public String name = "";
    public String host = "";
    /** 0 means "use the protocol default". */
    public int port = 0;
    public String username = "";
    public Protocol protocol = Protocol.UNKNOWN;

    /** The nested folder path from the source, preserved as TermiNow groups (outermost first). */
    public final List<String> folders = new ArrayList<>();

    public Credential credential = Credential.NONE;
    /** A readable password; empty unless the person opted in to importing saved passwords. Never logged. */
    public String password = "";
    /** An external key-file path or key name from the source. A reference only, never read here. */
    public String keyReference = "";
    public String remoteDir = "";

    /** Cloud provider resource id and region, so a repeat import can recognise what it saw before. Empty for files. */
    public String resourceId = "";
    public String region = "";

    /** A human-readable note, such as why an entry is unsupported. Shown in the preview. */
    public String note = "";

    public TerminowImportConnection(@NonNull String source) {
        this.source = source;
    }

    /** The port to actually use: the given one, or the protocol default when none was set. */
    public int effectivePort() {
        return port > 0 ? port : protocol.defaultPort;
    }

    /** The folder path as a single group name, like {@code "Work/EU"}; empty when at the top level. */
    @NonNull
    public String groupPath() {
        StringBuilder sb = new StringBuilder();
        for (String folder : folders) {
            if (sb.length() > 0) sb.append('/');
            sb.append(folder);
        }
        return sb.toString();
    }

    public boolean hasPassword() {
        return credential == Credential.PASSWORD && !password.isEmpty();
    }

    /** A copy of the entry with the password cleared, safe to show or log. */
    @NonNull
    public String masked() {
        StringBuilder sb = new StringBuilder();
        sb.append(source).append(": ").append(name.isEmpty() ? host : name)
            .append(" [").append(protocol.label()).append("] ")
            .append(username.isEmpty() ? "" : username + "@").append(host).append(':').append(effectivePort());
        if (!folders.isEmpty()) sb.append(" (").append(groupPath()).append(')');
        if (credential == Credential.PASSWORD) sb.append(" pw=****");
        else if (credential == Credential.KEY_REFERENCE) sb.append(" key=").append(keyReference);
        else if (credential == Credential.REQUIRED) sb.append(" creds-required");
        return sb.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return masked();
    }
}
