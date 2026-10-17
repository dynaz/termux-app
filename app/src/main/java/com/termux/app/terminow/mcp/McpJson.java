package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Typed reads of server JSON. The org.json of Android turns a JSON null into the text "null"
 * in optString, so values are checked by type instead.
 */
final class McpJson {

    private McpJson() {}

    @NonNull
    static String string(@Nullable JSONObject object, @NonNull String key) {
        Object value = object != null ? object.opt(key) : null;
        return value instanceof String ? (String) value : "";
    }

    @Nullable
    static Boolean bool(@Nullable JSONObject object, @NonNull String key) {
        Object value = object != null ? object.opt(key) : null;
        return value instanceof Boolean ? (Boolean) value : null;
    }

}
