package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.google.common.io.BaseEncoding;

import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Reads a FileZilla Site Manager export (its {@code FileZilla3/Servers} XML, as written by
 * <em>File → Export</em> or a {@code sitemanager.xml}) into normalised {@link TerminowImportConnection}s.
 *
 * <p>Parsing is local and hardened: the DOCTYPE declaration is disallowed and external entities and
 * DTD loading are turned off, so an XXE or entity-expansion ("billion laughs") file is rejected
 * rather than fetched or expanded. Numeric fields follow FileZilla's documented enums, verified
 * against real exports, and are never guessed:
 * <ul>
 *   <li>{@code Protocol}: 0 FTP, 1 SFTP, 2 HTTP, 3 HTTPS, 4 FTPS (implicit TLS), 5 FTPES (explicit
 *       TLS), 6 insecure FTP. Only SFTP is supported; every FTP/FTPS/HTTP variant is kept with its
 *       own protocol and reported unsupported — never converted to SSH.</li>
 *   <li>{@code Logontype}: 0 anonymous, 1 normal, 2 ask, 3 interactive, 4 account, 5 key file.</li>
 *   <li>{@code Pass encoding}: {@code base64} is encoding (decoded only on opt-in), {@code crypt} is
 *       master-password encryption and is left as "credentials required".</li>
 * </ul>
 * Nested {@code Folder} elements become the group path. Parsing is kept separate from persistence.
 */
public final class TerminowFileZillaImport {

    private static final String SOURCE = "FileZilla";
    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_DEPTH = 50;

    private TerminowFileZillaImport() {}

    /** Whether the text looks like a FileZilla export, for source auto-detection. */
    public static boolean looksLikeFileZilla(@NonNull String text) {
        return text.contains("<FileZilla3") && text.contains("<Servers");
    }

    /**
     * Parse the export. {@code importPasswords} gates readable passwords: when false (the default),
     * a stored base64/plaintext password is not decoded and the entry is left to prompt at connect.
     *
     * @throws IOException if the XML is malformed, uses an unsafe construct, or is not a FileZilla export.
     */
    @NonNull
    public static List<TerminowImportConnection> parse(@NonNull String xml, boolean importPasswords) throws IOException {
        Document document = parseSecurely(xml);
        Element root = document.getDocumentElement();
        if (root == null || !"FileZilla3".equals(root.getNodeName()))
            throw new IOException("Not a FileZilla Site Manager export");

        Element servers = firstChild(root, "Servers");
        if (servers == null)
            throw new IOException("FileZilla export has no Servers element");

        List<TerminowImportConnection> result = new ArrayList<>();
        walk(servers, new ArrayList<>(), result, importPasswords, 0);
        return result;
    }

    /** Recurse through Server and Folder elements, carrying the folder path down. */
    private static void walk(@NonNull Element parent, @NonNull List<String> path,
                             @NonNull List<TerminowImportConnection> out, boolean importPasswords, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("FileZilla folders nested too deep");
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            if ("Server".equals(element.getNodeName())) {
                if (out.size() >= MAX_ENTRIES) throw new IOException("FileZilla export has too many sites");
                out.add(toConnection(element, path, importPasswords));
            } else if ("Folder".equals(element.getNodeName())) {
                List<String> childPath = new ArrayList<>(path);
                String name = directText(element);
                if (!name.isEmpty()) childPath.add(name);
                walk(element, childPath, out, importPasswords, depth + 1);
            }
        }
    }

    @NonNull
    private static TerminowImportConnection toConnection(@NonNull Element server, @NonNull List<String> path,
                                                         boolean importPasswords) {
        TerminowImportConnection c = new TerminowImportConnection(SOURCE);
        c.folders.addAll(path);
        c.host = text(server, "Host");
        c.username = text(server, "User");
        c.remoteDir = remoteDir(text(server, "RemoteDir"));
        c.port = parseInt(text(server, "Port"));

        String name = text(server, "Name");
        c.name = !name.isEmpty() ? name : c.host;

        c.protocol = protocolOf(text(server, "Protocol"));
        if (!c.protocol.supported)
            c.note = "Unsupported protocol (" + c.protocol.label() + "); FileZilla sites of this type are skipped.";

        applyCredentials(server, c, importPasswords);
        return c;
    }

    /** Map FileZilla's numeric Protocol to our enum; keeps FTP/FTPS/HTTP distinct and unsupported. */
    @NonNull
    private static TerminowImportConnection.Protocol protocolOf(@NonNull String raw) {
        switch (raw.trim()) {
            case "": case "0": return TerminowImportConnection.Protocol.FTP;
            case "1": return TerminowImportConnection.Protocol.SFTP;
            case "2": case "3": return TerminowImportConnection.Protocol.UNKNOWN; // HTTP/HTTPS: not a connection TermiNow opens
            case "4": return TerminowImportConnection.Protocol.FTPS;
            case "5": return TerminowImportConnection.Protocol.FTPES;
            case "6": return TerminowImportConnection.Protocol.FTP_INSECURE;
            default: return TerminowImportConnection.Protocol.UNKNOWN;
        }
    }

    /** Decide the credential state from Logontype, Keyfile and Pass, decoding only when opted in. */
    private static void applyCredentials(@NonNull Element server, @NonNull TerminowImportConnection c, boolean importPasswords) {
        int logontype = parseInt(text(server, "Logontype"));
        String keyfile = text(server, "Keyfile");

        if (logontype == 5 || !keyfile.isEmpty()) {
            c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
            c.keyReference = keyfile;
            if (keyfile.isEmpty()) c.note = joinNote(c.note, "Key login with no key file; choose a Keychain key.");
            else c.note = joinNote(c.note, "Key file is a reference from the source; map it to a Keychain key.");
            return;
        }

        Element pass = firstChild(server, "Pass");
        if (pass == null) {
            c.credential = TerminowImportConnection.Credential.NONE; // anonymous / ask / interactive
            return;
        }

        String encoding = pass.getAttribute("encoding");
        if ("crypt".equalsIgnoreCase(encoding)) {
            c.credential = TerminowImportConnection.Credential.REQUIRED;
            c.note = joinNote(c.note, "Password is encrypted with a FileZilla master password; supply a credential.");
            return;
        }

        if (!importPasswords) {
            c.credential = TerminowImportConnection.Credential.NONE;
            c.note = joinNote(c.note, "A saved password exists but was not imported.");
            return;
        }

        String decoded = decodePassword(textContent(pass), encoding);
        if (decoded == null) {
            c.credential = TerminowImportConnection.Credential.REQUIRED;
            c.note = joinNote(c.note, "Saved password could not be decoded; supply a credential.");
        } else {
            c.credential = TerminowImportConnection.Credential.PASSWORD;
            c.password = decoded;
        }
    }

    /** base64 is the usual FileZilla encoding; a missing encoding means the value is already plain text. */
    private static String decodePassword(@NonNull String raw, @NonNull String encoding) {
        if (!"base64".equalsIgnoreCase(encoding)) return raw;
        try {
            // Guava's decoder works on every API level, unlike java.util.Base64 (API 26+).
            return new String(BaseEncoding.base64().decode(raw.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Secure XML ---------------------------------------------------------------------------

    @NonNull
    private static Document parseSecurely(@NonNull String xml) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Reject any DOCTYPE outright: this blocks XXE and entity-expansion attacks at the door.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // No EntityResolver that fetches anything: external references never reach the network.
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("The file is not valid XML: " + e.getMessage(), e);
        }
    }

    // --- DOM helpers --------------------------------------------------------------------------

    private static Element firstChild(@NonNull Element parent, @NonNull String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) return (Element) node;
        }
        return null;
    }

    @NonNull
    private static String text(@NonNull Element parent, @NonNull String name) {
        Element child = firstChild(parent, name);
        return child == null ? "" : textContent(child);
    }

    @NonNull
    private static String textContent(@NonNull Element element) {
        String content = element.getTextContent();
        return content == null ? "" : content.trim();
    }

    /** The direct text of an element, ignoring the text of nested elements (a Folder's own name). */
    @NonNull
    private static String directText(@NonNull Element element) {
        StringBuilder sb = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) sb.append(node.getNodeValue());
        }
        return sb.toString().trim();
    }

    @NonNull
    private static String remoteDir(@NonNull String raw) {
        // FileZilla stores a structured path like "1 0 5 /home 3 www"; keep a readable last segment.
        if (raw.isEmpty() || !raw.contains(" ")) return raw;
        int lastSpace = raw.lastIndexOf(' ');
        return raw.substring(lastSpace + 1);
    }

    private static int parseInt(@NonNull String raw) {
        try {
            return raw.trim().isEmpty() ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NonNull
    private static String joinNote(@NonNull String existing, @NonNull String add) {
        return existing.isEmpty() ? add : existing + " " + add;
    }
}
