package com.rronin.financialagent.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;
    public WebSearchTool(ExternalApiClient api, ObjectMapper mapper) { this.api = api; this.mapper = mapper; }
    public String name() { return "web_search"; }
    public String description() { return "Search the current web or news with source URLs using Tavily."; }
public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return true; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string"),
                "topic", Map.of("type", "string", "enum", List.of("general", "news"))
        ), "required", List.of("query")));
    }
    public Mono<ToolResult> execute(JsonNode args) {
        return api.tavily(args.path("query").asText(), args.path("topic").asText("general"))
                .map(data -> ToolResult.ok(name(), data));
    }
}
