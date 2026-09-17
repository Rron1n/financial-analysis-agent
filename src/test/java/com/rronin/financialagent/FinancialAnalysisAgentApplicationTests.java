package com.rronin.financialagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "agent.sessions-root=${java.io.tmpdir}/financial-agent-context-test-sessions",
        "agent.mcp-safety-file=${java.io.tmpdir}/financial-agent-context-test-mcp-safety.json",
        "agent.memory.root=${java.io.tmpdir}/financial-agent-test-memory",
        "agent.scheduler.root=${java.io.tmpdir}/financial-agent-test-schedulers",
        "agent.scheduler.portfolio-news-enabled=false",
        "agent.models.api-key=",
        "agent.model-routing.api-key=",
        "agent.chroma.enabled=false",
        "agent.events.root=${java.io.tmpdir}/financial-agent-context-test-events",
        "agent.ibkr.enabled=false"
})
class FinancialAnalysisAgentApplicationTests {
    @org.springframework.beans.factory.annotation.Autowired com.rronin.financialagent.tools.ToolRegistry tools;
    @org.springframework.beans.factory.annotation.Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Test void registeredToolSchemasEncodeWithoutCredentials() throws Exception {
        var request = new com.rronin.financialagent.model.ModelGateway.Request("schema-test", com.rronin.financialagent.model.ModelGateway.Role.PRIMARY,
                "Reply OK without calling tools.", java.util.List.of(com.rronin.financialagent.model.AgentMessage.text("schema-test", com.rronin.financialagent.model.AgentMessage.Role.USER, "Reply OK.", false)),
                tools.snapshot().stream().map(t -> new com.rronin.financialagent.model.ModelGateway.ToolDefinition(t.name(), t.description(), t.inputSchema())).toList(), 64_000, null);
        var body = new com.rronin.financialagent.model.ResponsesCodec(mapper).encode(request, "gpt-5.6-sol", "high", 128_000, false);
        org.assertj.core.api.Assertions.assertThat(body.path("tools").size()).isGreaterThan(0);
        String export = System.getProperty("agent.test.request-export", "");
        if (!export.isBlank()) java.nio.file.Files.writeString(java.nio.file.Path.of(export), mapper.writeValueAsString(body));
    }
    @org.springframework.beans.factory.annotation.Autowired com.rronin.financialagent.config.ModelSettings modelSettings;
    @Test void reviewStagesUseMiniMedium() {
        for(var role:java.util.List.of(com.rronin.financialagent.model.ModelGateway.Role.GENERAL_VALIDATION,com.rronin.financialagent.model.ModelGateway.Role.CLAIM_EXTRACTION,com.rronin.financialagent.model.ModelGateway.Role.FINANCIAL_AUDIT)) {
            org.assertj.core.api.Assertions.assertThat(modelSettings.model(role)).isEqualTo("gpt-5.4-mini");
            org.assertj.core.api.Assertions.assertThat(modelSettings.reasoning(role)).isEqualTo("medium");
        }
    }
    @Test
    void contextLoadsWithoutExternalCredentials() {
    }
}
