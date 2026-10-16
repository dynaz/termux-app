package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hosts from a file of another app: an OpenSSH config, or a CSV like the one that the import of
 * Termius takes (Groups, Label, Tags, Hostname, Protocol, Port, Username, Password, and the key
 * and proxy columns some exporters add). Columns are found by their name, so their order does not
 * matter. Every host is checked like one saved in the host form; what can not be is skipped and
 * said why.
 */
public final class TerminowHostImport {

    /** The hosts that can be imported, and what was left out. */
    public static final class Result {
        public final List<TerminowStore.Host> hosts = new ArrayList<>();
        /** The groups of the hosts, to add to the groups of the vault. */
        public final Set<String> groups = new LinkedHashSet<>();
        /** One line for each host that was left out. */
        public final List<String> skipped = new ArrayList<>();
        /** Keys that the file names but that are not in ~/.ssh of TermiNow, to import separately. */
        public final Set<String> missingKeys = new LinkedHashSet<>();
        /** Whether the file was read as CSV, else as an ssh config. */
        public boolean csv;
    }

    /** Where a key that a file names is in ~/.ssh of TermiNow, like "~/.ssh/id_ed25519", {@code null} if not there. */
    public interface KeyResolver {
        @Nullable String resolve(@NonNull String fileName);
    }

    private TerminowHostImport() {}

    /** Read a file as CSV if its first line is a header with a host column, else as an ssh config. */
    @NonNull
    public static Result read(@NonNull String text, @NonNull KeyResolver keys) throws IOException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = parseCsv(text);
        if (!rows.isEmpty() && column(header(rows.get(0)), ADDRESS) >= 0) return fromCsv(rows, keys);
        Result result = new Result();
        for (TerminowStore.Host host : TerminowStore.readSshConfigHosts(new StringReader(text))) {
            host.keyPath = resolveKey(host.keyPath, keys, result);
            result.hosts.add(host);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------------------------

    static final List<String> ADDRESS = Arrays.asList("hostname", "hostname/ip", "host", "ip", "address", "hostname or ip");
    static final List<String> LABEL = Arrays.asList("label", "name", "alias", "title");
    static final List<String> GROUP = Arrays.asList("groups", "group", "folder");
    static final List<String> TAGS = Arrays.asList("tags", "tag");
    static final List<String> PROTOCOL = Arrays.asList("protocol", "type");
    static final List<String> PORT = Arrays.asList("port", "ssh port");
    static final List<String> USERNAME = Arrays.asList("username", "user", "login");
    static final List<String> PASSWORD = Arrays.asList("password");
    static final List<String> KEY = Arrays.asList("identityfile", "ssh_key", "ssh key", "key", "keyfile", "key file", "private key");
    static final List<String> PROXY_TYPE = Arrays.asList("proxytype", "proxy type");
    static final List<String> PROXY_HOST = Arrays.asList("proxyhost", "proxy host");
    static final List<String> PROXY_PORT = Arrays.asList("proxyport", "proxy port");

    @NonNull
    private static Map<String, Integer> header(@NonNull List<String> row) {
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String name = row.get(i).trim().toLowerCase(Locale.ROOT).replace('-', ' ');
            if (!header.containsKey(name)) header.put(name, i);
        }
        return header;
    }

    private static int column(@NonNull Map<String, Integer> header, @NonNull List<String> names) {
        for (String name : names) {
            Integer index = header.get(name);
            if (index != null) return index;
        }
        return -1;
    }

    @NonNull
    private static String cell(@NonNull List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index).trim() : "";
    }

    @NonNull
    static Result fromCsv(@NonNull List<List<String>> rows, @NonNull KeyResolver keys) {
        Result result = new Result();
        result.csv = true;
        Map<String, Integer> header = header(rows.get(0));
        int address = column(header, ADDRESS), label = column(header, LABEL), group = column(header, GROUP), tags = column(header, TAGS),
            protocol = column(header, PROTOCOL), port = column(header, PORT), username = column(header, USERNAME),
            password = column(header, PASSWORD), key = column(header, KEY), proxyType = column(header, PROXY_TYPE),
            proxyHost = column(header, PROXY_HOST), proxyPort = column(header, PROXY_PORT);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            boolean empty = true;
            for (String value : row) empty &= value.trim().isEmpty();
            if (empty) continue;
            String name = !cell(row, label).isEmpty() ? cell(row, label) : cell(row, address);
            String line = "Row " + (r + 1) + (name.isEmpty() ? "" : " (" + name + ")") + ": ";

            TerminowStore.Host host = new TerminowStore.Host();
            host.address = cell(row, address);
            if (host.address.isEmpty()) {
                result.skipped.add(line + "no hostname or IP");
                continue;
            }
            host.label = cell(row, label).replaceAll("\\p{Cntrl}", " ");
            if (host.label.length() > 100) host.label = host.label.substring(0, 100);
            host.username = cell(row, username);
            host.password = cell(row, password);

            String kind = cell(row, protocol).toLowerCase(Locale.ROOT);
            String portText = cell(row, port);
            int portNumber = 22;
            if (!portText.isEmpty()) {
                try {
                    portNumber = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    result.skipped.add(line + "the port " + portText + " is not a number");
                    continue;
                }
            }
            if (kind.isEmpty() || kind.equals("ssh") || kind.equals("sftp")) {
                host.port = portNumber;
            } else if (kind.equals("mosh")) {
                host.port = portNumber;
                host.mosh = true;
            } else if (kind.equals("telnet")) {
                host.telnet = true;
                host.telnetPort = portText.isEmpty() ? 23 : portNumber;
            } else {
                // Like RDP, VNC or a serial port: not something TermiNow connects to
                result.skipped.add(line + "the protocol " + cell(row, protocol) + " is not SSH, Mosh or Telnet");
                continue;
            }

            String groupName = cell(row, group).replaceAll("\\p{Cntrl}", " ").trim();
            if (groupName.length() > 64) groupName = groupName.substring(0, 64);
            host.group = groupName;

            for (String tag : cell(row, tags).split("[,;]")) {
                tag = tag.trim();
                if (TerminowStore.isValidTag(tag) && !host.tags.contains(tag) && host.tags.size() < 20) host.tags.add(tag);
            }

            String type = cell(row, proxyType).toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !cell(row, proxyHost).isEmpty()) {
                if (type.startsWith("socks")) host.proxyType = TerminowStore.Host.PROXY_SOCKS5;
                else if (type.startsWith("http")) host.proxyType = TerminowStore.Host.PROXY_HTTP;
                if (!host.proxyType.isEmpty()) {
                    host.proxyHost = cell(row, proxyHost);
                    try {
                        host.proxyPort = Integer.parseInt(cell(row, proxyPort));
                    } catch (NumberFormatException e) {
                        host.proxyPort = host.proxyType.equals(TerminowStore.Host.PROXY_SOCKS5) ? 1080 : 8080;
                    }
                }
            }

            if (!host.telnet) host.keyPath = resolveKey(cell(row, key), keys, result);

            if (!TerminowStore.isValidAddress(host.address)) {
                result.skipped.add(line + "the hostname " + host.address + " is not valid");
                continue;
            }
            if (!TerminowStore.isValidUsername(host.username)) {
                result.skipped.add(line + "the username can not be used");
                continue;
            }
            if (!TerminowStore.isRunnable(host)) {
                result.skipped.add(line + "its options can not be used together");
                continue;
            }
            if (!host.group.isEmpty()) result.groups.add(host.group);
            result.hosts.add(host);
        }
        return result;
    }

    /** The key of a host, if the file it names is in ~/.ssh of TermiNow; a missing one is reported. */
    @NonNull
    private static String resolveKey(@NonNull String value, @NonNull KeyResolver keys, @NonNull Result result) {
        String path = value.trim();
        if (path.isEmpty()) return "";
        // Like "C:\\Users\\me\\.ssh\\id_rsa", "~/.ssh/id_rsa" or "/home/me/keys/server.pem"
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isEmpty()) return "";
        String resolved = keys.resolve(name);
        if (resolved != null && TerminowStore.isValidKeyPath(resolved)) return resolved;
        result.missingKeys.add(name);
        return "";
    }

    /**
     * Rows and cells of CSV text (RFC 4180): commas, quoted cells with commas, quotes ("") and
     * line breaks in them, and CRLF or LF line ends.
     */
    @NonNull
    static List<List<String>> parseCsv(@NonNull String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || cell.length() > 0) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

}
