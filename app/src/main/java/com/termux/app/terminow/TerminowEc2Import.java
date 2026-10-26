package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an EC2 {@code DescribeInstances} XML response into provider-neutral
 * {@link TerminowCloudInstance}s. Only parsing lives here; the signed network call and pagination are
 * done separately, so this maps captured XML without AWS credentials or a network.
 *
 * <p>Per instance it reads the id, the Name tag, state, public and private IP and the region (from the
 * availability zone). Tags are kept for display. No key material is read: EC2 metadata never contains a
 * private key, and the login key is chosen later from the Keychain.
 */
public final class TerminowEc2Import {

    private static final String SOURCE = "AWS";

    private TerminowEc2Import() {}

    /** @throws IOException if the body is not a valid DescribeInstances response. */
    @NonNull
    public static List<TerminowCloudInstance> parseDescribeInstances(@NonNull String xml) throws IOException {
        Document document = TerminowXml.parse(xml);
        Element root = document.getDocumentElement();
        if (root == null) throw new IOException("Empty EC2 response");

        List<TerminowCloudInstance> result = new ArrayList<>();
        Element reservationSet = child(root, "reservationSet");
        if (reservationSet == null) return result; // a valid response with no instances
        for (Element reservation : children(reservationSet, "item")) {
            Element instancesSet = child(reservation, "instancesSet");
            if (instancesSet == null) continue;
            for (Element instance : children(instancesSet, "item")) result.add(toInstance(instance));
        }
        return result;
    }

    /** The pagination token from a top-level {@code nextToken}, or empty when there is no next page. */
    @NonNull
    public static String nextToken(@NonNull String xml) {
        try {
            Element root = TerminowXml.parse(xml).getDocumentElement();
            Element token = root == null ? null : child(root, "nextToken");
            return token == null ? "" : token.getTextContent().trim();
        } catch (IOException e) {
            return "";
        }
    }

    @NonNull
    private static TerminowCloudInstance toInstance(@NonNull Element instance) {
        TerminowCloudInstance c = new TerminowCloudInstance(SOURCE);
        c.id = text(instance, "instanceId");
        c.publicIp = text(instance, "ipAddress");
        c.privateIp = text(instance, "privateIpAddress");

        Element state = child(instance, "instanceState");
        if (state != null) c.state = text(state, "name");

        Element placement = child(instance, "placement");
        if (placement != null) c.region = regionFromAz(text(placement, "availabilityZone"));

        String name = "";
        Element tagSet = child(instance, "tagSet");
        if (tagSet != null) {
            for (Element item : children(tagSet, "item")) {
                String key = text(item, "key");
                String value = text(item, "value");
                if ("Name".equals(key)) name = value;
                else if (!key.isEmpty()) c.tags.add(key + "=" + value);
            }
        }
        c.name = !name.isEmpty() ? name : c.id;
        return c;
    }

    /** An availability zone like {@code us-east-1a} belongs to region {@code us-east-1}. */
    @NonNull
    static String regionFromAz(@NonNull String az) {
        if (az.length() >= 2) {
            char last = az.charAt(az.length() - 1);
            char prev = az.charAt(az.length() - 2);
            if (Character.isLetter(last) && Character.isDigit(prev)) return az.substring(0, az.length() - 1);
        }
        return az;
    }

    // --- DOM helpers (namespace-unaware; EC2 uses a single default namespace) ------------------

    private static Element child(@NonNull Element parent, @NonNull String name) {
        for (Element e : children(parent, name)) return e;
        return null;
    }

    @NonNull
    private static List<Element> children(@NonNull Element parent, @NonNull String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) out.add((Element) node);
        }
        return out;
    }

    @NonNull
    private static String text(@NonNull Element parent, @NonNull String name) {
        Element c = child(parent, name);
        if (c == null) return "";
        String content = c.getTextContent();
        return content == null ? "" : content.trim();
    }
}
