package com.rronin.financialagent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
public class GetMarketDataTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;

    public GetMarketDataTool(ExternalApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    public String name() { return "get_market_data"; }
    public String description() { return "Get current or historical stock prices, company news, insider trades, institutional holdings or price context."; }
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
                        "ticker", Map.of("type", "string"),
                        "dataset", Map.of("type", "string", "enum", List.of("prices", "news", "insider_trades", "institutional_ownership", "price_context")),
                        "start_date", Map.of("type", "string"),
                        "end_date", Map.of("type", "string"),
                        "interval", Map.of("type", "string", "enum", List.of("minute", "hour", "day", "week", "month"))
                ),
                "required", List.of("ticker", "dataset"),
                "additionalProperties", false
        ));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        String dataset = args.path("dataset").asText();
        String path = switch (dataset) {
            case "prices" -> "/prices";
            case "price_context" -> "/prices/snapshot";
            case "news" -> "/news";
            case "insider_trades" -> "/insider-trades";
            case "institutional_ownership" -> "/institutional-holdings";
            default -> throw new IllegalArgumentException("Unsupported dataset: " + dataset);
        };
        Map<String, Object> query = new java.util.LinkedHashMap<>();
        String ticker = TickerNormalizer.normalize(args.path("ticker").asText());
        if (ticker.isBlank()) return Mono.just(ToolResult.error(name(), "INVALID_TICKER", "ticker is required"));
        query.put("ticker", ticker);
        if ("prices".equals(dataset)) {
            query.put("interval", args.path("interval").asText("day"));
            query.put("start_date", args.hasNonNull("start_date") ? args.get("start_date").asText() : LocalDate.now().minusYears(1).toString());
            query.put("end_date", args.hasNonNull("end_date") ? args.get("end_date").asText() : LocalDate.now().toString());
        } else {
            if (args.hasNonNull("start_date")) query.put("start_date", args.get("start_date").asText());
            if (args.hasNonNull("end_date")) query.put("end_date", args.get("end_date").asText());
        }
        return api.financialDatasets(path, query)
                .map(data -> ToolResult.ok(name(), data))
                .onErrorResume(error -> {
                    String message = safeMessage(error);
                    return Mono.just(ToolResult.ok(name(), Map.of(
                            "ticker", ticker,
                            "dataset", dataset,
                            "data", List.of(),
                            "available", false,
                            "note", "Market-data dataset unavailable or empty for this ticker. Continue with other collected evidence and disclose this limitation only if material.",
                            "error", message
                    )));
                });
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
