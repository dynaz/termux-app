package com.termux.app.terminow;

import org.junit.Test;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The catalog of the Browse tab: honest entries, search and categories. */
public class TerminowMcpCatalogTest {

    @Test
    public void everyEntryIsDocumentedAndConsistent() throws Exception {
        Set<String> ids = new HashSet<>();
        for (TerminowMcpCatalog.Entry entry : TerminowMcpCatalog.ENTRIES) {
            assertTrue(entry.id, ids.add(entry.id));
            assertTrue(entry.id, TerminowMcpProfile.isValidName(entry.suggestedName()));
            assertTrue(entry.id, TerminowMcpCatalog.CATEGORIES.contains(entry.category));
            assertTrue(entry.id, entry.docsUrl.startsWith("https://"));
            assertFalse(entry.id, entry.auth.isEmpty());
            switch (entry.kind) {
                case REMOTE:
                    // Only documented HTTPS endpoints, which the URL check of the app accepts
                    assertNotNull(entry.id, entry.url);
                    assertNull(entry.id, TerminowMcpClient.validateUrl(entry.url, false));
                    assertEquals(entry.id, "https", new URL(entry.url).getProtocol());
                    assertTrue(entry.id, entry.canAdd());
                    break;
                case REMOTE_MANUAL:
                    // Nothing is filled in that the documentation does not give exactly
                    assertNull(entry.id, entry.url);
                    assertFalse(entry.id, entry.canAdd());
                    break;
                case LOCAL:
                    assertNull(entry.id, entry.url);
                    assertFalse(entry.id, entry.command.isEmpty());
                    // Local servers can not be added as servers that TermiNow tests
                    assertFalse(entry.id, entry.canAdd());
                    assertEquals(TerminowMcpCatalog.CATEGORY_CUSTOM, entry.category);
                    break;
            }
        }
    }

    @Test
    public void searchAndCategories() {
        assertEquals(TerminowMcpCatalog.ENTRIES.size(), TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "").size());
        List<TerminowMcpCatalog.Entry> developer = TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_DEVELOPER, "");
        assertFalse(developer.isEmpty());
        for (TerminowMcpCatalog.Entry entry : developer) assertEquals(TerminowMcpCatalog.CATEGORY_DEVELOPER, entry.category);
        // By name, description, publisher, ignoring case
        assertEquals("github", TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "GITHUB").get(0).id);
        assertTrue(TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "jira").stream().anyMatch(e -> e.id.equals("atlassian")));
        assertTrue(TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "upstash").stream().anyMatch(e -> e.id.equals("context7")));
        assertTrue(TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "productivity").stream().anyMatch(e -> e.id.equals("notion")));
        // A search within a category
        assertTrue(TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_BUSINESS, "github").isEmpty());
        assertTrue(TerminowMcpCatalog.search(TerminowMcpCatalog.CATEGORY_ALL, "no such server").isEmpty());
        assertNotNull(TerminowMcpCatalog.find("stripe"));
        assertNull(TerminowMcpCatalog.find("nope"));
    }

}
