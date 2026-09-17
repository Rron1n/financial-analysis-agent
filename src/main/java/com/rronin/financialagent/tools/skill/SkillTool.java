package com.rronin.financialagent.tools.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.ApprovalBehaviour;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.skills.SkillRegistry;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillTool implements AgentTool {
    private final SkillRegistry skills;
    private final ObjectMapper mapper;
    public SkillTool(SkillRegistry skills, ObjectMapper mapper) { this.skills = skills; this.mapper = mapper; }
    public String name() { return "Skill"; }
    public List<String> aliases() { return List.of("skill"); }
    public String description() { return "Load the complete instructions of an available skill. Required before implicitly using a matching skill workflow."; }
    public JsonNode inputSchema() { return mapper.valueToTree(Map.of("type", "object", "properties", Map.of(
            "skill", Map.of("type", "string"), "args", Map.of("type", "string")), "required", List.of("skill"), "additionalProperties", false)); }
    public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public Optional<ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) { return Optional.of(ApprovalBehaviour.ALLOW); }
    public Mono<ToolResult> execute(JsonNode input) {
        return Mono.fromCallable(() -> {
            String content = skills.content(input.path("skill").asText());
            if (content.matches("(?s).*\\ndisable-model-invocation:\\s*true\\s*\\n.*"))
                return ToolResult.error(name(), "EXPLICIT_INVOCATION_REQUIRED", "This skill may only be selected explicitly by the user.");
            return ToolResult.ok(name(), Map.of("skill", input.path("skill").asText(), "instructions",
                    content.replace("$ARGUMENTS", input.path("args").asText(""))));
        });
    }
}
