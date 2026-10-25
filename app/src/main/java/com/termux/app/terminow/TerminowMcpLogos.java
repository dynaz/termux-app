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
        LOGOS.put("sentry", R.drawable.ic_terminow_logo_sentry);
        LOGOS.put("vercel", R.drawable.ic_terminow_logo_vercel);
        LOGOS.put("context7", R.drawable.ic_terminow_logo_context7); // Context7 is an Upstash product
        LOGOS.put("figma", R.drawable.ic_terminow_logo_figma);
        LOGOS.put("linear", R.drawable.ic_terminow_logo_linear);
        LOGOS.put("notion", R.drawable.ic_terminow_logo_notion);
        LOGOS.put("asana", R.drawable.ic_terminow_logo_asana);
        LOGOS.put("atlassian", R.drawable.ic_terminow_logo_atlassian);
        LOGOS.put("stripe", R.drawable.ic_terminow_logo_stripe);
        LOGOS.put("hubspot", R.drawable.ic_terminow_logo_hubspot);
        LOGOS.put("supabase", R.drawable.ic_terminow_logo_supabase);
        LOGOS.put("neon", R.drawable.ic_terminow_logo_neon);
        LOGOS.put("huggingface", R.drawable.ic_terminow_logo_huggingface);
        LOGOS.put("git", R.drawable.ic_terminow_logo_git);
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
