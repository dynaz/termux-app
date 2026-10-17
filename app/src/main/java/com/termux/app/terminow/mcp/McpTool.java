package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/** A tool of an MCP server, as tools/list describes it. */
public final class McpTool {

    @NonNull public final String name;
    /** The display name, empty if the server gives none. */
    @NonNull public final String title;
    @NonNull public final String description;
    @NonNull private final String mInputSchema;
    @Nullable private final String mOutputSchema;
    /** Hints of the server, {@code null} if it does not say. They are not guarantees. */
    @Nullable public final Boolean readOnlyHint;
    @Nullable public final Boolean destructiveHint;
    @Nullable public final Boolean idempotentHint;
    @Nullable public final Boolean openWorldHint;

    private McpTool(@NonNull String name, @NonNull String title, @NonNull String description, @NonNull JSONObject inputSchema,
                    @Nullable JSONObject outputSchema, @Nullable JSONObject annotations) {
        this.name = name;
        this.title = title;
        this.description = description;
        // Kept as text, so that no caller can change the schema of a shared tool
        mInputSchema = inputSchema.toString();
        mOutputSchema = outputSchema != null ? outputSchema.toString() : null;
        readOnlyHint = McpJson.bool(annotations, "readOnlyHint");
        destructiveHint = McpJson.bool(annotations, "destructiveHint");
        idempotentHint = McpJson.bool(annotations, "idempotentHint");
        openWorldHint = McpJson.bool(annotations, "openWorldHint");
    }

    /** Parse a tool of a tools/list result. Returns {@code null} if it has no name. */
    @Nullable
    public static McpTool parse(@NonNull JSONObject tool) {
        String name = McpJson.string(tool, "name");
        if (name.isEmpty()) return null;
        JSONObject annotations = tool.optJSONObject("annotations");
        String title = McpJson.string(tool, "title");
        if (title.isEmpty()) title = McpJson.string(annotations, "title");
        JSONObject inputSchema = tool.optJSONObject("inputSchema");
        return new McpTool(name, title, McpJson.string(tool, "description"), inputSchema != null ? inputSchema : new JSONObject(),
            tool.optJSONObject("outputSchema"), annotations);
    }

    @NonNull
    public String getDisplayName() {
        return title.isEmpty() ? name : title;
    }

    @NonNull
    public JSONObject getInputSchema() {
        return parseObject(mInputSchema);
    }

    @Nullable
    public JSONObject getOutputSchema() {
        return mOutputSchema != null ? parseObject(mOutputSchema) : null;
    }

    public boolean isReadOnly() {
        return Boolean.TRUE.equals(readOnlyHint);
    }

    /** Whether the server says that the tool may destroy or overwrite data. */
    public boolean isDestructive() {
        return !isReadOnly() && Boolean.TRUE.equals(destructiveHint);
    }

    /** The same shape as tools/list, to keep a list of tools and parse it again. */
    @NonNull
    public JSONObject toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("name", name);
            if (!title.isEmpty()) object.put("title", title);
            if (!description.isEmpty()) object.put("description", description);
            object.put("inputSchema", getInputSchema());
            JSONObject outputSchema = getOutputSchema();
            if (outputSchema != null) object.put("outputSchema", outputSchema);
            JSONObject annotations = new JSONObject();
            if (readOnlyHint != null) annotations.put("readOnlyHint", readOnlyHint);
            if (destructiveHint != null) annotations.put("destructiveHint", destructiveHint);
            if (idempotentHint != null) annotations.put("idempotentHint", idempotentHint);
            if (openWorldHint != null) annotations.put("openWorldHint", openWorldHint);
            if (annotations.length() > 0) object.put("annotations", annotations);
            return object;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    private static JSONObject parseObject(@NonNull String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

}
