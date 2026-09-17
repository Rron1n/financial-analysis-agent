package com.rronin.financialagent.tools.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.events.UpcomingEventService;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class ManageUpcomingEventTool implements AgentTool {
    private final UpcomingEventService service;
    private final ObjectMapper mapper;
    public ManageUpcomingEventTool(UpcomingEventService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    public String name() { return "manage_upcoming_event"; }
    public String description() { return "Create, update or delete a custom upcoming event. Use when the user asks to add or modify the investment calendar."; }
    public JsonNode inputSchema() {
        ObjectNode props = mapper.createObjectNode();
        props.set("action", mapper.valueToTree(java.util.Map.of("type", "string", "enum", java.util.List.of("create", "update", "delete"))));
        props.set("id", mapper.createObjectNode().put("type", "string"));
        props.set("title", mapper.createObjectNode().put("type", "string"));
        props.set("type", mapper.valueToTree(java.util.Map.of("type", "string", "enum", java.util.List.of("CUSTOM", "MACRO", "EARNINGS", "IPO", "POLITICAL", "REGULATORY"))));
        props.set("ticker", mapper.createObjectNode().put("type", "string"));
        props.set("companyName", mapper.createObjectNode().put("type", "string"));
        props.set("startsAt", mapper.createObjectNode().put("type", "string").put("description", "ISO instant, ISO local datetime, or YYYY-MM-DD."));
        props.set("timezone", mapper.createObjectNode().put("type", "string").put("description", "IANA timezone, default America/New_York."));
        props.set("importance", mapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 5));
        props.set("notes", mapper.createObjectNode().put("type", "string"));
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", props);
        schema.putArray("required").add("action");
        return schema;
    }
    public Mode mode() { return Mode.MUTATING; }
    public Mono<ToolResult> execute(JsonNode a) {
        String action = a.path("action").asText("create");
        var req = new UpcomingEventService.UpsertRequest(txt(a,"title"), txt(a,"type"), txt(a,"ticker"), txt(a,"companyName"), txt(a,"startsAt"), txt(a,"timezone"), txt(a,"source"), txt(a,"sourceUrl"), a.has("importance") ? a.path("importance").asInt() : null, a.has("notes") ? a.path("notes").asText() : null);
        return switch (action) {
            case "create" -> Mono.just(ToolResult.ok(name(), service.create(req)));
            case "update" -> Mono.just(ToolResult.ok(name(), service.update(txt(a,"id"), req)));
            case "delete" -> { service.delete(txt(a,"id")); yield Mono.just(ToolResult.ok(name(), java.util.Map.of("deleted", txt(a,"id")))); }
            default -> Mono.just(ToolResult.error(name(), "INVALID_ACTION", "Unsupported action: " + action));
        };
    }
    private static String txt(JsonNode n, String k) { return n.path(k).asText(""); }
}
