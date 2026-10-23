package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;

public class TerminowMcpCatalogTest {

    private TerminowMcpCatalog.Entry byId(String id) {
        for (TerminowMcpCatalog.Entry e : TerminowMcpCatalog.ENTRIES) if (e.id.equals(id)) return e;
        return null;
    }

    @Test
    public void apifyIsInTheDataCategoryWithItsStreamableHttpEndpoint() {
        TerminowMcpCatalog.Entry apify = byId("apify");
        assertNotNull(apify);
        assertEquals("Apify", apify.name);
        assertEquals(TerminowMcpCatalog.CATEGORY_DATA, apify.category);
        assertEquals("https://mcp.apify.com", apify.url);
        assertTrue(apify.canAdd());
        assertTrue(apify.oauth);          // OAuth preferred
        assertTrue(apify.bearerToken);    // token alternative
    }

    @Test
    public void apifyRegistersAsAnHttpServerWithNoTokenInTheUrl() throws Exception {
        TerminowMcpCatalog.Entry apify = byId("apify");
        // Equivalent to: claude mcp add --transport http --scope user apify https://mcp.apify.com
        JSONObject entry = TerminowClaudeConfig.buildHttpEntry(apify.url, new HashMap<>());
        assertEquals("http", entry.optString("type"));
        assertEquals("https://mcp.apify.com", entry.optString("url"));
        // The endpoint carries no token or query secret
        assertFalse(entry.optString("url").contains("token"));
        assertFalse(entry.optString("url").contains("?"));
    }

    @Test
    public void cloudflareAndItsDocsShareOneLogo() {
        assertTrue(TerminowMcpLogos.hasLogo("cloudflare"));
        assertTrue(TerminowMcpLogos.hasLogo("cloudflare-docs"));
        assertEquals(TerminowMcpLogos.logoFor("cloudflare"), TerminowMcpLogos.logoFor("cloudflare-docs"));
    }

    @Test
    public void officialLogosArePresentAndUnknownFallsBackToGeneric() {
        assertTrue(TerminowMcpLogos.hasLogo("github"));
        assertTrue(TerminowMcpLogos.hasLogo("apify"));
        assertFalse(TerminowMcpLogos.hasLogo("linear"));
        // An entry with no bundled logo returns the generic fallback, different from a real one
        assertNotEquals(TerminowMcpLogos.logoFor("github"), TerminowMcpLogos.logoFor("linear"));
    }
}
