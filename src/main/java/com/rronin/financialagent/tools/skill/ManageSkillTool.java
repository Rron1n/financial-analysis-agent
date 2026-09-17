package com.rronin.financialagent.tools.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.skills.SkillRegistry;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ManageSkillTool implements AgentTool {
    private final SkillRegistry skills;
    private final ObjectMapper mapper;

    public ManageSkillTool(SkillRegistry skills, ObjectMapper mapper) {
        this.skills = skills;
        this.mapper = mapper;
    }

    public String name() { return "manage_skill"; }

    public String description() {
        return "Create or update a SKILL.md prompt under the project's skills directory. Use for agent-managed skill creation or modification.";
    }

    public Mode mode() { return Mode.MUTATING; }

    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "required", List.of("content"),
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "Optional skill id. If omitted, the tool derives it from SKILL.md front matter name or first H1 title."),
                        "content", Map.of("type", "string", "description", "Complete SKILL.md content, including YAML front matter and workflow instructions. Include name in front matter.")
                ),
                "additionalProperties", false
        ));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        return Mono.fromCallable(() -> {
            String content = args.path("content").asText();
            String id = args.path("id").asText("");
            if (id.isBlank()) id = deriveId(content);
            return ToolResult.ok(name(), skills.upsert(id, content));
        });
    }

    private static String deriveId(String content) {
        String name = match(content, "(?m)^name:\s*(.+)$");
        if (name.isBlank() || "new-skill".equalsIgnoreCase(name.trim())) name = match(content, "(?m)^#\s+(.+)$");
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "skill-" + System.currentTimeMillis() : slug;
    }

    private static String match(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
