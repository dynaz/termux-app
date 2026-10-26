package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The registry of import sources: it dispatches a chosen {@link Source} to the matching parser and
 * returns the shared {@link TerminowImportConnection} model. FileZilla, PuTTY and SecureCRT have
 * their own parsers; CSV and OpenSSH reuse the existing {@link TerminowHostImport}, whose hosts are
 * wrapped into the model so every source flows through the same validate/preview/persist pipeline.
 */
public final class TerminowImportParsers {

    /** A file-based import source the person can pick. Cloud sources are added separately. */
    public enum Source {
        FILEZILLA("FileZilla"), PUTTY("PuTTY"), SECURECRT("SecureCRT"), CSV("CSV"), OPENSSH("OpenSSH");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    private TerminowImportParsers() {}

    /**
     * Parse {@code text} as {@code source}. {@code fileName} names the entry when the format does not
     * (SecureCRT). {@code importPasswords} gates readable passwords.
     *
     * @throws IOException if the text does not match the source format or is malformed/unsafe.
     */
    @NonNull
    public static List<TerminowImportConnection> parse(@NonNull Source source, @NonNull String text,
                                                       @NonNull String fileName, boolean importPasswords,
                                                       @NonNull TerminowImportPersist.KeyResolver keys) throws IOException {
        switch (source) {
            case FILEZILLA:
                return TerminowFileZillaImport.parse(text, importPasswords);
            case PUTTY:
                return TerminowPuttyImport.parse(text);
            case SECURECRT:
                return TerminowSecureCrtImport.parse(text, "", baseName(fileName));
            case CSV:
            case OPENSSH:
                return fromHostImport(text, importPasswords, keys);
            default:
                throw new IOException("Unknown import source");
        }
    }

    /** Reuse the existing CSV/ssh-config reader and wrap its hosts into the normalised model. */
    @NonNull
    private static List<TerminowImportConnection> fromHostImport(@NonNull String text, boolean importPasswords,
                                                                 @NonNull TerminowImportPersist.KeyResolver keys) throws IOException {
        TerminowHostImport.Result result = TerminowHostImport.read(text, keys::resolve);
        String source = result.csv ? Source.CSV.label : Source.OPENSSH.label;
        List<TerminowImportConnection> out = new ArrayList<>();
        for (TerminowStore.Host host : result.hosts) out.add(fromHost(host, source, importPasswords));
        return out;
    }

    @NonNull
    static TerminowImportConnection fromHost(@NonNull TerminowStore.Host host, @NonNull String source, boolean importPasswords) {
        TerminowImportConnection c = new TerminowImportConnection(source);
        c.name = !host.label.isEmpty() ? host.label : host.address;
        c.host = host.address;
        c.username = host.username;
        c.protocol = host.telnet ? TerminowImportConnection.Protocol.TELNET : TerminowImportConnection.Protocol.SSH;
        c.port = host.telnet ? host.telnetPort : host.port;
        if (!host.group.isEmpty()) for (String part : host.group.split("/")) if (!part.isEmpty()) c.folders.add(part);

        if (!host.keyPath.isEmpty()) {
            c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
            c.keyReference = host.keyPath;
        } else if (!host.password.isEmpty()) {
            if (importPasswords) {
                c.credential = TerminowImportConnection.Credential.PASSWORD;
                c.password = host.password;
            } else {
                c.credential = TerminowImportConnection.Credential.NONE;
                c.note = "A saved password exists but was not imported.";
            }
        }
        return c;
    }

    @NonNull
    private static String baseName(@NonNull String fileName) {
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }
}
