package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The groups of keys of the key panel. A key is a line of tokens that are sent one after the
 * other, separated by spaces:
 *
 * - special keys: ESC TAB S-TAB HOME END PGUP PGDN INS DEL BKSP UP DOWN LEFT RIGHT ENTER SPACE F1..F12
 * - modifiers that apply to the next key: CTRL ALT
 * - control characters: ^C, ^B, ^[, ^_ ...
 * - anything else is typed as it is, like "|" or "%"
 *
 * So "^B %" is the tmux prefix and then %, which splits the window.
 */
public final class TerminowKeyGroups {

    public static final int MAX_KEYS_PER_GROUP = 6;
    public static final int MAX_GROUPS = 40;
    static final int MAX_TOKENS = 4;
    static final int MAX_LITERAL = 16;

    static final Set<String> SPECIAL = new HashSet<>(Arrays.asList("ESC", "TAB", "S-TAB", "HOME", "END", "PGUP", "PGDN", "INS",
        "DEL", "BKSP", "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "SPACE", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9",
        "F10", "F11", "F12"));
    static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList("CTRL", "ALT"));

    /** The defaults, like the key panel of Termius. */
    static final String[][] DEFAULTS = {
        {"LEFT", "UP", "DOWN", "RIGHT"},
        {"HOME", "PGUP", "PGDN", "END"},
        {"S-TAB", "CTRL", "ESC"},
        {"|", "\\", "?", "-"},
        {"^B %", "^B \""},
        {"^B o", "^B x"},
        {"ALT", "TAB", "INS", "DEL"},
        {":", ";", "!", "&"},
        {"~", "@", "$", "*"},
        {"^", "%", "=", "`"},
        {"<", ">", "(", ")"},
        {"{", "}", "[", "]"},
        {"F1", "F2", "F3", "F4"},
        {"F5", "F6", "F7", "F8"},
        {"F9", "F10", "F11", "F12"},
        {"^C", "^D", "^Z", "^L"},
        {"^R", "^W", "^A", "^E"},
    };

    private TerminowKeyGroups() {}

    @NonNull
    public static List<List<String>> defaults() {
        List<List<String>> groups = new ArrayList<>();
        for (String[] group : DEFAULTS) groups.add(new ArrayList<>(Arrays.asList(group)));
        return groups;
    }

    /** The groups that were saved, the defaults if none were or they are not valid any more. */
    @NonNull
    public static List<List<String>> fromJson(@Nullable JSONArray array) {
        if (array == null) return defaults();
        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i < array.length() && groups.size() < MAX_GROUPS; i++) {
            JSONArray keys = array.optJSONArray(i);
            if (keys == null) continue;
            List<String> group = new ArrayList<>();
            for (int j = 0; j < keys.length() && group.size() < MAX_KEYS_PER_GROUP; j++) {
                String key = keys.optString(j);
                if (isValidKey(key)) group.add(key);
            }
            if (!group.isEmpty()) groups.add(group);
        }
        return groups;
    }

    @NonNull
    public static JSONArray toJson(@NonNull List<List<String>> groups) {
        JSONArray array = new JSONArray();
        for (List<String> group : groups) array.put(new JSONArray(group));
        return array;
    }

    @NonNull
    static String[] tokens(@NonNull String key) {
        return key.trim().split(" +");
    }

    static boolean isValidToken(@NonNull String token) {
        if (token.isEmpty()) return false;
        if (SPECIAL.contains(token) || MODIFIERS.contains(token)) return true;
        if (token.length() == 2 && token.charAt(0) == '^') return controlCode(token.charAt(1)) >= 0;
        return token.codePointCount(0, token.length()) <= MAX_LITERAL && !token.contains(" ") && !token.contains("\n");
    }

    public static boolean isValidKey(@Nullable String key) {
        if (key == null || key.trim().isEmpty()) return false;
        String[] tokens = tokens(key);
        if (tokens.length > MAX_TOKENS) return false;
        for (String token : tokens) if (!isValidToken(token)) return false;
        // A key of only modifiers is one modifier, which is a toggle
        return tokens.length == 1 || !MODIFIERS.contains(tokens[tokens.length - 1]);
    }

    /** The control character for ^c, -1 if there is none. */
    static int controlCode(char c) {
        char upper = Character.toUpperCase(c);
        if (upper >= '@' && upper <= '_') return upper - '@';
        if (c == '?') return 127;
        return -1;
    }

    /** "ctrl+B, %" for "^B %", and "^C" for a single control character. */
    @NonNull
    public static String label(@NonNull String key) {
        String[] tokens = tokens(key);
        List<String> parts = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() == 2 && token.charAt(0) == '^' && tokens.length > 1) parts.add("ctrl+" + token.charAt(1));
            else parts.add(tokenLabel(token));
        }
        return join(parts);
    }

    @NonNull
    private static String join(@NonNull List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(parts.get(i));
        }
        return out.toString();
    }

    @NonNull
    static String tokenLabel(@NonNull String token) {
        switch (token) {
            case "LEFT": return "←";
            case "RIGHT": return "→";
            case "UP": return "↑";
            case "DOWN": return "↓";
            case "PGUP": return "pgUp";
            case "PGDN": return "pgDn";
            case "S-TAB": return "shift+tab";
            case "BKSP": return "⌫";
            case "ENTER": return "↵";
            case "SPACE": return "space";
            default:
                if (SPECIAL.contains(token) && !token.startsWith("F")) return token.toLowerCase(Locale.ROOT);
                if (MODIFIERS.contains(token)) return token.toLowerCase(Locale.ROOT);
                return token;
        }
    }

    /** Parse the lines of the editor of a group, one key a line; empty with the bad line in error if one is not valid. */
    @NonNull
    public static List<String> parseGroup(@NonNull String text, @NonNull StringBuilder error) {
        List<String> keys = new ArrayList<>();
        for (String line : text.split("\n")) {
            String key = line.trim().replaceAll(" +", " ");
            if (key.isEmpty()) continue;
            if (!isValidKey(key)) {
                error.append(key);
                return new ArrayList<>();
            }
            keys.add(key);
        }
        return keys;
    }

}
