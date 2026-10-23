package com.termux.app.terminow;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.termux.R;

import java.util.HashMap;
import java.util.Map;

/**
 * The offline logo for an MCP catalog entry, bundled so it renders without the network and the
 * same in light and dark. Entries of the same product share one logo (Cloudflare and Cloudflare
 * Docs). An entry with no bundled official logo falls back to its coloured letter tile, drawn by
 * the page; {@link #hasLogo} tells the two apart. The logos are drawn on a neutral tile so dark
 * marks stay visible on a dark background.
 */
public final class TerminowMcpLogos {

    private static final Map<String, Integer> LOGOS = new HashMap<>();

    static {
        LOGOS.put("github", R.drawable.ic_terminow_logo_github);
        // Cloudflare and Cloudflare Docs are one product, one logo
        LOGOS.put("cloudflare", R.drawable.ic_terminow_logo_cloudflare);
        LOGOS.put("cloudflare-docs", R.drawable.ic_terminow_logo_cloudflare);
        LOGOS.put("apify", R.drawable.ic_terminow_logo_apify);
    }

    private TerminowMcpLogos() {}

    /** Whether a bundled official logo exists for the entry; otherwise the letter tile is used. */
    public static boolean hasLogo(@NonNull String id) {
        return LOGOS.containsKey(id);
    }

    /** The bundled official logo of the entry, or the generic fallback when there is none. */
    @DrawableRes
    public static int logoFor(@NonNull String id) {
        Integer res = LOGOS.get(id);
        return res != null ? res : R.drawable.ic_terminow_logo_generic;
    }
}
