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
public class GetMacroDataTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;
    public GetMacroDataTool(ExternalApiClient api, ObjectMapper mapper) { this.api = api; this.mapper = mapper; }
    public String name() { return "get_macro_data"; }
    public String description() { return "Get FRED macro series or current industry, Federal Reserve/FOMC and geopolitical news."; }
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
                        "type", Map.of("type", "string", "enum", List.of("fred_series", "industry_news", "fed_fomc_news", "geopolitical_news")),
                        "series_id", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "start_date", Map.of("type", "string"),
                        "end_date", Map.of("type", "string")
                ), "required", List.of("type")));
    }
    public Mono<ToolResult> execute(JsonNode args) {
        if ("fred_series".equals(args.path("type").asText())) {
            String series = args.path("series_id").asText();
            if (series.isBlank()) return Mono.just(ToolResult.error(name(), "INVALID_ARGUMENT", "series_id is required"));
            return api.fred(series, args.path("start_date").asText(), args.path("end_date").asText())
                    .map(data -> ToolResult.ok(name(), data));
        }
        String query = args.path("query").asText(switch (args.path("type").asText()) {
            case "industry_news" -> "latest material US stock market industry news";
            case "fed_fomc_news" -> "latest Federal Reserve FOMC release monetary policy";
            case "geopolitical_news" -> "latest geopolitical events affecting global financial markets";
            default -> throw new IllegalArgumentException("Unsupported macro data type");
        });
        return api.tavily(query, "news").map(data -> ToolResult.ok(name(), data));
    }
}
