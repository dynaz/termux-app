package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of tools/call. A result with {@link #isError} is a normal answer of the tool that
 * reports a failure, unlike a JSON-RPC error, which is an {@link McpException}.
 */
public final class McpToolResult {

    /** A block of the content. Each kind is one of the final classes below. */
    public abstract static class Content {
        private Content() {}
    }

    public static final class Text extends Content {
        @NonNull public final String text;

        Text(@NonNull String text) {
            this.text = text;
        }
    }

    public static final class Image extends Content {
        @NonNull public final String mimeType;
        /** Base64. */
        @NonNull public final String data;

        Image(@NonNull String mimeType, @NonNull String data) {
            this.mimeType = mimeType;
            this.data = data;
        }
    }

    public static final class Audio extends Content {
        @NonNull public final String mimeType;
        /** Base64. */
        @NonNull public final String data;

        Audio(@NonNull String mimeType, @NonNull String data) {
            this.mimeType = mimeType;
            this.data = data;
        }
    }

    public static final class ResourceLink extends Content {
        @NonNull public final String uri;
        @NonNull public final String name;
        @NonNull public final String mimeType;

        ResourceLink(@NonNull String uri, @NonNull String name, @NonNull String mimeType) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
        }
    }

    public static final class EmbeddedResource extends Content {
        @NonNull public final String uri;
        @NonNull public final String mimeType;
        /** The text of a text resource, {@code null} for a binary one. */
        @Nullable public final String text;
        /** Base64 of a binary resource, {@code null} for a text one. */
        @Nullable public final String blob;

        EmbeddedResource(@NonNull String uri, @NonNull String mimeType, @Nullable String text, @Nullable String blob) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
            this.blob = blob;
        }
    }

    /** A kind of content that this version does not know, shown as its JSON. */
    public static final class Other extends Content {
        @NonNull public final String type;
        @NonNull public final String json;

        Other(@NonNull String type, @NonNull String json) {
            this.type = type;
            this.json = json;
        }
    }

    public final boolean isError;
    @NonNull public final List<Content> content;
    @Nullable public final JSONObject structuredContent;
    /** The result as the server sent it. */
    @NonNull public final JSONObject raw;

    private McpToolResult(boolean isError, @NonNull List<Content> content, @Nullable JSONObject structuredContent, @NonNull JSONObject raw) {
        this.isError = isError;
        this.content = Collections.unmodifiableList(content);
        this.structuredContent = structuredContent;
        this.raw = raw;
    }

    @NonNull
    public static McpToolResult parse(@NonNull JSONObject result) {
        List<Content> blocks = new ArrayList<>();
        JSONArray array = result.optJSONArray("content");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject block = array.optJSONObject(i);
            if (block != null) blocks.add(parseContent(block));
        }
        return new McpToolResult(Boolean.TRUE.equals(McpJson.bool(result, "isError")), blocks,
            result.optJSONObject("structuredContent"), result);
    }

    @NonNull
    private static Content parseContent(@NonNull JSONObject block) {
        String type = McpJson.string(block, "type");
        switch (type) {
            case "text":
                return new Text(McpJson.string(block, "text"));
            case "image":
                return new Image(McpJson.string(block, "mimeType"), McpJson.string(block, "data"));
            case "audio":
                return new Audio(McpJson.string(block, "mimeType"), McpJson.string(block, "data"));
            case "resource_link":
                return new ResourceLink(McpJson.string(block, "uri"), McpJson.string(block, "name"), McpJson.string(block, "mimeType"));
            case "resource": {
                JSONObject resource = block.optJSONObject("resource");
                if (resource == null) break;
                return new EmbeddedResource(McpJson.string(resource, "uri"), McpJson.string(resource, "mimeType"),
                    resource.opt("text") instanceof String ? McpJson.string(resource, "text") : null,
                    resource.opt("blob") instanceof String ? McpJson.string(resource, "blob") : null);
            }
            default:
                break;
        }
        return new Other(type, block.toString());
    }

}
