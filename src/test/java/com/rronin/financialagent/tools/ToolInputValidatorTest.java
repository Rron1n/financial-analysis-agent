package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolInputValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolInputValidator validator = new ToolInputValidator();

    @Test void rejectsEnumArrayAccidentallyUsedAsPropertySchema() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"frequency\":[\"daily\",\"weekly\"]}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateSchema(schema));
    }

    @Test void checksNestedSchemasButNotEnumValues() throws Exception {
        var valid = mapper.readTree("{\"type\":\"object\",\"properties\":{\"policy\":{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"none\",\"reminder\"]}}}}}");
        assertDoesNotThrow(() -> validator.validateSchema(valid));
        var invalid = mapper.readTree("{\"type\":\"object\",\"properties\":{\"policy\":{\"type\":\"object\",\"properties\":{\"type\":[\"none\",\"reminder\"]}}}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateSchema(invalid));
    }

    @Test void remoteReferencesAreRejectedBeforeAnyResolution() throws Exception {
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"https://example.com/schema\"}}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validateSchema(schema));
    }
}
