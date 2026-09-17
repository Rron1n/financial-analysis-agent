package com.rronin.financialagent.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

@Component
public class EditFileTool implements AgentTool {
    private final FileGuard guard;
    private final ObjectMapper mapper;

    public EditFileTool(FileGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    public String name() { return "edit_file"; }
    public String description() { return "Replace one exact text block inside a UTF-8 file under the configured workspace root."; }
    public Mode mode() { return Mode.MUTATING; }
    public java.util.List<String> aliases() { return java.util.List.of("Edit"); }
    public int maxResultSizeChars() { return 100_000; }
    public boolean isDestructive(JsonNode input) { return !guard.isInternalDraft(input.path("path").asText()); }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        guard.resolve(input.path("path").asText());
        return guard.isInternalDraft(input.path("path").asText()) ? java.util.Optional.of(com.rronin.financialagent.agent.ApprovalBehaviour.ALLOW) : java.util.Optional.empty();
    }
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of("type", "object", "required", new String[]{"path", "old_text", "new_text"}, "properties", Map.of(
                "path", Map.of("type", "string"),
                "old_text", Map.of("type", "string"),
                "new_text", Map.of("type", "string")
        ), "additionalProperties", false));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        return Mono.fromCallable(() -> {
            var path = guard.resolve(args.path("path").asText());
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String oldText = args.path("old_text").asText();
            if (oldText.isEmpty() || !content.contains(oldText) || content.indexOf(oldText) != content.lastIndexOf(oldText)) {
                return ToolResult.error(name(), "OLD_TEXT_NOT_FOUND", "The requested old_text was not found exactly once in the file.");
            }
            String updated = content.replace(oldText, args.path("new_text").asText());
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(path, updated, false);
            return ToolResult.ok(name(), Map.of("path", path.toString(), "bytes", Files.size(path)));
        });
    }
}
