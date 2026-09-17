package com.rronin.financialagent.tools.finance;

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
public class StockScannerTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;
    public StockScannerTool(ExternalApiClient api, ObjectMapper mapper) { this.api = api; this.mapper = mapper; }
    public String name() { return "stock_scanner"; }
    public String description() { return "Screen stocks by valuation, growth, margins, ROE, industry and GICS sector using combined filters."; }
public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return true; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of(
                        "filters", Map.of("type", "object", "additionalProperties", true),
                        "sector", Map.of("type", "string"),
                        "industry", Map.of("type", "string"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)
                ), "required", List.of("filters")));
    }
    public Mono<ToolResult> execute(JsonNode args) {
        Map<String, Object> query = mapper.convertValue(args.path("filters"), Map.class);
        query = new java.util.LinkedHashMap<>(query);
        if (args.hasNonNull("sector")) query.put("sector", args.get("sector").asText());
        if (args.hasNonNull("industry")) query.put("industry", args.get("industry").asText());
        query.put("limit", args.path("limit").asInt(25));
        return api.financialDatasets("/financial-metrics/snapshot", query)
                .map(data -> ToolResult.ok(name(), data));
    }
}
