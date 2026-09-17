package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.integrations.ibkr.IbkrTokenStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class McpDiscoveryService {
    private final AgentProperties properties;
    private final ExternalApiClient client;
    private final IbkrTokenStore tokens;
    private final ToolRegistry registry;
    private final McpSafetyRegistry safety;
    private final ObjectMapper mapper;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile String state = "DISCONNECTED";
    public McpDiscoveryService(AgentProperties properties, ExternalApiClient client, IbkrTokenStore tokens,
                               ToolRegistry registry, McpSafetyRegistry safety, ObjectMapper mapper) {
        this.properties = properties; this.client = client; this.tokens = tokens; this.registry = registry; this.safety = safety; this.mapper = mapper;
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    public void refresh() {
        if (!properties.ibkr().enabled() || tokens.accessToken().isEmpty()) { registry.replaceMcpTools("ibkr", List.of()); state = "DISCONNECTED"; return; }
        if (!refreshing.compareAndSet(false, true)) return;
        Mono<Void> initialize = "CONNECTED".equals(state) ? Mono.empty()
                : client.ibkrInitialize().then(client.ibkrNotify("notifications/initialized", mapper.createObjectNode())).then();
        var seenCursors = new java.util.HashSet<String>();
        initialize.then(client.ibkrToolsList()).expand(page -> {
                    String cursor = page.path("result").path("nextCursor").asText();
                    if (!cursor.isBlank() && (!seenCursors.add(cursor) || seenCursors.size() >= 100))
                        return Mono.error(new IllegalStateException("MCP pagination is cyclic or exceeds the page limit"));
                    return cursor.isBlank() ? Mono.empty() : client.ibkrCall("tools/list", mapper.createObjectNode().put("cursor", cursor));
                }).collectList().map(pages -> {
                    var combined = mapper.createObjectNode();
                    var list = combined.putObject("result").putArray("tools");
                    for (JsonNode page : pages) {
                        if (!page.path("result").path("tools").isArray()) throw new IllegalStateException("Invalid MCP tools/list response");
                        page.path("result").path("tools").forEach(list::add);
                    }
                    safety.replaceServerTools("ibkr", combined);
                    List<AgentTool> tools = new java.util.ArrayList<>();
                    list.forEach(definition -> tools.add(new McpTool("ibkr", definition, client, safety, mapper)));
                    registry.replaceMcpTools("ibkr", tools);
                    state = "CONNECTED";
                    return true;
                }).timeout(java.time.Duration.ofSeconds(30)).doFinally(signal -> refreshing.set(false))
                .subscribe(ignored -> { }, error -> {
                    registry.replaceMcpTools("ibkr", List.of());
                    state = "UNAVAILABLE";
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("MCP discovery unavailable ({})", error.getClass().getSimpleName());
                });
    }
    public String state() { return state; }
}
