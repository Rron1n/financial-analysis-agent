package com.rronin.financialagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.tools.McpSafetyRegistry;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.integrations.ibkr.IbkrTokenStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/ibkr/mcp")
public class IbkrMcpController {
    private final AgentProperties properties;
    private final ExternalApiClient api;
    private final IbkrTokenStore tokens;
    private final ObjectMapper mapper;
    private final McpSafetyRegistry approvalRules;

    public IbkrMcpController(AgentProperties properties, ExternalApiClient api, IbkrTokenStore tokens,
                             ObjectMapper mapper, McpSafetyRegistry approvalRules) {
        this.properties = properties;
        this.api = api;
        this.tokens = tokens;
        this.mapper = mapper;
        this.approvalRules = approvalRules;
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("enabled", properties.ibkr().enabled());
        base.put("mcpUrl", properties.ibkr().mcpUrl());
        base.put("authorized", tokens.accessToken().isPresent());
        if (!properties.ibkr().enabled() || tokens.accessToken().isEmpty()) {
            approvalRules.disconnect("ibkr");
            base.put("state", "DISABLED_OR_NOT_AUTHORIZED");
            return Mono.just(base);
        }
        return api.ibkrToolsList()
                .map(tools -> {
                    approvalRules.replaceServerTools("ibkr", tools);
                    base.put("state", tools.has("error") ? "MCP_ERROR" : "CONNECTED");
                    base.put("toolsList", tools);
                    return base;
                })
                .onErrorResume(error -> {
                    base.put("state", "MCP_ERROR");
                    base.put("error", error.getMessage());
                    return Mono.just(base);
                });
    }


    @GetMapping("/portfolio/minimal")
    public Mono<JsonNode> minimalPortfolio() {
        var params = mapper.createObjectNode();
        params.put("name", "get_account_positions");
        params.set("arguments", mapper.createObjectNode());
        return api.ibkrCall("tools/call", params)
                .flatMap(this::minimalPositions)
                .flatMap(node -> enrichBaseCurrencyValues((ObjectNode) node))
                .onErrorResume(error -> Mono.just(errorJson(error)));
    }

    private Mono<JsonNode> minimalPositions(JsonNode response) {
        ObjectNode out = mapper.createObjectNode();
        out.put("source", "ibkr_mcp");
        JsonNode positions = findPositions(response);
        if (positions == null) return Mono.error(new IllegalStateException("IBKR did not return a valid positions payload; refresh authorization or retry."));
        return Flux.fromIterable(positions)
                .flatMap(this::minimalHolding, 4)
                .collectList()
                .map(items -> {
                    ArrayNode holdings = mapper.createArrayNode();
                    items.forEach(holdings::add);
                    out.set("holdings", holdings);
                    out.put("count", holdings.size());
                    return out;
                });
    }

    private JsonNode findPositions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.has("error") || node.path("isError").asBoolean(false))
            throw new IllegalStateException("IBKR MCP returned an error: " + node.toString());
        if (node.path("positions").isArray()) return node.path("positions");
        if (node.isTextual()) {
            try { return findPositions(mapper.readTree(node.asText())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { return null; }
        }
        if (node.isContainerNode()) for (JsonNode child : node) {
            JsonNode found = findPositions(child);
            if (found != null) return found;
        }
        return null;
    }

    private Mono<ObjectNode> minimalHolding(JsonNode position) {
        String description = firstText(position, "company_name", "companyName", "name", "contract_description", "description", "local_symbol");
        String ticker = tickerFrom(position, description);
        String currency = position.path("currency").asText("");
        String conid = firstText(position, "conid", "con_id", "contract_id", "contractId", "contractID");
        ObjectNode item = mapper.createObjectNode();
        item.put("ticker", ticker);
        item.put("name", usefulDescription(description, ticker) ? description : knownCompanyName(ticker).orElse(ticker));
        Double marketPrice = firstDouble(position, "market_price", "marketPrice", "mktPrice");
        Double quantity = firstDouble(position, "quantity", "position", "qty", "shares");
        Double marketValue = firstDouble(position, "market_value", "marketValue", "position_value", "positionValue", "market_value_base", "marketValueBase", "mktValue");
        if (marketValue == null && marketPrice != null && quantity != null) marketValue = Math.abs(marketPrice * quantity);
        if (marketPrice != null) item.put("market_price", marketPrice);
        else item.putNull("market_price");
        if (quantity != null) item.put("quantity", quantity);
        if (marketValue != null) item.put("market_value", marketValue);
        item.put("currency", currency);
        if (ticker.isBlank() || usefulDescription(description, ticker) || knownCompanyName(ticker).isPresent()) return Mono.just(item);
        return lookupContractName(ticker, conid, currency)
                .map(name -> {
                    if (!name.isBlank()) item.put("name", name);
                    return item;
                })
                .onErrorReturn(item);
    }

    private Mono<JsonNode> enrichBaseCurrencyValues(ObjectNode out) {
        return accountBaseCurrency()
                .flatMap(baseCurrency -> {
                    out.put("base_currency", baseCurrency);
                    JsonNode holdings = out.path("holdings");
                    if (!holdings.isArray() || holdings.isEmpty()) return Mono.just((JsonNode) out);
                    Set<String> currencies = new HashSet<>();
                    for (JsonNode holding : holdings) {
                        String currency = holding.path("currency").asText("").trim().toUpperCase();
                        if (!currency.isBlank() && !currency.equals(baseCurrency)) currencies.add(currency);
                    }
                    return Flux.fromIterable(currencies)
                            .flatMap(currency -> api.fxRate(currency, baseCurrency)
                                    .map(rate -> Map.entry(currency, rate))
                                    .onErrorResume(error -> Mono.empty()))
                            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                            .map(rates -> {
                                for (JsonNode holding : holdings) {
                                    if (!(holding instanceof ObjectNode item)) continue;
                                    String currency = item.path("currency").asText("").trim().toUpperCase();
                                    Double marketValue = firstDouble(item, "market_value", "marketValue");
                                    if (marketValue == null) continue;
                                    Double rate = currency.isBlank() || currency.equals(baseCurrency) ? 1.0 : rates.get(currency);
                                    if (rate == null) continue;
                                    item.put("base_market_value", Math.abs(marketValue * rate));
                                    item.put("base_currency", baseCurrency);
                                    item.put("fx_rate_to_base", rate);
                                }
                                return out;
                            });
                })
                .onErrorResume(error -> Mono.just((JsonNode) out));
    }

    private Mono<String> accountBaseCurrency() {
        var params = mapper.createObjectNode();
        params.put("name", "get_account_summary");
        params.set("arguments", mapper.createObjectNode());
        return api.ibkrCall("tools/call", params)
                .map(summary -> firstTextDeep(summary, "base_currency", "baseCurrency", "account_currency", "accountCurrency", "currency"))
                .map(currency -> currency.isBlank() ? "USD" : currency.trim().toUpperCase())
                .defaultIfEmpty("USD")
                .onErrorReturn("USD");
    }


    private Mono<String> lookupContractName(String ticker, String conid, String currency) {
        var params = mapper.createObjectNode();
        params.put("name", "search_contracts");
        params.set("arguments", mapper.createObjectNode()
                .put("query", ticker)
                .put("language", "en_US"));
        return api.ibkrCall("tools/call", params)
                .map(response -> bestContractDescription(response, ticker, conid, currency));
    }

    private String bestContractDescription(JsonNode response, String ticker, String conid, String currency) {
        JsonNode results = response.path("result").path("structuredContent").path("results");
        if (!results.isArray()) return knownCompanyName(ticker).orElse("");
        String symbolCurrencyFallback = "";
        String symbolFallback = "";
        String firstFallback = "";
        for (JsonNode result : results) {
            String symbol = result.path("symbol").asText("");
            String description = result.path("description").asText("");
            if (description.isBlank()) description = result.path("companyName").asText("");
            if (description.isBlank()) continue;
            if (firstFallback.isBlank()) firstFallback = description;
            if (!conid.isBlank() && conid.equals(firstText(result, "conid", "con_id", "contract_id", "contractId"))) return description;
            if (symbol.equalsIgnoreCase(ticker)) {
                if (symbolFallback.isBlank()) symbolFallback = description;
                String resultCurrency = firstText(result, "currency", "currency_code");
                if (!currency.isBlank() && currency.equalsIgnoreCase(resultCurrency)) {
                    symbolCurrencyFallback = description;
                }
            }
        }
        return !symbolCurrencyFallback.isBlank() ? symbolCurrencyFallback :
                !symbolFallback.isBlank() ? symbolFallback :
                knownCompanyName(ticker).orElse(firstFallback);
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("");
            if (!value.isBlank()) return value.trim();
        }
        return "";
    }

    private Double firstDouble(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber()) return value.asDouble();
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return Double.parseDouble(value.asText().replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private String firstTextDeep(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode direct = node.path(key);
            if (direct.isTextual() && !direct.asText().isBlank()) return direct.asText().trim();
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                String found = firstTextDeep(child, keys);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private boolean usefulDescription(String description, String ticker) {
        if (description == null || description.isBlank()) return false;
        String normalized = description.trim();
        return !normalized.equalsIgnoreCase(ticker) && normalized.length() > Math.max(3, ticker.length());
    }

    private Optional<String> knownCompanyName(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "PL" -> Optional.of("Planet Labs");
            case "TE" -> Optional.of("T1 Energy");
            default -> Optional.empty();
        };
    }

    private String tickerFrom(JsonNode position, String description) {
        for (String key : java.util.List.of("symbol", "ticker", "contract_ticker")) {
            String value = position.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        if (description == null || description.isBlank()) return "";
        String first = description.trim().split("\\s+")[0];
        return first.replaceAll("[^A-Za-z0-9.\\-]", "");
    }


    @GetMapping("/tools")
    public Mono<JsonNode> tools() {
        return api.ibkrToolsList().onErrorResume(error -> Mono.just(errorJson(error)));
    }

    @GetMapping("/initialize")
    public Mono<JsonNode> initialize() {
        return api.ibkrInitialize().onErrorResume(error -> Mono.just(errorJson(error)));
    }

    private JsonNode errorJson(Throwable error) {
        var node = new com.fasterxml.jackson.databind.node.JsonNodeFactory(false).objectNode();
        node.put("status", "MCP_ERROR");
        node.put("message", error.getMessage());
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseError) {
            node.put("httpStatus", responseError.getStatusCode().value());
            node.put("responseBody", responseError.getResponseBodyAsString());
        }
        return node;
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
