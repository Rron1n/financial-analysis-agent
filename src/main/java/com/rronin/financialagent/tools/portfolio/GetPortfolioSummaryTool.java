package com.rronin.financialagent.tools.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class GetPortfolioSummaryTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;

    public GetPortfolioSummaryTool(ExternalApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    public String name() { return "get_portfolio_summary"; }
    public String description() { return "Read currently authorized IBKR MCP account summary through official get_account_summary. Read-only."; }
    public Mode mode() { return Mode.READ_ONLY; }
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of("type", "object", "properties", Map.of(
                "include_pnl", Map.of("type", "boolean", "default", true),
                "include_cash", Map.of("type", "boolean", "default", true)
        ), "additionalProperties", false));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        var callArgs = mapper.createObjectNode();
        callArgs.put("name", "get_account_summary");
        callArgs.set("arguments", mapper.createObjectNode());
        return api.ibkrCall("tools/call", callArgs).map(data -> ToolResult.ok(name(), data));
    }
}
