package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches DigitalOcean Droplets with a personal access token, following pagination until the last
 * page, the token is exhausted or the person cancels. The token needs only read scope; it is used
 * for this one call and never logged or stored by this class. No Droplet is created, changed or
 * removed — this only lists.
 */
public final class TerminowDigitalOceanClient {

    private static final String FIRST_PAGE = "https://api.digitalocean.com/v2/droplets?per_page=200";
    private static final int MAX_INSTANCES = 5000;

    private TerminowDigitalOceanClient() {}

    @NonNull
    public static List<TerminowCloudInstance> fetchDroplets(@NonNull String token, @Nullable AtomicBoolean cancel) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.trim());
        headers.put("Accept", "application/json");

        List<TerminowCloudInstance> result = new ArrayList<>();
        String url = FIRST_PAGE;
        while (!url.isEmpty()) {
            if (cancel != null && cancel.get()) break;
            TerminowCloudHttp.Response response = TerminowCloudHttp.request("GET", url, headers, null);
            if (response.code == 401) throw new IOException("DigitalOcean rejected the token. Check that it is valid and has read scope.");
            if (response.code == 429) throw new IOException("DigitalOcean rate limit reached. Try again in a moment.");
            if (!response.ok()) throw new IOException("DigitalOcean returned HTTP " + response.code + ".");

            result.addAll(TerminowDigitalOceanImport.parseDroplets(response.body));
            if (result.size() >= MAX_INSTANCES) break;
            url = TerminowDigitalOceanImport.nextPageUrl(response.body);
        }
        return result;
    }
}
