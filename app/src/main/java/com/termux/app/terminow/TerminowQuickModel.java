package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What Quick Launch shows and in which order, without any view: the cards of environments and
 * pinned launch profiles by stable ids, the default, and the recent sessions. Ids never come
 * from names: "env:local", "env:<container alias>" and "profile:<uuid>".
 */
public final class TerminowQuickModel {

    public static final String LOCAL = "env:local";
    static final String ENV = "env:";
    static final String PROFILE = "profile:";

    /** How many recent sessions the section shows; the rest are behind View all. */
    public static final int RECENT_VISIBLE = 2;

    private TerminowQuickModel() {}

    @NonNull
    public static String envId(@NonNull String environmentKey) {
        return TerminowMcpTargets.TARGET_TERMINOW.equals(environmentKey) ? LOCAL : ENV + environmentKey;
    }

    @NonNull
    public static String profileId(@NonNull String uuid) {
        return PROFILE + uuid;
    }

    /** The key of the environment of an id, {@code null} if it is not one of an environment. */
    @Nullable
    public static String environmentKeyOf(@NonNull String id) {
        if (id.equals(LOCAL)) return TerminowMcpTargets.TARGET_TERMINOW;
        return id.startsWith(ENV) ? id.substring(ENV.length()) : null;
    }

    @Nullable
    public static String profileUuidOf(@NonNull String id) {
        return id.startsWith(PROFILE) ? id.substring(PROFILE.length()) : null;
    }

    /**
     * The cards in order: what is pinned first, in the order it was pinned, then the other
     * environments. A launch profile is only a card when it is pinned. Pins of what is gone,
     * like an uninstalled environment, are skipped but kept, so they come back with it.
     */
    @NonNull
    public static List<String> cards(@NonNull List<String> environmentIds, @NonNull List<String> profileIds, @NonNull List<String> pins) {
        Set<String> available = new LinkedHashSet<>(environmentIds);
        available.addAll(profileIds);
        List<String> out = new ArrayList<>();
        for (String pin : pins) if (available.contains(pin) && !out.contains(pin)) out.add(pin);
        for (String id : environmentIds) if (!out.contains(id)) out.add(id);
        return out;
    }

    /**
     * The pins of a TermiNow that had no Quick Launch yet: its launch profiles were all in the
     * menu of the + button, so they all stay one tap away. Saved pins are kept as they are.
     */
    @NonNull
    public static List<String> migratePins(@Nullable List<String> stored, @NonNull List<String> profileIds) {
        if (stored != null) return new ArrayList<>(stored);
        return new ArrayList<>(profileIds);
    }

    @NonNull
    public static List<String> togglePin(@NonNull List<String> pins, @NonNull String id) {
        List<String> out = new ArrayList<>(pins);
        if (!out.remove(id)) out.add(id);
        return out;
    }

    public static boolean isPinned(@NonNull List<String> pins, @NonNull String id) {
        return pins.contains(id);
    }

    /**
     * The id of a card from the default of new terminals as the settings keep it: "local",
     * "ubuntu:<alias>" for an environment, or the uuid of a profile.
     */
    @NonNull
    public static String idOfDefault(@NonNull String value) {
        if (value.equals("local") || value.isEmpty()) return LOCAL;
        if (value.startsWith("ubuntu:")) return ENV + value.substring("ubuntu:".length());
        return PROFILE + value;
    }

    /** The other way: what the settings keep for a card that is made the default. */
    @NonNull
    public static String defaultOfId(@NonNull String id) {
        if (id.equals(LOCAL)) return "local";
        if (id.startsWith(ENV)) return "ubuntu:" + id.substring(ENV.length());
        String uuid = profileUuidOf(id);
        return uuid != null ? uuid : "local";
    }

    // ---------------------------------------------------------------------------------------
    // Recent sessions

    public enum State {
        /** A tab of TermiNow whose program runs. */
        OPEN,
        /** A tab whose program ended; it shows what it printed, nothing runs in it. */
        ENDED,
        /** A tmux session that runs and no client is attached to. */
        DETACHED,
        /** A tmux session that runs with a client, not one of the tabs of TermiNow. */
        ATTACHED
    }

    /** A session that can be resumed, as it was found just now, never only from a saved history. */
    public static class Recent {
        /** Unique: "tab:<handle>" or "tmux:<environment>|<user>|<name>". */
        public final String key;
        public final String name;
        public final String environmentKey;
        public final String environmentLabel;
        public final State state;
        /** Milliseconds, 0 if not known. */
        public final long lastUsed;
        /** For a tmux session: its name. */
        public final String tmuxName;

        public Recent(@NonNull String key, @NonNull String name, @NonNull String environmentKey, @NonNull String environmentLabel,
                      @NonNull State state, long lastUsed, @NonNull String tmuxName) {
            this.key = key;
            this.name = name;
            this.environmentKey = environmentKey;
            this.environmentLabel = environmentLabel;
            this.state = state;
            this.lastUsed = lastUsed;
            this.tmuxName = tmuxName;
        }
    }

    /**
     * Tabs and tmux sessions together, the most recently used first, those without a time last.
     * A tmux session that a tab of TermiNow shows is that tab, not a second row.
     *
     * @param tabTmux For each tab, "<environment>|<tmux name>" of the tmux session it shows, or empty.
     */
    @NonNull
    public static List<Recent> merge(@NonNull List<Recent> tabs, @NonNull List<String> tabTmux, @NonNull List<Recent> tmux) {
        Set<String> shown = new LinkedHashSet<>(tabTmux);
        List<Recent> all = new ArrayList<>(tabs);
        for (Recent session : tmux)
            if (!shown.contains(session.environmentKey + "|" + session.tmuxName)) all.add(session);
        sort(all);
        return all;
    }

    public static void sort(@NonNull List<Recent> sessions) {
        Collections.sort(sessions, (a, b) -> {
            if (a.lastUsed == b.lastUsed) return a.name.compareToIgnoreCase(b.name);
            if (a.lastUsed == 0) return 1;
            if (b.lastUsed == 0) return -1;
            return Long.compare(b.lastUsed, a.lastUsed);
        });
    }

    /** The rows of the section: at most {@link #RECENT_VISIBLE}. */
    @NonNull
    public static List<Recent> visible(@NonNull List<Recent> sorted) {
        return new ArrayList<>(sorted.subList(0, Math.min(RECENT_VISIBLE, sorted.size())));
    }

    /** Whether the header shows View all. */
    public static boolean hasMore(@NonNull List<Recent> sorted) {
        return sorted.size() > RECENT_VISIBLE;
    }

    /** How many columns of cards fit: one on a phone, more on a foldable or tablet, from the width only. */
    public static int columns(int widthDp) {
        if (widthDp < 560) return 1;
        if (widthDp < 900) return 2;
        return 3;
    }
}
