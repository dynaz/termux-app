package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.mcp.McpSchemaForm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerminowMcpSchemaFormTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"query\",\"mode\"],\"properties\":{" +
        "\"query\":{\"type\":\"string\",\"title\":\"Search text\",\"description\":\"What to find\"}," +
        "\"limit\":{\"type\":\"integer\",\"default\":10}," +
        "\"ratio\":{\"type\":[\"number\",\"null\"]}," +
        "\"exact\":{\"type\":\"boolean\"}," +
        "\"mode\":{\"type\":\"string\",\"enum\":[\"fast\",\"full\"]}," +
        "\"level\":{\"enum\":[1,2,3]}," +
        "\"filter\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}," +
        "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
        "\"target\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}";

    private static McpSchemaForm form() throws Exception {
        return McpSchemaForm.parse(new JSONObject(SCHEMA));
    }

    private static McpSchemaForm.Field field(McpSchemaForm form, String name) {
        for (McpSchemaForm.Field field : form.getFields()) if (field.name.equals(name)) return field;
        throw new AssertionError("No field " + name);
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static Map<String, String> errors(McpSchemaForm form, Map<String, String> values) {
        try {
            form.buildArguments(values);
        } catch (McpSchemaForm.InvalidArguments e) {
            return e.errors;
        }
        fail("The arguments were accepted: " + values);
        return null;
    }

    @Test
    public void kindsFromTheSchema() throws Exception {
        McpSchemaForm form = form();
        assertEquals(9, form.getFields().size());
        // The required fields come first
        assertEquals("query", form.getFields().get(0).name);
        assertEquals("mode", form.getFields().get(1).name);
        assertTrue(field(form, "query").required);
        assertEquals("Search text", field(form, "query").getLabel());
        assertEquals("What to find", field(form, "query").description);
        assertEquals(McpSchemaForm.Kind.STRING, field(form, "query").kind);
        assertEquals(McpSchemaForm.Kind.INTEGER, field(form, "limit").kind);
        assertEquals("10", field(form, "limit").defaultText);
        assertFalse(field(form, "limit").required);
        assertEquals(McpSchemaForm.Kind.NUMBER, field(form, "ratio").kind);
        assertEquals(McpSchemaForm.Kind.BOOLEAN, field(form, "exact").kind);
        assertEquals(McpSchemaForm.Kind.ENUM, field(form, "mode").kind);
        assertEquals(Arrays.asList("fast", "full"), field(form, "mode").enumValues);
        assertEquals(McpSchemaForm.Kind.ENUM, field(form, "level").kind);
        // What the form does not model is edited as JSON
        assertEquals(McpSchemaForm.Kind.JSON, field(form, "filter").kind);
        assertEquals(McpSchemaForm.Kind.JSON, field(form, "tags").kind);
        assertEquals(McpSchemaForm.Kind.JSON, field(form, "target").kind);
    }

    @Test
    public void argumentsHaveTheTypesOfTheSchema() throws Exception {
        JSONObject arguments = form().buildArguments(values("query", "cats", "mode", "full", "limit", "25", "ratio", "0.5",
            "exact", "true", "level", "2", "filter", "{\"a\": \"b\"}", "tags", "[\"x\", \"y\"]", "target", "7"));
        assertEquals("cats", arguments.get("query"));
        assertEquals("full", arguments.get("mode"));
        assertEquals(25L, ((Number) arguments.get("limit")).longValue());
        assertTrue(arguments.get("limit") instanceof Long);
        assertEquals(0.5, ((Number) arguments.get("ratio")).doubleValue(), 0);
        assertEquals(Boolean.TRUE, arguments.get("exact"));
        // An enum keeps the type of its values
        assertEquals(2, arguments.get("level"));
        assertEquals("b", arguments.getJSONObject("filter").getString("a"));
        assertEquals(2, arguments.getJSONArray("tags").length());
        assertEquals(7, arguments.get("target"));
    }

    @Test
    public void emptyOptionalFieldsAreLeftOutEvenWithADefault() throws Exception {
        McpSchemaForm form = form();
        JSONObject arguments = form.buildArguments(values("query", "cats", "mode", "fast", "limit", "", "exact", ""));
        assertEquals(2, arguments.length());
        assertFalse(arguments.has("limit"));
        assertFalse(arguments.has("exact"));
        // An optional switch without a default that is off is left out, one with a default is sent
        assertEquals("", field(form, "exact").booleanText(false));
        assertEquals("true", field(form, "exact").booleanText(true));
        McpSchemaForm withDefault = McpSchemaForm.parse(new JSONObject("{\"properties\":{\"on\":{\"type\":\"boolean\",\"default\":true}}}"));
        assertEquals("false", withDefault.getFields().get(0).booleanText(false));
    }

    @Test
    public void errorsArePerField() throws Exception {
        McpSchemaForm form = form();
        Map<String, String> errors = errors(form, values("limit", "ten", "ratio", "1.5x", "mode", "slow", "filter", "{\"a\":",
            "tags", "{\"not\": \"an array\"}"));
        assertTrue(errors.containsKey("query"));
        assertTrue(errors.containsKey("limit"));
        assertTrue(errors.containsKey("ratio"));
        assertTrue(errors.containsKey("mode"));
        assertTrue(errors.containsKey("filter"));
        assertTrue(errors.containsKey("tags"));
        assertFalse(errors.containsKey("exact"));
        assertEquals(6, errors.size());

        assertTrue(errors(form, values("query", "q", "mode", "fast", "limit", "1.5")).containsKey("limit"));
        assertTrue(errors(form, values("query", "q", "mode", "fast", "ratio", "NaN")).containsKey("ratio"));
        assertTrue(errors(form, values("query", "q", "mode", "fast", "filter", "{} extra")).containsKey("filter"));
        // Spaces alone are not a value
        assertTrue(errors(form, values("query", "   ", "mode", "fast")).containsKey("query"));
    }

    @Test
    public void aSchemaWithoutPropertiesHasNoFields() throws Exception {
        McpSchemaForm form = McpSchemaForm.parse(new JSONObject("{\"type\":\"object\"}"));
        assertTrue(form.getFields().isEmpty());
        assertEquals(0, form.buildArguments(new HashMap<>()).length());
    }

    @Test
    public void secretsAreRedactedForDisplayOnly() throws Exception {
        JSONObject arguments = new JSONObject().put("token", "tok-live-7777").put("note", "keep tok-live-7777 here")
            .put("list", new JSONArray().put("tok-live-7777").put(3));
        List<String> secrets = Arrays.asList("tok-live-7777");
        JSONObject shown = (JSONObject) McpSchemaForm.redactSecrets(arguments, secrets);
        assertEquals(TerminowMcpRedactor.REDACTED, shown.getString("token"));
        assertEquals(TerminowMcpRedactor.REDACTED, shown.getJSONArray("list").getString(0));
        assertEquals(3, shown.getJSONArray("list").getInt(1));
        // Only values that equal a secret
        assertEquals("keep tok-live-7777 here", shown.getString("note"));
        // The arguments that are sent are not changed
        assertEquals("tok-live-7777", arguments.getString("token"));
    }

}
