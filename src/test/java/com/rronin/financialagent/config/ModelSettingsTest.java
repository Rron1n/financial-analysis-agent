package com.rronin.financialagent.config;

import com.rronin.financialagent.model.ModelGateway;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelSettingsTest {
    private final ModelSettings settings = new ModelSettings("https://example.test/v1", "secret",
            "primary", "auxiliary", "embedding", "high", "medium",
            Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10),
            2, 100_000, 50_000, 10_000, Map.of());

    @Test void assignsReasoningEffortByBusinessRole() {
        assertEquals("high", settings.reasoning(ModelGateway.Role.PRIMARY));
        assertEquals("low", settings.reasoning(ModelGateway.Role.FALLBACK));
        assertEquals("medium", settings.reasoning(ModelGateway.Role.FINANCIAL_AUDIT));
        assertEquals("medium", settings.reasoning(ModelGateway.Role.GENERAL_VALIDATION));
        assertEquals("medium", settings.reasoning(ModelGateway.Role.CLAIM_EXTRACTION));
        assertEquals("low", settings.reasoning(ModelGateway.Role.MEMORY_EXTRACTION));
        assertEquals("low", settings.reasoning(ModelGateway.Role.AUTO_APPROVAL));
    }
}
