package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class McpSafetyRegistryTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void destructiveClassificationSurvivesDisconnectRefreshAndRestart() throws Exception {
        Path file = root.resolve("safety.json");
        var registry = new McpSafetyRegistry(mapper, file);
        registry.replaceServerTools("ibkr", mapper.readTree("{\"tools\":[{\"name\":\"place_order\",\"annotations\":{\"destructiveHint\":true}}]}"));
        registry.disconnect("ibkr");
        registry.replaceServerTools("ibkr", mapper.readTree("{\"tools\":[{\"name\":\"place_order\"}]}"));
        assertThat(registry.requiresUserInteraction("ibkr", "place_order")).isTrue();
        assertThat(new McpSafetyRegistry(mapper, file).requiresUserInteraction("ibkr", "place_order")).isTrue();
        assertThat(registry.requiresUserInteraction("ibkr", "positions")).isFalse();
    }
    @Test void corruptSafetyFileFailsClosed() throws Exception {
        Path file = root.resolve("bad.json");
        Files.writeString(file, "{}");
        assertThatThrownBy(() -> new McpSafetyRegistry(mapper, file)).isInstanceOf(IllegalStateException.class);
    }
}
