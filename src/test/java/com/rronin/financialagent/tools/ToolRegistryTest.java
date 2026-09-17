package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {
    @Test
    void unknownToolsDefaultToInteractive() {
        ToolRegistry registry = new ToolRegistry(List.of(), new ObjectMapper(), null);
        assertThat(registry.modeOf("unknown_tool")).isEqualTo(AgentTool.Mode.INTERACTIVE);
    }
}
