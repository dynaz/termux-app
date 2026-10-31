package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regression tests for host action targeting on the grouped Hosts page.
 *
 * <p>The bug these guard: once connection-type section headers (and group chips, and Local
 * Terminal) are interleaved into the list, an Edit/Duplicate/Delete that resolved its target by
 * adapter position would act on the wrong host. The fix is to resolve by the host's stable
 * {@link TerminowStore.Host#uuid}. These tests drive the pure {@link TerminowHostRows} model that
 * produces the exact row order the RecyclerView shows, then assert that every host row still
 * resolves to the right host by uuid after headers, filtering, searching, group navigation and
 * section collapse have moved display positions around.</p>
 */
public class TerminowHostRowsTest {

    private static final List<String> NO_GROUPS = Collections.emptyList();
    private static final Set<String> NONE = Collections.emptySet();

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
    }

    @After
    public void tearDown() {
        TerminowStore.sTestsWithoutKeystore = false;
    }

    private static TerminowStore.Host host(String uuid, String label, String protocol) {
        TerminowStore.Host h = new TerminowStore.Host();
        h.uuid = uuid;
        h.label = label;
        h.address = label.toLowerCase() + ".example.com";
        h.protocol = protocol;
        return h;
    }

    private static List<TerminowStore.Host> hostsOf(TerminowHostRows.Result r) {
        List<TerminowStore.Host> hosts = new ArrayList<>();
        for (TerminowHostRows.Row row : r.rows)
            if (row.kind == TerminowHostRows.Kind.HOST) hosts.add(row.host);
        return hosts;
    }

    /** The core invariant: each displayed host row resolves, by its uuid, back to the exact same host. */
    private static void assertEveryHostResolvesByUuid(TerminowHostRows.Result r, List<TerminowStore.Host> all) {
        for (TerminowHostRows.Row row : r.rows) {
            if (row.kind != TerminowHostRows.Kind.HOST) continue;
            TerminowStore.Host resolved = TerminowStore.findHostByUuid(all, row.host.uuid);
            assertSame("uuid " + row.host.uuid + " must resolve to the same host object", row.host, resolved);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Grouping + ordering

    @Test
    public void sectionsAppearInProtocolOrderWithHeaders() {
        List<TerminowStore.Host> all = Arrays.asList(
            host("u-rdp", "Win", TerminowStore.Host.PROTOCOL_RDP),
            host("u-ssh", "Linux", TerminowStore.Host.PROTOCOL_SSH),
            host("u-winrm", "PS", TerminowStore.Host.PROTOCOL_WINRM));

        TerminowHostRows.Result r = TerminowHostRows.build(all, NO_GROUPS, null, "", false, NONE, "");

        // Local + SSH header + ssh host + RDP header + rdp host + WinRM header + winrm host
        List<TerminowHostRows.Kind> kinds = new ArrayList<>();
        for (TerminowHostRows.Row row : r.rows) kinds.add(row.kind);
        assertEquals(Arrays.asList(
            TerminowHostRows.Kind.LOCAL,
            TerminowHostRows.Kind.SECTION, TerminowHostRows.Kind.HOST,   // SSH
            TerminowHostRows.Kind.SECTION, TerminowHostRows.Kind.HOST,   // RDP
            TerminowHostRows.Kind.SECTION, TerminowHostRows.Kind.HOST),  // WinRM
            kinds);
        // Section order is ssh, rdp, winrm (vnc/other hidden when empty).
        List<String> sections = new ArrayList<>();
        for (TerminowHostRows.Row row : r.rows)
            if (row.kind == TerminowHostRows.Kind.SECTION) sections.add(row.protocol);
        assertEquals(Arrays.asList("ssh", "rdp", "winrm"), sections);
        assertEveryHostResolvesByUuid(r, all);
    }

    @Test
    public void unknownProtocolFallsInOtherNeverSsh() {
        TerminowStore.Host weird = host("u-x", "Mystery", "sftp-only");
        List<TerminowStore.Host> all = Collections.singletonList(weird);
        assertEquals("other", TerminowHostRows.protocolKey(weird));
        TerminowHostRows.Result r = TerminowHostRows.build(all, NO_GROUPS, null, "", false, NONE, "");
        List<String> sections = new ArrayList<>();
        for (TerminowHostRows.Row row : r.rows)
            if (row.kind == TerminowHostRows.Kind.SECTION) sections.add(row.protocol);
        assertEquals(Collections.singletonList("other"), sections);
        assertEveryHostResolvesByUuid(r, all);
    }

    // ---------------------------------------------------------------------------------------------
    // Collapse shifts positions but never targeting

    @Test
    public void collapsingSshRemovesItsHostRowsButKeepsTheHeaderAndTargeting() {
        List<TerminowStore.Host> all = Arrays.asList(
            host("a", "A", TerminowStore.Host.PROTOCOL_SSH),
            host("b", "B", TerminowStore.Host.PROTOCOL_SSH),
            host("c", "C", TerminowStore.Host.PROTOCOL_RDP));

        Set<String> collapsed = new HashSet<>(Collections.singletonList("ssh"));
        TerminowHostRows.Result r = TerminowHostRows.build(all, NO_GROUPS, null, "", false, collapsed, "");

        // SSH section is present (count 2) but contributes no host rows; RDP host still shows.
        List<TerminowStore.Host> shown = hostsOf(r);
        assertEquals(1, shown.size());
        assertSame(all.get(2), shown.get(0));
        boolean sshHeaderPresent = false;
        for (TerminowHostRows.Row row : r.rows)
            if (row.kind == TerminowHostRows.Kind.SECTION && row.protocol.equals("ssh")) {
                sshHeaderPresent = true;
                assertEquals(2, row.count);
                assertTrue(row.collapsed);
            }
        assertTrue(sshHeaderPresent);
        assertEveryHostResolvesByUuid(r, all);
    }

    // ---------------------------------------------------------------------------------------------
    // Protocol filter

    @Test
    public void protocolFilterShowsOnlyThatSectionButTargetsCorrectly() {
        List<TerminowStore.Host> all = Arrays.asList(
            host("a", "A", TerminowStore.Host.PROTOCOL_SSH),
            host("b", "B", TerminowStore.Host.PROTOCOL_RDP),
            host("c", "C", TerminowStore.Host.PROTOCOL_WINRM));

        TerminowHostRows.Result r = TerminowHostRows.build(all, NO_GROUPS, null, "", false, NONE, "rdp");
        assertEquals("rdp", r.effectiveProtocolFilter);
        List<TerminowStore.Host> shown = hostsOf(r);
        assertEquals(1, shown.size());
        assertSame(all.get(1), shown.get(0));
        assertSame(all.get(1), TerminowStore.findHostByUuid(all, shown.get(0).uuid));
    }

    @Test
    public void filterForAnEmptySectionFallsBackToAll() {
        List<TerminowStore.Host> all = Collections.singletonList(
            host("a", "A", TerminowStore.Host.PROTOCOL_SSH));
        // "winrm" has no hosts: the model must not blank the list, it resets to All.
        TerminowHostRows.Result r = TerminowHostRows.build(all, NO_GROUPS, null, "", false, NONE, "winrm");
        assertEquals("", r.effectiveProtocolFilter);
        assertEquals(1, hostsOf(r).size());
    }

    // ---------------------------------------------------------------------------------------------
    // Search spans groups, expands collapse

    @Test
    public void searchIgnoresCollapseAndGroupAndStillTargetsByUuid() {
        TerminowStore.Host a = host("a", "alpha", TerminowStore.Host.PROTOCOL_SSH);
        a.group = "Work";
        TerminowStore.Host b = host("b", "beta", TerminowStore.Host.PROTOCOL_SSH);
        b.group = "Home";
        TerminowStore.Host c = host("c", "alpine", TerminowStore.Host.PROTOCOL_RDP);
        List<TerminowStore.Host> all = Arrays.asList(a, b, c);

        // Collapsed ssh is ignored during search; the query "alp" matches alpha + alpine across groups.
        Set<String> collapsed = new HashSet<>(Collections.singletonList("ssh"));
        TerminowHostRows.Result r = TerminowHostRows.build(all, Arrays.asList("Work", "Home"), "Work", "alp", false, collapsed, "");

        List<TerminowStore.Host> shown = hostsOf(r);
        assertEquals(2, shown.size());
        assertTrue(shown.contains(a));
        assertTrue(shown.contains(c));
        assertTrue("beta must not match", !shown.contains(b));
        assertEveryHostResolvesByUuid(r, all);
    }

    @Test
    public void searchShowsLocalTerminalOnlyWhenItMatches() {
        List<TerminowStore.Host> all = Collections.singletonList(
            host("a", "server", TerminowStore.Host.PROTOCOL_SSH));
        TerminowHostRows.Result withLocal = TerminowHostRows.build(all, NO_GROUPS, null, "serv", true, NONE, "");
        assertEquals(TerminowHostRows.Kind.LOCAL, withLocal.rows.get(0).kind);
        TerminowHostRows.Result withoutLocal = TerminowHostRows.build(all, NO_GROUPS, null, "serv", false, NONE, "");
        assertNotEquals(TerminowHostRows.Kind.LOCAL, withoutLocal.rows.get(0).kind);
    }

    // ---------------------------------------------------------------------------------------------
    // Group navigation

    @Test
    public void groupNavigationRestrictsToThatGroupAndKeepsTargeting() {
        TerminowStore.Host a = host("a", "A", TerminowStore.Host.PROTOCOL_SSH);
        a.group = "Work";
        TerminowStore.Host b = host("b", "B", TerminowStore.Host.PROTOCOL_SSH);
        b.group = "Home";
        TerminowStore.Host c = host("c", "C", TerminowStore.Host.PROTOCOL_SSH);   // no group
        List<TerminowStore.Host> all = Arrays.asList(a, b, c);
        List<String> groups = Arrays.asList("Work", "Home");

        TerminowHostRows.Result top = TerminowHostRows.build(all, groups, null, "", false, NONE, "");
        // Top level: Local + two group chips + SSH section + ungrouped host C only.
        assertEquals(Collections.singletonList(c), hostsOf(top));

        TerminowHostRows.Result work = TerminowHostRows.build(all, groups, "Work", "", false, NONE, "");
        assertEquals(Collections.singletonList(a), hostsOf(work));
        assertSame(a, TerminowStore.findHostByUuid(all, hostsOf(work).get(0).uuid));
    }

    @Test
    public void danglingGroupHostsShowAtTopLevel() {
        TerminowStore.Host a = host("a", "A", TerminowStore.Host.PROTOCOL_SSH);
        a.group = "DeletedGroup";   // group no longer exists
        List<TerminowStore.Host> all = Collections.singletonList(a);
        TerminowHostRows.Result top = TerminowHostRows.build(all, NO_GROUPS, null, "", false, NONE, "");
        assertEquals(Collections.singletonList(a), hostsOf(top));
    }

    // ---------------------------------------------------------------------------------------------
    // uuid resolution directly (Edit / Duplicate / Delete targeting)

    @Test
    public void findHostByUuidPicksTheRightHostAmongManyWithSharedFields() {
        // Three hosts identical but for uuid: position-based targeting would confuse them.
        TerminowStore.Host a = host("id-1", "dup", TerminowStore.Host.PROTOCOL_SSH);
        TerminowStore.Host b = host("id-2", "dup", TerminowStore.Host.PROTOCOL_SSH);
        TerminowStore.Host c = host("id-3", "dup", TerminowStore.Host.PROTOCOL_SSH);
        List<TerminowStore.Host> all = Arrays.asList(a, b, c);
        assertSame(a, TerminowStore.findHostByUuid(all, "id-1"));
        assertSame(b, TerminowStore.findHostByUuid(all, "id-2"));
        assertSame(c, TerminowStore.findHostByUuid(all, "id-3"));
    }

    @Test
    public void removedHostResolvesToNullSoTheMenuCanReportItInsteadOfRetargeting() {
        TerminowStore.Host a = host("id-1", "A", TerminowStore.Host.PROTOCOL_SSH);
        TerminowStore.Host b = host("id-2", "B", TerminowStore.Host.PROTOCOL_SSH);
        List<TerminowStore.Host> all = new ArrayList<>(Arrays.asList(a, b));
        // The action menu for A is open; A is deleted elsewhere.
        all.remove(a);
        assertNull("a removed host must not resolve to a surviving one", TerminowStore.findHostByUuid(all, "id-1"));
        assertSame(b, TerminowStore.findHostByUuid(all, "id-2"));
    }

    @Test
    public void emptyUuidNeverResolvesEvenWhenAHostHasNoId() {
        TerminowStore.Host legacy = host("", "Legacy", TerminowStore.Host.PROTOCOL_SSH);
        List<TerminowStore.Host> all = Collections.singletonList(legacy);
        // An empty id must never match: it would otherwise retarget the first id-less host.
        assertNull(TerminowStore.findHostByUuid(all, ""));
    }

    @Test
    public void duplicateGetsADistinctUuidAndResolvesIndependently() {
        TerminowStore.Host original = host("orig", "web", TerminowStore.Host.PROTOCOL_SSH);
        original.port = 2288;
        original.keyPath = "/data/keys/id_ed25519";

        // Mirror duplicateHost(): a copy that shares the fields but gets a fresh stable id.
        TerminowStore.Host copy = host(TerminowStore.newHostId(), "web copy", TerminowStore.Host.PROTOCOL_SSH);
        copy.port = original.port;
        copy.keyPath = original.keyPath;

        assertNotEquals("duplicate must have a new uuid", original.uuid, copy.uuid);
        assertTrue(copy.uuid.length() > 0);

        List<TerminowStore.Host> all = Arrays.asList(original, copy);
        assertSame(original, TerminowStore.findHostByUuid(all, original.uuid));
        assertSame(copy, TerminowStore.findHostByUuid(all, copy.uuid));
        // The copy keeps the original's connection fields.
        assertEquals(2288, copy.port);
        assertEquals("/data/keys/id_ed25519", copy.keyPath);
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence: an edited host keeps its id and connection fields after reopening (fresh store).

    @Test
    public void editedHostKeepsUuidPortAndKeyAfterReopen() {
        MemoryPreferences preferences = new MemoryPreferences();
        TerminowStore store = new TerminowStore(preferences);

        TerminowStore.Host drivo = host("drivo-id", "81 Drivo", TerminowStore.Host.PROTOCOL_SSH);
        drivo.address = "81.drivo.example";
        drivo.port = 2288;
        drivo.keyPath = "/data/data/com.terminow/files/keys/drivo_ed25519";
        store.setHosts(Collections.singletonList(drivo));

        // Edit something unrelated (rename), the way the editor does: a new object that copies the
        // stable identity, saved in the same slot.
        List<TerminowStore.Host> live = store.getHosts();
        TerminowStore.Host existing = TerminowStore.findHostByUuid(live, "drivo-id");
        assertNotNull(existing);
        TerminowStore.Host edited = host("", "81 Drivo (prod)", TerminowStore.Host.PROTOCOL_SSH);
        TerminowStore.copyIdentity(edited, existing);     // carries the uuid
        edited.address = existing.address;
        edited.port = existing.port;                       // unchanged by the user
        edited.keyPath = existing.keyPath;                 // unchanged by the user
        int index = live.indexOf(existing);
        live.set(index, edited);
        store.setHosts(live);

        // Reopen: a brand-new store reads from the same preferences.
        List<TerminowStore.Host> reopened = new TerminowStore(preferences).getHosts();
        TerminowStore.Host back = TerminowStore.findHostByUuid(reopened, "drivo-id");
        assertNotNull("the host must still resolve by its stable id", back);
        assertEquals("81 Drivo (prod)", back.getTitle());
        assertEquals("SSH port must be preserved", 2288, back.port);
        assertEquals("key reference must be preserved",
            "/data/data/com.terminow/files/keys/drivo_ed25519", back.keyPath);
    }

    @Test
    public void newHostIdIsNonEmptyAndUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = TerminowStore.newHostId();
            assertNotNull(id);
            assertTrue(id.length() > 0);
            assertTrue("ids must be unique", ids.add(id));
        }
    }
}
