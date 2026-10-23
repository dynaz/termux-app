package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;

import com.termux.app.terminow.TerminowMcpRedactor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The fields of a form for the arguments of a tool, from its input schema. The top-level
 * properties with a simple type get a field of that kind. Anything else, like a nested object,
 * an array, oneOf or $ref, is a {@link Kind#JSON} field edited as JSON text.
 *
 * Values are the texts that the user entered, by field name.
 */
public final class McpSchemaForm {

    public enum Kind { STRING, ENUM, INTEGER, NUMBER, BOOLEAN, JSON }

    private static final Pattern INTEGER_PATTERN = Pattern.compile("[-+]?\\d+");

    public static final class Field {
        @NonNull public final String name;
        @NonNull public final String title;
        @NonNull public final String description;
        public final boolean required;
        @NonNull public final Kind kind;
        /** For {@link Kind#ENUM}, the values as texts. */
        @NonNull public final List<String> enumValues;
        /** The default of the schema as text, empty if there is none. */
        @NonNull public final String defaultText;
        /** For {@link Kind#JSON}, the JSON type the value must have, like "object", or empty for any. */
        @NonNull final String jsonType;
        @NonNull private final List<Object> mEnumRaw;

        Field(@NonNull String name, @NonNull String title, @NonNull String description, boolean required, @NonNull Kind kind,
              @NonNull List<Object> enumRaw, @NonNull String defaultText, @NonNull String jsonType) {
            this.name = name;
            this.title = title;
            this.description = description;
            this.required = required;
            this.kind = kind;
            mEnumRaw = enumRaw;
            List<String> texts = new ArrayList<>();
            for (Object value : enumRaw) texts.add(String.valueOf(value));
            enumValues = Collections.unmodifiableList(texts);
            this.defaultText = defaultText;
            this.jsonType = jsonType;
        }

        @NonNull
        public String getLabel() {
            return title.isEmpty() ? name : title;
        }

        /**
         * The value of a boolean switch. An optional boolean without a default that is off is
         * left out, so the server decides.
         */
        @NonNull
        public String booleanText(boolean checked) {
            if (checked) return "true";
            return required || !defaultText.isEmpty() ? "false" : "";
        }
    }

    /** The arguments are not valid. Each message is for the field of its key. */
    public static final class InvalidArguments extends Exception {
        @NonNull public final Map<String, String> errors;

        InvalidArguments(@NonNull Map<String, String> errors) {
            super(errors.toString());
            this.errors = Collections.unmodifiableMap(errors);
        }
    }

    @NonNull private final List<Field> mFields;

    private McpSchemaForm(@NonNull List<Field> fields) {
        mFields = Collections.unmodifiableList(fields);
    }

    @NonNull
    public List<Field> getFields() {
        return mFields;
    }

    @NonNull
    public static McpSchemaForm parse(@NonNull JSONObject inputSchema) {
        JSONObject properties = inputSchema.optJSONObject("properties");
        Set<String> required = new LinkedHashSet<>();
        JSONArray requiredArray = inputSchema.optJSONArray("required");
        for (int i = 0; requiredArray != null && i < requiredArray.length(); i++) {
            String name = requiredArray.opt(i) instanceof String ? requiredArray.optString(i) : "";
            if (properties != null && properties.has(name)) required.add(name);
        }
        List<Field> fields = new ArrayList<>();
        if (properties == null) return new McpSchemaForm(fields);
        // The required fields first, the others in the order of the schema
        List<String> names = new ArrayList<>(required);
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!required.contains(name)) names.add(name);
        }
        for (String name : names) {
            JSONObject property = properties.optJSONObject(name);
            fields.add(parseField(name, property != null ? property : new JSONObject(), required.contains(name)));
        }
        return new McpSchemaForm(fields);
    }

    @NonNull
    private static Field parseField(@NonNull String name, @NonNull JSONObject property, boolean required) {
        String type = singleType(property);
        List<Object> enumRaw = new ArrayList<>();
        JSONArray enumArray = property.optJSONArray("enum");
        boolean simpleEnum = enumArray != null && enumArray.length() > 0;
        for (int i = 0; simpleEnum && i < enumArray.length(); i++) {
            Object value = enumArray.opt(i);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) enumRaw.add(value);
            else simpleEnum = false;
        }
        boolean composite = property.has("oneOf") || property.has("anyOf") || property.has("allOf") || property.has("$ref");
        Kind kind;
        if (simpleEnum && !composite) {
            kind = Kind.ENUM;
        } else if (composite) {
            kind = Kind.JSON;
        } else if ("string".equals(type)) {
            kind = Kind.STRING;
        } else if ("integer".equals(type)) {
            kind = Kind.INTEGER;
        } else if ("number".equals(type)) {
            kind = Kind.NUMBER;
        } else if ("boolean".equals(type)) {
            kind = Kind.BOOLEAN;
        } else {
            kind = Kind.JSON;
        }
        if (kind != Kind.ENUM) enumRaw.clear();
        String defaultText = "";
        if (property.has("default") && !property.isNull("default")) {
            Object value = property.opt("default");
            defaultText = value instanceof JSONObject || value instanceof JSONArray ? value.toString() : String.valueOf(value);
        }
        String jsonType = kind == Kind.JSON && ("object".equals(type) || "array".equals(type)) ? type : "";
        return new Field(name, McpJson.string(property, "title"), McpJson.string(property, "description"), required, kind,
            enumRaw, defaultText, jsonType);
    }

    /** The type of a property, the non-null one of a type like ["string", "null"], or empty. */
    @NonNull
    private static String singleType(@NonNull JSONObject property) {
        Object type = property.opt("type");
        if (type instanceof String) return (String) type;
        if (type instanceof JSONArray) {
            JSONArray types = (JSONArray) type;
            String found = "";
            for (int i = 0; i < types.length(); i++) {
                String t = types.optString(i);
                if (t.equals("null")) continue;
                if (!found.isEmpty()) return "";
                found = t;
            }
            return found;
        }
        return "";
    }

    /**
     * The arguments for tools/call. Empty optional fields are left out, so the server uses its
     * defaults.
     */
    @NonNull
    public JSONObject buildArguments(@NonNull Map<String, String> values) throws InvalidArguments {
        JSONObject arguments = new JSONObject();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Field field : mFields) {
            String text = values.get(field.name);
            if (text == null) text = "";
            if (text.trim().isEmpty()) {
                if (field.required) errors.put(field.name, "Required");
                continue;
            }
            try {
                arguments.put(field.name, parseValue(field, text));
            } catch (IllegalArgumentException e) {
                errors.put(field.name, String.valueOf(e.getMessage()));
            } catch (JSONException e) {
                errors.put(field.name, "Not valid JSON: " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) throw new InvalidArguments(errors);
        return arguments;
    }

    @NonNull
    private static Object parseValue(@NonNull Field field, @NonNull String text) throws JSONException {
        String trimmed = text.trim();
        switch (field.kind) {
            case STRING:
                return text;
            case ENUM: {
                int index = field.enumValues.indexOf(text);
                if (index < 0) throw new IllegalArgumentException("Choose one of " + field.enumValues);
                return field.mEnumRaw.get(index);
            }
            case INTEGER:
                if (!INTEGER_PATTERN.matcher(trimmed).matches()) throw new IllegalArgumentException("Enter a whole number");
                try {
                    return Long.parseLong(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("The number is too large");
                }
            case NUMBER: {
                if (INTEGER_PATTERN.matcher(trimmed).matches()) {
                    try {
                        return Long.parseLong(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed);
                    } catch (NumberFormatException e) {
                        // A double holds it
                    }
                }
                double number;
                try {
                    number = Double.parseDouble(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Enter a number");
                }
                if (Double.isNaN(number) || Double.isInfinite(number) || trimmed.matches(".*[a-zA-Z&&[^eE]].*"))
                    throw new IllegalArgumentException("Enter a number");
                return number;
            }
            case BOOLEAN:
                if (trimmed.equals("true")) return Boolean.TRUE;
                if (trimmed.equals("false")) return Boolean.FALSE;
                throw new IllegalArgumentException("Choose true or false");
            case JSON:
            default: {
                JSONTokener tokener = new JSONTokener(trimmed);
                Object value = tokener.nextValue();
                if (tokener.nextClean() != 0) throw new JSONException("Unexpected text after the value");
                if (field.jsonType.equals("object") && !(value instanceof JSONObject))
                    throw new IllegalArgumentException("Enter a JSON object, like {\"key\": \"value\"}");
                if (field.jsonType.equals("array") && !(value instanceof JSONArray))
                    throw new IllegalArgumentException("Enter a JSON array, like [1, 2]");
                return value;
            }
        }
    }

    /**
     * A copy of JSON in which every string that equals a secret is replaced, to show arguments
     * without the secrets that are stored for the server.
     */
    @NonNull
    public static Object redactSecrets(@NonNull Object json, @NonNull Collection<String> secrets) throws JSONException {
        if (json instanceof String) return secrets.contains(json) ? TerminowMcpRedactor.REDACTED : json;
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            JSONObject copy = new JSONObject();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                copy.put(key, redactSecrets(object.get(key), secrets));
            }
            return copy;
        }
        if (json instanceof JSONArray) {
            JSONArray array = (JSONArray) json;
            JSONArray copy = new JSONArray();
            for (int i = 0; i < array.length(); i++) copy.put(redactSecrets(array.get(i), secrets));
            return copy;
        }
        return json;
    }

}
