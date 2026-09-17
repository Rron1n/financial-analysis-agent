package com.rronin.financialagent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class GetFinancialsTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;

    public GetFinancialsTool(ExternalApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    public String name() { return "get_financials"; }
    public String description() {
        return "Get statements, metrics, valuation ratios, historical metrics and earnings for one or more US companies.";
    }
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
                        "tickers", Map.of("type", "array", "items", Map.of("type", "string")),
                        "dataset", Map.of("type", "string", "enum", List.of("income", "balance_sheet", "cash_flow", "metrics", "earnings")),
                        "period", Map.of("type", "string", "enum", List.of("annual", "quarterly", "ttm")),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 40)
                ),
                "required", List.of("tickers", "dataset")
        ));
    }
    public Mono<ToolResult> execute(JsonNode args) {
        String dataset = args.path("dataset").asText();
        String path = switch (dataset) {
            case "income" -> "/financials/income-statements";
            case "balance_sheet" -> "/financials/balance-sheets";
            case "cash_flow" -> "/financials/cash-flow-statements";
            case "metrics" -> "/financial-metrics";
            case "earnings" -> "/earnings";
            default -> throw new IllegalArgumentException("Unsupported dataset: " + dataset);
        };
        List<String> tickers = mapper.convertValue(args.path("tickers"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        int limit = args.path("limit").asInt(8);
        return Flux.fromIterable(tickers.stream()
                        .map(TickerNormalizer::normalize)
                        .filter(ticker -> !ticker.isBlank())
                        .distinct()
                        .limit(10)
                        .toList())
                .flatMap(ticker -> api.financialDatasets(path, Map.of(
                        "ticker", ticker,
                        "period", args.path("period").asText("annual"),
                        "limit", limit))
                        .map(data -> Map.entry(ticker, Map.of("success", true, "scope", scope(ticker,dataset,args.path("period").asText("annual"),data), "data", data)))
                        .onErrorResume(error -> Mono.just(Map.entry(ticker, Map.of(
                                "success", false,
                                "error", safeMessage(error),
                                "dataset", dataset
                        )))), 4)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .map(data -> {
                    boolean anySuccess = data.values().stream()
                            .filter(Objects::nonNull)
                            .anyMatch(value -> value instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("success")));
                    return anySuccess
                            ? ToolResult.ok(name(), data)
                            : ToolResult.error(name(), "NO_FINANCIAL_DATA", "No financial data was returned for the requested tickers/dataset: " + dataset);
                });
    }

    static Map<String,Object> scope(String ticker,String dataset,String period,JsonNode data) {
        var scope=new LinkedHashMap<String,Object>();
        scope.put("provider","Financial Datasets");scope.put("ticker",ticker);scope.put("dataset",dataset);
        scope.put("requestedPeriod",period);scope.put("retrievedAt",java.time.Instant.now().toString());
        var records=new java.util.ArrayList<Map<String,Object>>();collectScopes(data,"$",records);
        scope.put("records",records);
        scope.put("interpretation","Requested period is not proof of the returned period. Use each record's fiscal/calendar dates, currency, units and accounting basis. Missing fields are unknown, never assume USD, GAAP, percentage scale or identical periods. Retrieval time is not the reporting date. Weighted-average shares are not necessarily current shares outstanding. Do not combine GAAP net income with adjusted EBITDA without a disclosed reconciliation.");
        return scope;
    }
    private static void collectScopes(JsonNode node,String path,java.util.List<Map<String,Object>> records) {
        if(node.isArray()){for(int i=0;i<node.size();i++)collectScopes(node.get(i),path+"["+i+"]",records);return;}
        if(!node.isObject())return;
        var fields=new LinkedHashMap<String,Object>();fields.put("sourcePath",path);
        for(String key:List.of("report_period","period","fiscal_period","fiscal_year","calendar_date","filing_date","currency","unit","units","accounting_standard","accounting_basis"))
            if(node.hasNonNull(key)&&node.get(key).isValueNode())fields.put(key,node.get(key).asText());
        if(fields.size()>1)records.add(fields);
        node.fields().forEachRemaining(e->{if(e.getValue().isContainerNode())collectScopes(e.getValue(),path+"."+e.getKey(),records);});
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
