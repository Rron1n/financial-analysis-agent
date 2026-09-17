package com.rronin.financialagent.tools.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.events.UpcomingEventService;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class GetUpcomingEventsTool implements AgentTool {
    private final UpcomingEventService service;
    private final ObjectMapper mapper;
    public GetUpcomingEventsTool(UpcomingEventService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    public String name() { return "get_upcoming_events"; }
    public String description() { return "List upcoming macro, portfolio earnings, political/regulatory and custom events."; }
    public JsonNode inputSchema() { return mapper.valueToTree(Map.of("type", "object", "properties", Map.of("months", Map.of("type", "integer", "minimum", 1, "maximum", 12)))); }
public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return false; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }
    public Mono<ToolResult> execute(JsonNode args) { return Mono.just(ToolResult.ok(name(), service.list(args.path("months").asInt(6)))); }
}
