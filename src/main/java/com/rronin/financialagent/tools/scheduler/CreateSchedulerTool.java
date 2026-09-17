package com.rronin.financialagent.tools.scheduler;

import com.rronin.financialagent.agent.OutputPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.schedulers.ScheduledPromptService;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class CreateSchedulerTool implements AgentTool {
    private final ScheduledPromptService service;
    private final com.rronin.financialagent.schedulers.TemporarySchedulerService temporary;
    private final ObjectMapper mapper;
    public CreateSchedulerTool(ScheduledPromptService service, com.rronin.financialagent.schedulers.TemporarySchedulerService temporary, ObjectMapper mapper) { this.service = service; this.temporary=temporary; this.mapper = mapper; }
    public String name() { return "create_scheduler"; }
    public String description() { return "Create a scheduled prompt task. Use only when the user clearly asks to schedule recurring work."; }
    public JsonNode inputSchema() {
        ObjectNode props = mapper.createObjectNode();
        props.set("title", mapper.createObjectNode().put("type", "string"));
        props.set("schedulerType", mapper.valueToTree(java.util.Map.of("type","string","enum",java.util.List.of("TEMPORARY","PERSISTENT"),"description","Temporary is tied to the current session; persistent creates a new session per run.")));
        props.set("prompt", mapper.createObjectNode().put("type", "string"));
        props.set("frequency", mapper.valueToTree(java.util.Map.of("type", "string", "enum", java.util.List.of("daily", "weekdays", "weekly"))));
        props.set("dayOfWeek", mapper.createObjectNode().put("type", "string").put("description", "Required for weekly. One of SUN, MON, TUE, WED, THU, FRI, SAT."));
        props.set("time", mapper.createObjectNode().put("type", "string").put("description", "HH:mm, 24-hour time."));
        props.set("cron", mapper.createObjectNode().put("type","string").put("description","Five- or six-field cron for a temporary recurring scheduler."));
        props.set("at", mapper.createObjectNode().put("type","string").put("description","ISO-8601 instant for a one-time temporary scheduler."));
        props.set("expiresAt", mapper.createObjectNode().put("type","string").put("description","Required ISO-8601 expiry for temporary recurring schedulers."));
        props.set("recurring", mapper.createObjectNode().put("type","boolean"));
        props.set("enabled", mapper.createObjectNode().put("type", "boolean"));
        ObjectNode skills = mapper.createObjectNode().put("type", "array");
        skills.set("items", mapper.createObjectNode().put("type", "string"));
        props.set("skills", skills.put("description", "Optional skill ids, such as portfolio-news-radar, company-research, x-research."));
        props.set("maxRuntimeMinutes", mapper.createObjectNode().put("type", "integer"));
        ObjectNode outputPolicy = mapper.createObjectNode().put("type", "object");
        ObjectNode outputProps = mapper.createObjectNode();
        outputProps.set("type", mapper.valueToTree(java.util.Map.of("type", "string", "enum", java.util.List.of("none", "direct_response", "reminder", "file_output"))));
        outputProps.set("pathPattern", mapper.createObjectNode().put("type", "string").put("description", "Optional for file_output; defaults to downloads/*.md. Use reports/... only when the file should appear in the report sidebars."));
        outputPolicy.set("properties", outputProps);
        props.set("outputPolicy", outputPolicy);
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", props);
        schema.putArray("required").add("prompt");
        return schema;
    }
    public Mode mode() { return Mode.MUTATING; }
    public Mono<ToolResult> execute(JsonNode a) {
        if(a.hasNonNull("at"))return Mono.just(ToolResult.ok(name(),service.createOneTime(txt(a,"title"),txt(a,"prompt"),java.time.Instant.parse(a.path("at").asText()),txt(a,"zone"),skills(a),outputPolicy(a),a.has("maxRuntimeMinutes")?a.path("maxRuntimeMinutes").asInt():null)));
        var req = new ScheduledPromptService.UpsertRequest(txt(a,"title"), txt(a,"prompt"), txt(a,"frequency"), txt(a,"dayOfWeek"), txt(a,"time"), txt(a,"zone"), a.has("enabled") ? a.path("enabled").asBoolean() : true, null, null, skills(a), null, outputPolicy(a), a.has("maxRuntimeMinutes") ? a.path("maxRuntimeMinutes").asInt() : null);
        return Mono.just(ToolResult.ok(name(), service.create(req)));
    }
    @Override public Mono<ToolResult> execute(JsonNode a, ApprovalContext context) {
        if(!"TEMPORARY".equalsIgnoreCase(a.path("schedulerType").asText("PERSISTENT")))return execute(a);
        boolean recurring=a.path("recurring").asBoolean(!a.hasNonNull("at"));
        java.time.Instant at=a.hasNonNull("at")?java.time.Instant.parse(a.path("at").asText()):null;
        java.time.Instant expires=a.hasNonNull("expiresAt")?java.time.Instant.parse(a.path("expiresAt").asText()):null;
        String name=txt(a,"title").isBlank()?"Temporary scheduled task":txt(a,"title");
        return Mono.just(ToolResult.ok(name(),temporary.create(name,txt(a,"prompt"),txt(a,"cron"),txt(a,"zone"),at,expires,recurring,context.sessionId(),skills(a)==null?java.util.List.of():skills(a))));
    }
    private static OutputPolicy outputPolicy(JsonNode n) {
        JsonNode p = n.path("outputPolicy");
        if (!p.isObject()) return null;
        String type = p.path("type").asText("");
        if ("direct_response".equalsIgnoreCase(type)) return OutputPolicy.directResponse();
        if ("reminder".equalsIgnoreCase(type)) return OutputPolicy.reminder();
        if ("file_output".equalsIgnoreCase(type)) return OutputPolicy.fileOutput(p.path("pathPattern").asText(""));
        if ("none".equalsIgnoreCase(type)) return OutputPolicy.none();
        return null;
    }

    private static java.util.List<String> skills(JsonNode n) {
        if (!n.path("skills").isArray()) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        n.path("skills").forEach(x -> out.add(x.asText("")));
        return out;
    }
    private static String txt(JsonNode n, String k) { return n.path(k).asText(""); }
}
