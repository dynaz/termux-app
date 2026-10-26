package com.termux.app.terminow;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stores QuickCuts in their own SharedPreferences file, fully apart from Snippets and the host store,
 * so the two features never share data. The list operations — add without silently overwriting a
 * duplicate name, pin, record a use, order for Pinned/Recent/All, search — and the JSON and the
 * content checks are pure static methods so they can be tested without Android.
 */
public final class TerminowQuickCuts {

    private static final String PREFS = "terminow_quickcut";
    private static final String KEY = "quickcuts";

    private final SharedPreferences mPrefs;

    public TerminowQuickCuts(@NonNull Context context) {
        this.mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public List<TerminowQuickCut> all() {
        return fromJson(mPrefs.getString(KEY, "[]"));
    }

    private void persist(@NonNull List<TerminowQuickCut> list) {
        mPrefs.edit().putString(KEY, toJson(list)).apply();
    }

    /** Add a new QuickCut or update the one with its id; returns the stored list. Never silently
     * merges two different ids with the same name — the caller checks {@link #findByName} first. */
    @NonNull
    public List<TerminowQuickCut> save(@NonNull TerminowQuickCut cut) {
        List<TerminowQuickCut> list = all();
        if (cut.id.isEmpty()) cut.id = newId();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(cut.id)) {
                list.set(i, cut);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(cut);
        persist(list);
        return list;
    }

    public void delete(@NonNull String id) {
        List<TerminowQuickCut> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                persist(list);
                return;
            }
        }
    }

    public void setPinned(@NonNull String id, boolean pinned) {
        List<TerminowQuickCut> list = all();
        for (TerminowQuickCut cut : list) if (cut.id.equals(id)) cut.pinned = pinned;
        persist(list);
    }

    /** Record a successful insertion: used only after the terminal accepted the text. */
    public void recordUse(@NonNull String id, long now) {
        List<TerminowQuickCut> list = all();
        for (TerminowQuickCut cut : list) {
            if (cut.id.equals(id)) {
                cut.usageCount++;
                cut.lastUsedAt = now;
            }
        }
        persist(list);
    }

    @Nullable
    public TerminowQuickCut byId(@NonNull String id) {
        for (TerminowQuickCut cut : all()) if (cut.id.equals(id)) return cut;
        return null;
    }

    // --- Pure list operations (testable) -------------------------------------------------------

    /** The QuickCut with this name (case-insensitive), or null — so a save can warn before replacing. */
    @Nullable
    public static TerminowQuickCut findByName(@NonNull List<TerminowQuickCut> list, @NonNull String name) {
        for (TerminowQuickCut cut : list) if (cut.name.equalsIgnoreCase(name.trim()) && !name.trim().isEmpty()) return cut;
        return null;
    }

    /** Pinned first (both ordered by most-recently-used), for the top of the picker. */
    @NonNull
    public static List<TerminowQuickCut> pinned(@NonNull List<TerminowQuickCut> list) {
        List<TerminowQuickCut> result = new ArrayList<>();
        for (TerminowQuickCut cut : list) if (cut.pinned) result.add(cut);
        Collections.sort(result, byRecent());
        return result;
    }

    /** The most-recently-used that are not pinned, newest first; only those actually used. */
    @NonNull
    public static List<TerminowQuickCut> recent(@NonNull List<TerminowQuickCut> list, int limit) {
        List<TerminowQuickCut> result = new ArrayList<>();
        for (TerminowQuickCut cut : list) if (!cut.pinned && cut.lastUsedAt > 0) result.add(cut);
        Collections.sort(result, byRecent());
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    /** Every QuickCut, pinned first then by name, for the All section. */
    @NonNull
    public static List<TerminowQuickCut> ordered(@NonNull List<TerminowQuickCut> list) {
        List<TerminowQuickCut> result = new ArrayList<>(list);
        Collections.sort(result, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return a.getTitle().compareToIgnoreCase(b.getTitle());
        });
        return result;
    }

    /** The QuickCuts whose name or content contains the query (case-insensitive); all when empty. */
    @NonNull
    public static List<TerminowQuickCut> search(@NonNull List<TerminowQuickCut> list, @NonNull String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return ordered(list);
        List<TerminowQuickCut> result = new ArrayList<>();
        for (TerminowQuickCut cut : list)
            if (cut.name.toLowerCase(Locale.ROOT).contains(q) || cut.content.toLowerCase(Locale.ROOT).contains(q)) result.add(cut);
        return result;
    }

    @NonNull
    private static Comparator<TerminowQuickCut> byRecent() {
        return (a, b) -> Long.compare(b.lastUsedAt, a.lastUsedAt);
    }

    /**
     * Whether the content could run a command if inserted, so the person must confirm first: it has a
     * line break (CR or LF) or another control character. A newline makes most shells run the line.
     */
    public static boolean needsConfirmation(@NonNull String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (c < 0x20 && c != '\t') return true;
            if (c == 0x7f) return true;
        }
        return false;
    }

    /** A short, single-line preview of the content, with line breaks shown as a return glyph. */
    @NonNull
    public static String preview(@NonNull String content, int max) {
        String flat = content.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎').trim();
        if (flat.length() > max) flat = flat.substring(0, max) + "…";
        return flat;
    }

    // --- JSON (testable) -----------------------------------------------------------------------

    @NonNull
    public static String toJson(@NonNull List<TerminowQuickCut> list) {
        JSONArray array = new JSONArray();
        try {
            for (TerminowQuickCut cut : list) {
                JSONObject object = new JSONObject();
                object.put("id", cut.id);
                object.put("name", cut.name);
                object.put("content", cut.content);
                object.put("pinned", cut.pinned);
                object.put("usageCount", cut.usageCount);
                object.put("lastUsedAt", cut.lastUsedAt);
                array.put(object);
            }
        } catch (JSONException ignored) {
            // A value that can not be written is dropped rather than losing the whole list.
        }
        return array.toString();
    }

    @NonNull
    public static List<TerminowQuickCut> fromJson(@NonNull String json) {
        List<TerminowQuickCut> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                TerminowQuickCut cut = new TerminowQuickCut();
                cut.id = object.optString("id");
                cut.name = object.optString("name");
                cut.content = object.optString("content");
                cut.pinned = object.optBoolean("pinned");
                cut.usageCount = object.optInt("usageCount");
                cut.lastUsedAt = object.optLong("lastUsedAt");
                if (cut.id.isEmpty()) cut.id = newId();
                if (!cut.content.isEmpty()) list.add(cut);
            }
        } catch (JSONException ignored) {
            // A corrupt store reads as empty rather than crashing.
        }
        return list;
    }

    @NonNull
    static String newId() {
        return Long.toString(System.nanoTime(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
    }
}
