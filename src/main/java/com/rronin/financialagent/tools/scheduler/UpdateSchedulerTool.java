package com.rronin.financialagent.tools.scheduler;

import com.rronin.financialagent.agent.OutputPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.schedulers.ScheduledPromptService;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UpdateSchedulerTool implements AgentTool {
    private final ScheduledPromptService service;
    private final com.rronin.financialagent.schedulers.TemporarySchedulerService temporary;
    private final ObjectMapper mapper;
    public UpdateSchedulerTool(ScheduledPromptService service, com.rronin.financialagent.schedulers.TemporarySchedulerService temporary, ObjectMapper mapper) { this.service = service; this.temporary=temporary; this.mapper = mapper; }
    public String name() { return "update_scheduler"; }
    public String description() { return "Update an existing scheduled prompt task by id, including title, prompt, frequency, day, time or enabled state."; }
    public JsonNode inputSchema() {
        ObjectNode props = mapper.createObjectNode();
        props.set("id", mapper.createObjectNode().put("type", "string"));
        props.set("operation",mapper.valueToTree(java.util.Map.of("type","string","enum",java.util.List.of("UPDATE","PAUSE","DELETE"))));
        props.set("title", mapper.createObjectNode().put("type", "string"));
        props.set("prompt", mapper.createObjectNode().put("type", "string"));
        props.set("frequency", mapper.valueToTree(java.util.Map.of("type", "string", "enum", java.util.List.of("daily", "weekdays", "weekly"))));
        props.set("dayOfWeek", mapper.createObjectNode().put("type", "string"));
        props.set("time", mapper.createObjectNode().put("type", "string"));
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
        schema.putArray("required").add("id");
        return schema;
    }
    public Mode mode() { return Mode.MUTATING; }
    public Mono<ToolResult> execute(JsonNode a) {
        String id=txt(a,"id"),operation=a.path("operation").asText("UPDATE").toUpperCase(java.util.Locale.ROOT);
        if(id.startsWith("temp-")){
            if("UPDATE".equals(operation)&&!a.has("enabled"))return Mono.just(ToolResult.error(name(),"TEMPORARY_UPDATE_UNSUPPORTED","Cancel and recreate a temporary scheduler to change its timing"));
            temporary.delete(id);return Mono.just(ToolResult.ok(name(),java.util.Map.of("id",id,"status","DELETED")));
        }
        if("DELETE".equals(operation)){service.delete(id);return Mono.just(ToolResult.ok(name(),java.util.Map.of("id",id,"status","DELETED")));}
        Boolean enabled = a.has("enabled") ? a.path("enabled").asBoolean() : null;
        if("PAUSE".equals(operation))enabled=false;
        var req = new ScheduledPromptService.UpsertRequest(txt(a,"title"), txt(a,"prompt"), txt(a,"frequency"), txt(a,"dayOfWeek"), txt(a,"time"), txt(a,"zone"), enabled, null, null, skills(a), null, outputPolicy(a), a.has("maxRuntimeMinutes") ? a.path("maxRuntimeMinutes").asInt() : null);
        return Mono.just(ToolResult.ok(name(), service.update(id, req)));
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
