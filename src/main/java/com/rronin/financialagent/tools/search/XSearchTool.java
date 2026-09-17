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
public class XSearchTool implements AgentTool {
    private final ExternalApiClient api;
    private final ObjectMapper mapper;

    public XSearchTool(ExternalApiClient api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "x_search";
    }

    @Override
    public String description() {
        return "Search public X posts, look up X profiles, or fetch posts from a public X conversation/thread using the X API v2.";
    }

    @Override
public Mode mode() {
        return Mode.READ_ONLY;
    }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return true; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }

    @Override
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "enum", List.of("search", "profile", "thread")),
                        "query", Map.of("type", "string", "description", "X API search query for command=search. Supports X operators such as from:, conversation_id:, -is:reply, has:links."),
                        "username", Map.of("type", "string", "description", "X username for command=profile, with or without @."),
                        "conversation_id", Map.of("type", "string", "description", "Tweet conversation id for command=thread."),
                        "sort", Map.of("type", "string", "enum", List.of("recent", "relevancy", "likes"), "description", "likes maps to X relevancy sorting."),
                        "limit", Map.of("type", "integer", "minimum", 10, "maximum", 100),
                        "since", Map.of("type", "string", "description", "Recent-search start time. Use 1d, 2d, 3d, 7d, or an ISO-8601 timestamp."),
                        "min_likes", Map.of("type", "integer", "minimum", 0, "description", "Filters the returned sample locally by public_metrics.like_count; not an exhaustive popularity search.")
                ),
                "required", List.of("command")
        ));
    }

    @Override
    public Mono<ToolResult> execute(JsonNode args) {
        String command = args.path("command").asText("search");
        return switch (command) {
            case "profile" -> profile(args);
            case "thread" -> thread(args);
            case "search" -> search(args);
            default -> Mono.just(ToolResult.error(name(), "INVALID_COMMAND", "Unsupported x_search command: " + command));
        };
    }

    private Mono<ToolResult> search(JsonNode args) {
        String query = args.path("query").asText("").trim();
        if (query.isBlank()) return Mono.just(ToolResult.error(name(), "MISSING_QUERY", "command=search requires query."));
        String sort = args.path("sort").asText("relevancy");
        int limit = args.path("limit").asInt(15);
        String since = args.path("since").asText("7d");
        Integer minLikes = args.hasNonNull("min_likes") ? args.path("min_likes").asInt() : null;
        return api.xRecentSearch(query, sort, limit, since, minLikes).map(data -> ToolResult.ok(name(), data));
    }

    private Mono<ToolResult> profile(JsonNode args) {
        String username = args.path("username").asText("").trim();
        if (username.isBlank()) return Mono.just(ToolResult.error(name(), "MISSING_USERNAME", "command=profile requires username."));
        return api.xProfile(username).map(data -> ToolResult.ok(name(), data));
    }

    private Mono<ToolResult> thread(JsonNode args) {
        String conversationId = args.path("conversation_id").asText("").trim();
        if (conversationId.isBlank()) return Mono.just(ToolResult.error(name(), "MISSING_CONVERSATION_ID", "command=thread requires conversation_id."));
        int limit = args.path("limit").asInt(30);
        return api.xThread(conversationId, limit).map(data -> ToolResult.ok(name(), data));
    }
}
