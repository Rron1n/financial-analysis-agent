package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

@Component
public class ToolInputValidator {
    public void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("Tool input schema must be an object");
        rejectRemoteReferences(schema, 0);
        schemaShape(schema, 0);
    }
    private void schemaShape(JsonNode node, int depth) {
        if (depth > 64 || (!node.isObject() && !node.isBoolean())) throw new IllegalArgumentException("Invalid JSON Schema node");
        for (String map : java.util.List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")) {
            if (!node.has(map)) continue;
            if (!node.path(map).isObject()) throw new IllegalArgumentException("Schema map must be an object");
            node.path(map).forEach(child -> schemaShape(child, depth + 1));
        }
        for (String key : java.util.List.of("items", "additionalProperties", "contains", "not", "if", "then", "else", "propertyNames"))
            if (node.has(key)) schemaShape(node.path(key), depth + 1);
        for (String key : java.util.List.of("allOf", "anyOf", "oneOf", "prefixItems")) {
            if (!node.has(key)) continue;
            if (!node.path(key).isArray()) throw new IllegalArgumentException("Schema alternatives must be an array");
            node.path(key).forEach(child -> schemaShape(child, depth + 1));
        }
    }
    public void validate(JsonNode schema, JsonNode arguments) {
        if (schema == null || arguments == null || !arguments.isObject()) throw new IllegalArgumentException("INVALID_TOOL_INPUT: object arguments are required");
        validateSchema(schema);
        var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema).validate(arguments);
        if (!errors.isEmpty()) throw new IllegalArgumentException("INVALID_TOOL_INPUT: " + errors.stream().map(Object::toString).limit(5).toList());
    }
    private void rejectRemoteReferences(JsonNode node, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Tool schema nesting exceeds limit");
        if (node.has("$schema") && !java.util.Set.of(
                "https://json-schema.org/draft/2020-12/schema", "https://json-schema.org/draft/2019-09/schema",
                "http://json-schema.org/draft-07/schema#", "http://json-schema.org/draft-06/schema#",
                "http://json-schema.org/draft-04/schema#").contains(node.path("$schema").asText()))
            throw new IllegalArgumentException("Unsupported schema dialect");
        for (String key : java.util.List.of("$ref", "$dynamicRef", "$recursiveRef")) {
            if (node.has(key) && !node.path(key).asText().startsWith("#"))
                throw new IllegalArgumentException("Remote schema references are not permitted");
        }
        if (node.isContainerNode()) node.forEach(child -> rejectRemoteReferences(child, depth + 1));
    }
}
