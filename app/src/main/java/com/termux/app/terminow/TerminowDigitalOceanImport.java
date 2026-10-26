package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a DigitalOcean {@code /v2/droplets} response into provider-neutral {@link TerminowCloudInstance}s.
 * Only parsing lives here; the network fetch (token, pagination, timeouts) is done separately, so the
 * mapping can be tested against captured JSON without a token or a network.
 *
 * <p>DigitalOcean returns, per droplet, an id, name, status, region slug, tags and a {@code networks.v4}
 * list of public and private addresses. No SSH-key metadata is read as key material: the login key is
 * chosen later from the Keychain.
 */
public final class TerminowDigitalOceanImport {

    private static final String SOURCE = "DigitalOcean";

    private TerminowDigitalOceanImport() {}

    /** @throws IOException if the body is not the expected DigitalOcean droplets JSON. */
    @NonNull
    public static List<TerminowCloudInstance> parseDroplets(@NonNull String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray droplets = root.optJSONArray("droplets");
            if (droplets == null) throw new IOException("Response has no droplets");
            List<TerminowCloudInstance> result = new ArrayList<>();
            for (int i = 0; i < droplets.length(); i++) {
                JSONObject d = droplets.optJSONObject(i);
                if (d == null) continue;
                result.add(toInstance(d));
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("The DigitalOcean response is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** The next page URL from {@code links.pages.next}, or empty when this is the last page. */
    @NonNull
    public static String nextPageUrl(@NonNull String json) {
        try {
            JSONObject pages = new JSONObject(json).optJSONObject("links");
            if (pages == null) return "";
            pages = pages.optJSONObject("pages");
            return pages == null ? "" : pages.optString("next", "");
        } catch (JSONException e) {
            return "";
        }
    }

    @NonNull
    private static TerminowCloudInstance toInstance(@NonNull JSONObject d) {
        TerminowCloudInstance c = new TerminowCloudInstance(SOURCE);
        c.id = String.valueOf(d.opt("id"));
        c.name = d.optString("name");
        c.state = d.optString("status");

        JSONObject region = d.optJSONObject("region");
        if (region != null) c.region = region.optString("slug");

        JSONObject networks = d.optJSONObject("networks");
        if (networks != null) {
            JSONArray v4 = networks.optJSONArray("v4");
            if (v4 != null) {
                for (int i = 0; i < v4.length(); i++) {
                    JSONObject net = v4.optJSONObject(i);
                    if (net == null) continue;
                    String ip = net.optString("ip_address");
                    if ("public".equals(net.optString("type"))) c.publicIp = ip;
                    else if ("private".equals(net.optString("type"))) c.privateIp = ip;
                }
            }
        }

        JSONArray tags = d.optJSONArray("tags");
        if (tags != null)
            for (int i = 0; i < tags.length(); i++) {
                String tag = tags.optString(i, "").trim();
                if (!tag.isEmpty()) c.tags.add(tag);
            }
        return c;
    }
}
