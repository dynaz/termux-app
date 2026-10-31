package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pure model behind the Hosts page list: it turns the saved hosts plus the current scope
 * (group, search, protocol filter and per-section collapse) into the exact ordered sequence of
 * rows the RecyclerView shows — Local Terminal, group chips, connection-type section headers and
 * host cards — and nothing else. It holds no Android state, so the ordering (and therefore which
 * host sits at which display position) is unit-testable on the JVM.
 *
 * <p>Crucially, a {@link Row} of kind {@link Kind#HOST} carries the host's stable
 * {@link TerminowStore.Host#uuid}, never its display position. Callers resolve an action target by
 * that uuid against the live list ({@link TerminowStore#findHostByUuid}), so inserting headers,
 * collapsing a section, filtering or searching — all of which move display positions — can never
 * make an Edit/Duplicate/Delete act on a different host than the one that was tapped.</p>
 */
public final class TerminowHostRows {

    private TerminowHostRows() {}

    /** Section display order; empty ones are hidden. VNC is listed but never has hosts (no VNC connect). */
    public static final String[] PROTOCOL_ORDER = {"ssh", "vnc", "rdp", "winrm", "other"};

    /** The kind of a row in the flat Hosts list. */
    public enum Kind { LOCAL, GROUP, SECTION, HOST }

    /** One row of the Hosts list. Only the fields that apply to its {@link #kind} are meaningful. */
    public static final class Row {
        public final Kind kind;
        /** GROUP: the group name. */
        public final String group;
        /** SECTION: the protocol key (one of {@link #PROTOCOL_ORDER}). */
        public final String protocol;
        /** SECTION: how many hosts the section holds (shown whether collapsed or not). */
        public final int count;
        /** SECTION: whether the section is collapsed (its hosts then have no HOST rows). */
        public final boolean collapsed;
        /** HOST: the host this row stands for. */
        public final TerminowStore.Host host;

        private Row(Kind kind, String group, String protocol, int count, boolean collapsed, TerminowStore.Host host) {
            this.kind = kind;
            this.group = group;
            this.protocol = protocol;
            this.count = count;
            this.collapsed = collapsed;
            this.host = host;
        }

        static Row local() { return new Row(Kind.LOCAL, null, null, 0, false, null); }
        static Row group(String name) { return new Row(Kind.GROUP, name, null, 0, false, null); }
        static Row section(String protocol, int count, boolean collapsed) {
            return new Row(Kind.SECTION, null, protocol, count, collapsed, null);
        }
        static Row host(TerminowStore.Host host) { return new Row(Kind.HOST, null, null, 0, false, host); }
    }

    /** The built list plus the information the caller needs for the filter chips and a stale-filter reset. */
    public static final class Result {
        public final List<Row> rows;
        /** Hosts present in the current scope bucketed by protocol, before the protocol filter — for the chips. */
        public final LinkedHashMap<String, List<TerminowStore.Host>> byProtocol;
        /** The protocol filter actually applied: "" when the requested one has no hosts in scope. */
        public final String effectiveProtocolFilter;

        Result(List<Row> rows, LinkedHashMap<String, List<TerminowStore.Host>> byProtocol, String effectiveProtocolFilter) {
            this.rows = rows;
            this.byProtocol = byProtocol;
            this.effectiveProtocolFilter = effectiveProtocolFilter;
        }
    }

    /** The section a host belongs to, from the REAL connection type on the record (never guessed). */
    @NonNull
    public static String protocolKey(@NonNull TerminowStore.Host host) {
        if (host.isRdp()) return "rdp";
        if (host.isWinrm()) return "winrm";
        String p = host.protocol == null ? "" : host.protocol;
        if (p.isEmpty() || TerminowStore.Host.PROTOCOL_SSH.equals(p)) return "ssh";
        return "other";   // an unrecognized protocol value: shown in Other, data left untouched
    }

    /**
     * Whether a host is shown while {@code group} is the open group. The hosts of a group that no
     * longer exists fall in with the hosts that have no group (so they are never lost).
     */
    public static boolean inGroup(@NonNull TerminowStore.Host host, @Nullable String group, @NonNull List<String> groups) {
        if (group == null) return host.group.isEmpty() || !groups.contains(host.group);
        return group.equals(host.group);
    }

    /** Whether a host matches the search: name, hostname/IP, username or tags (never the password). */
    public static boolean matches(@NonNull TerminowStore.Host host, @NonNull String query) {
        if (query.isEmpty()) return true;
        if (host.getTitle().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (host.address.toLowerCase(Locale.ROOT).contains(query)) return true;
        if (host.username.toLowerCase(Locale.ROOT).contains(query)) return true;
        return joinTags(host).toLowerCase(Locale.ROOT).contains(query);
    }

    private static String joinTags(@NonNull TerminowStore.Host host) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < host.tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(host.tags.get(i));
        }
        return sb.toString();
    }

    /**
     * Build the full ordered Hosts list.
     *
     * @param allHosts         every saved host in the active vault, in saved order
     * @param groups           the user-defined groups (to detect a dangling host group)
     * @param currentGroup     the open group, or {@code null} for the top level; ignored while searching
     * @param query            the lowercased, trimmed search ("" = not searching)
     * @param localMatchesQuery whether the Local Terminal label matches the search (only used while searching)
     * @param collapsed        the protocols whose sections are collapsed (ignored while searching)
     * @param protocolFilter   the selected protocol chip ("" = All)
     */
    @NonNull
    public static Result build(@NonNull List<TerminowStore.Host> allHosts, @NonNull List<String> groups,
                               @Nullable String currentGroup, @NonNull String query, boolean localMatchesQuery,
                               @NonNull Set<String> collapsed, @NonNull String protocolFilter) {
        boolean searching = !query.isEmpty();
        List<Row> rows = new ArrayList<>();

        // Local Terminal + group chips at the top of the ungrouped, unsearched view; while searching,
        // Local Terminal only when it matches.
        if (!searching && currentGroup == null) {
            rows.add(Row.local());
            for (String group : groups) rows.add(Row.group(group));
        } else if (searching && localMatchesQuery) {
            rows.add(Row.local());
        }

        // Bucket the hosts in scope by their real connection type, in the fixed order.
        LinkedHashMap<String, List<TerminowStore.Host>> byProto = new LinkedHashMap<>();
        for (String p : PROTOCOL_ORDER) byProto.put(p, new ArrayList<>());
        for (TerminowStore.Host host : allHosts) {
            if (!searching && !inGroup(host, currentGroup, groups)) continue;   // group filter
            if (searching && !matches(host, query)) continue;                   // search across all groups
            byProto.get(protocolKey(host)).add(host);                           // protocolKey is always in order
        }

        // A filter that selects an empty (or absent) section falls back to All, so the list is never blank.
        String effectiveFilter = protocolFilter;
        if (!effectiveFilter.isEmpty()) {
            List<TerminowStore.Host> sel = byProto.get(effectiveFilter);
            if (sel == null || sel.isEmpty()) effectiveFilter = "";
        }

        for (String p : PROTOCOL_ORDER) {
            if (!effectiveFilter.isEmpty() && !effectiveFilter.equals(p)) continue;   // protocol filter
            List<TerminowStore.Host> list = byProto.get(p);
            if (list == null || list.isEmpty()) continue;
            boolean isCollapsed = !searching && collapsed.contains(p);
            rows.add(Row.section(p, list.size(), isCollapsed));
            if (!isCollapsed) for (TerminowStore.Host host : list) rows.add(Row.host(host));
        }
        return new Result(rows, byProto, effectiveFilter);
    }
}
