package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The content of a vault as it is encrypted and synced: hosts (with their passwords), groups,
 * snippets and port forwards, in the same fields as {@link TerminowStore} keeps them.
 *
 * When this phone and another one both changed a vault, the two are merged item by item: an
 * item that is on both is taken from this phone, items of either one are kept. An item that one
 * phone deleted while the other one changed the vault comes back, which is safer than losing it.
 */
public final class TerminowVaultData {

    public static final int FORMAT = 1;
    static final String[] LISTS = {"hosts", "groups", "snippets", "forwards"};

    private TerminowVaultData() {}

    @NonNull
    public static JSONObject empty() {
        JSONObject data = new JSONObject();
        try {
            data.put("format", FORMAT);
            for (String list : LISTS) data.put(list, new JSONArray());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return data;
    }

    /** Parse decrypted vault data, refusing a format that this version does not know. */
    @NonNull
    public static JSONObject parse(@NonNull String json) throws JSONException {
        JSONObject data = new JSONObject(json);
        if (data.optInt("format", 0) != FORMAT) throw new JSONException("This vault was saved by a newer TermiNow, update the app");
        for (String list : LISTS) if (data.optJSONArray(list) == null) data.put(list, new JSONArray());
        return data;
    }

    /** What identifies an item of a list, to find the same item on both phones. */
    @NonNull
    static String keyOf(@NonNull String list, @NonNull Object item) {
        if (!(item instanceof JSONObject)) return String.valueOf(item).trim();
        JSONObject o = (JSONObject) item;
        switch (list) {
            case "hosts":
                return o.optString("label") + "|" + o.optString("username") + "@" + o.optString("address") + ":" + o.optInt("port", 22);
            case "snippets":
                return o.optString("name") + "|" + o.optString("command");
            case "forwards":
                return o.optString("label") + "|" + o.optString("type") + "|" + o.optString("host") + "|" + o.optInt("port");
            default:
                return o.toString();
        }
    }

    /** Merge the vault of another phone with the one of this phone, this phone winning on the same item. */
    @NonNull
    public static JSONObject merge(@NonNull JSONObject remote, @NonNull JSONObject local) throws JSONException {
        JSONObject merged = empty();
        for (String list : LISTS) {
            Map<String, Object> items = new LinkedHashMap<>();
            JSONArray remoteItems = remote.optJSONArray(list);
            JSONArray localItems = local.optJSONArray(list);
            if (remoteItems != null)
                for (int i = 0; i < remoteItems.length(); i++) items.put(keyOf(list, remoteItems.get(i)), remoteItems.get(i));
            if (localItems != null)
                for (int i = 0; i < localItems.length(); i++) items.put(keyOf(list, localItems.get(i)), localItems.get(i));
            JSONArray out = new JSONArray();
            for (Object item : items.values()) out.put(item);
            merged.put(list, out);
        }
        return merged;
    }

    /** The number of items, for "12 hosts · 3 snippets". */
    public static int count(@NonNull JSONObject data, @NonNull String list) {
        JSONArray array = data.optJSONArray(list);
        return array == null ? 0 : array.length();
    }

    @NonNull
    static List<String> strings(@NonNull JSONArray array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
        return out;
    }

}
