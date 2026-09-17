package com.rronin.financialagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.integrations.ibkr.IbkrOAuthClient;
import com.rronin.financialagent.integrations.ibkr.IbkrTokenStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class ExternalApiClient {
    private final WebClient.Builder web;
    private final ObjectMapper mapper;
    private final AgentProperties properties;
    private final IbkrTokenStore ibkrTokenStore;
    private final IbkrOAuthClient ibkrOAuthClient;

    public ExternalApiClient(WebClient.Builder web, ObjectMapper mapper, AgentProperties properties, IbkrTokenStore ibkrTokenStore, IbkrOAuthClient ibkrOAuthClient) {
        this.web = web;
        this.mapper = mapper;
        this.properties = properties;
        this.ibkrTokenStore = ibkrTokenStore;
        this.ibkrOAuthClient = ibkrOAuthClient;
    }

    public Mono<JsonNode> financialDatasets(String path, Map<String, ?> query) {
        AgentProperties.Api api = properties.financialDatasets();
        if (blank(api.apiKey())) return notConfigured("FINANCIAL_DATASETS_API_KEY");
        return web.clone().baseUrl(api.baseUrl()).defaultHeader("X-API-KEY", api.apiKey()).build().get()
                .uri(builder -> {
                    builder.path(path);
                    query.forEach((key, value) -> {
                        if (value != null) builder.queryParam(key, value);
                    });
                    return builder.build();
                }).retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> tavily(String query, String topic) {
        AgentProperties.Api api = properties.tavily();
        if (blank(api.apiKey())) return notConfigured("TAVILY_API_KEY");
        ObjectNode body = mapper.createObjectNode()
                .put("api_key", api.apiKey())
                .put("query", query)
                .put("topic", topic)
                .put("search_depth", "advanced")
                .put("include_answer", false)
                .put("max_results", 8);
        return web.clone().baseUrl(api.baseUrl()).build().post().uri("/search")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class);
    }


    public Mono<JsonNode> xRecentSearch(String query, String sort, int limit, String since, Integer minLikes) {
        AgentProperties.Api api = properties.x();
        if (blank(api.apiKey())) return notConfigured("X_BEARER_TOKEN");
        var likesMatcher = java.util.regex.Pattern.compile("\\bmin_faves:(\\d+)").matcher(query);
        int requestedLikes = minLikes == null ? 0 : minLikes;
        while (likesMatcher.find()) requestedLikes = Math.max(requestedLikes, Integer.parseInt(likesMatcher.group(1)));
        final int threshold = requestedLikes;
        String finalQuery = query.replaceAll("\\bmin_faves:\\d+", "").trim();
        int maxResults = Math.max(10, Math.min(limit, 100));
        return web.clone().baseUrl(api.baseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + api.apiKey()).build().get()
                .uri(builder -> {
                    builder.path("/2/tweets/search/recent")
                            .queryParam("query", finalQuery)
                            .queryParam("max_results", maxResults)
                            .queryParam("tweet.fields", "author_id,created_at,public_metrics,lang,conversation_id,referenced_tweets,entities")
                            .queryParam("user.fields", "username,name,verified,verified_type,description,public_metrics")
                            .queryParam("expansions", "author_id,referenced_tweets.id")
                            .queryParam("sort_order", normalizeXSort(sort))
                            .queryParamIfPresent("start_time", optional(xStartTime(since)));
                    return builder.build();
                }).retrieve().bodyToMono(JsonNode.class).map(node -> {
                    if (threshold <= 0 || !node.path("data").isArray()) return node;
                    var filtered = mapper.createArrayNode();
                    node.path("data").forEach(post -> { if (post.path("public_metrics").path("like_count").asInt() >= threshold) filtered.add(post); });
                    ((ObjectNode) node).set("data", filtered);
                    ((ObjectNode) node).put("filter_note", "Minimum likes applied locally to returned sample; not exhaustive.");
                    return node;
                });
    }

    public Mono<JsonNode> xProfile(String username) {
        AgentProperties.Api api = properties.x();
        if (blank(api.apiKey())) return notConfigured("X_BEARER_TOKEN");
        return web.clone().baseUrl(api.baseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + api.apiKey()).build().get()
                .uri(builder -> builder.path("/2/users/by/username/{username}")
                        .queryParam("user.fields", "username,name,verified,verified_type,description,location,url,public_metrics,created_at")
                        .build(username.replaceFirst("^@", "")))
                .retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> xThread(String conversationId, int limit) {
        String query = "conversation_id:" + conversationId;
        return xRecentSearch(query, "recent", limit, null, null);
    }

    public Mono<JsonNode> fred(String seriesId, String start, String end) {
        AgentProperties.Api api = properties.fred();
        if (blank(api.apiKey())) return notConfigured("FRED_API_KEY");
        return web.clone().baseUrl(api.baseUrl()).build().get().uri(builder -> builder
                        .path("/series/observations")
                        .queryParam("series_id", seriesId)
                        .queryParam("api_key", api.apiKey())
                        .queryParam("file_type", "json")
                        .queryParamIfPresent("observation_start", optional(start))
                        .queryParamIfPresent("observation_end", optional(end))
                        .build())
                .retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> fredReleaseDates(String from,String to){
        AgentProperties.Api api=properties.fred();if(blank(api.apiKey()))return notConfigured("FRED_API_KEY");
        return reactor.core.publisher.Flux.just(10, 46).flatMap(id ->
                web.clone().baseUrl(api.baseUrl()).build().get().uri(builder->builder.path("/release/dates")
                .queryParam("api_key",api.apiKey()).queryParam("file_type","json").queryParam("limit",1000)
                .queryParam("release_id",id).queryParam("sort_order","asc")
                .queryParam("include_release_dates_with_no_data",true).queryParam("realtime_start",from).queryParam("realtime_end",to).build())
                .retrieve().bodyToMono(JsonNode.class).map(node -> {
                    for (JsonNode date : node.path("release_dates")) if (date instanceof ObjectNode object)
                        object.put("release_name",id == 10 ? "Consumer Price Index" : "Producer Price Index");
                    return node;
                }))
                .collectList().map(nodes -> {
                    var dates = mapper.createArrayNode();
                    nodes.forEach(node -> node.path("release_dates").forEach(dates::add));
                    return mapper.createObjectNode().set("release_dates",dates);
                });
    }

    public Mono<String> officialText(String url) {
        return web.clone().defaultHeader(HttpHeaders.USER_AGENT,"FinancialAnalysisAgent/0.1").build().get().uri(url)
                .retrieve().bodyToMono(String.class);
    }

    public Mono<String> blsReleaseCalendar() { return officialText("https://www.bls.gov/schedule/news_release/bls.ics"); }
    public Mono<String> fomcCalendar() { return officialText("https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"); }

    public Mono<JsonNode> blsSeries(java.util.List<String> seriesIds, int startYear, int endYear) {
        AgentProperties.Api bls=properties.bls();
        if(bls==null||blank(bls.apiKey()))return notConfigured("BLS_API_KEY");
        ObjectNode body=mapper.createObjectNode();var ids=body.putArray("seriesid");seriesIds.forEach(ids::add);
        body.put("startyear",String.valueOf(startYear));body.put("endyear",String.valueOf(endYear));body.put("registrationkey",bls.apiKey());
        return web.clone().baseUrl(bls.baseUrl()).build().post().uri("/timeseries/data/")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode.class);
    }


    public Mono<JsonNode> finnhub(String path, Map<String, ?> query) {
        AgentProperties.Api api = properties.finnhub();
        if (api == null || blank(api.apiKey())) return notConfigured("FINNHUB_API_KEY");
        return web.clone().baseUrl(api.baseUrl()).build().get()
                .uri(builder -> {
                    builder.path(path).queryParam("token", api.apiKey());
                    query.forEach((key, value) -> {
                        if (value != null) builder.queryParam(key, value);
                    });
                    return builder.build();
                }).retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> finnhubEarningsCalendar(String from, String to, String symbol) {
        java.util.Map<String, Object> query = new java.util.LinkedHashMap<>();
        query.put("from", from);
        query.put("to", to);
        if (!blank(symbol)) query.put("symbol", symbol);
        return finnhub("/api/v1/calendar/earnings", query);
    }


    public Mono<JsonNode> finnhubEconomicCalendar(String from, String to) {
        return finnhub("/api/v1/calendar/economic", java.util.Map.of("from", from, "to", to));
    }

    public Mono<Double> fxRate(String from, String to) {
        String source = from == null ? "" : from.trim().toUpperCase();
        String target = to == null ? "" : to.trim().toUpperCase();
        if (source.isBlank() || target.isBlank() || source.equals(target)) return Mono.just(1.0);
        return web.clone().baseUrl("https://open.er-api.com").build().get()
                .uri("/v6/latest/{source}", source)
                .retrieve().bodyToMono(JsonNode.class)
                .map(node -> node.path("rates").path(target).asDouble(Double.NaN))
                .filter(rate -> Double.isFinite(rate) && rate > 0);
    }


    public Mono<JsonNode> ibkrInitialize() {
        AgentProperties.Ibkr ibkr = properties.ibkr();
        if (!ibkr.enabled()) return notConfigured("IBKR MCP authorization");
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", System.nanoTime());
        body.put("method", "initialize");
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-06-18");
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", mapper.createObjectNode()
                .put("name", "financial-analysis-agent")
                .put("version", "0.1.0"));
        body.set("params", params);
        return ibkrMcpPost(body);
    }

    public Mono<JsonNode> ibkrToolsList() {
        AgentProperties.Ibkr ibkr = properties.ibkr();
        if (!ibkr.enabled()) return notConfigured("IBKR MCP authorization");
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", System.nanoTime());
        body.put("method", "tools/list");
        body.set("params", mapper.createObjectNode());
        return ibkrMcpPost(body);
    }


    public Mono<JsonNode> ibkrCall(String method, JsonNode params) {
        AgentProperties.Ibkr ibkr = properties.ibkr();
        if (!ibkr.enabled()) return notConfigured("IBKR authorization");
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", System.nanoTime());
        body.put("method", method);
        body.set("params", params);
        return ibkrMcpPost(body);
    }

    public Mono<Void> ibkrNotify(String method, JsonNode params) {
        ObjectNode body = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        body.set("params", params);
        return ibkrMcpPost(body).then();
    }

    private Mono<JsonNode> ibkrMcpPost(ObjectNode body) {
        AgentProperties.Ibkr ibkr = properties.ibkr();
        return ibkrOAuthClient.validAccessToken().flatMap(token -> web.clone().baseUrl(ibkr.mcpUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader("MCP-Protocol-Version", "2025-06-18")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .build()
                .post().contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class));
    }

    private static String appendMinLikes(String query, Integer minLikes) {
        if (minLikes == null || minLikes <= 0) return query;
        if (query != null && query.contains("min_faves:")) return query;
        return query + " min_faves:" + minLikes;
    }

    private static String normalizeXSort(String sort) {
        return "relevancy".equalsIgnoreCase(sort) || "likes".equalsIgnoreCase(sort) ? "relevancy" : "recency";
    }

    private static String xStartTime(String since) {
        if (blank(since)) return null;
        String value = since.trim().toLowerCase();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = switch (value) {
            case "1d", "24h" -> now.minusDays(1);
            case "2d" -> now.minusDays(2);
            case "3d" -> now.minusDays(3);
            case "7d" -> now.minusDays(7);
            default -> null;
        };
        return start == null ? since : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start);
    }

    private Mono<JsonNode> notConfigured(String key) {
        return Mono.just(mapper.createObjectNode().put("status", "NOT_CONFIGURED").put("required", key));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static java.util.Optional<String> optional(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
    }
}
