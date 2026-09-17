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
public class WriteFileTool implements AgentTool {
    private final FileGuard guard;
    private final ObjectMapper mapper;

    public WriteFileTool(FileGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    public String name() { return "write_file"; }
    public String description() { return "Create or overwrite a UTF-8 text file under the configured workspace root."; }
    public Mode mode() { return Mode.MUTATING; }
    public java.util.List<String> aliases() { return java.util.List.of("Write"); }
    public int maxResultSizeChars() { return 100_000; }
    public boolean isDestructive(JsonNode input) { return !guard.isInternalDraft(input.path("path").asText()) && Files.exists(guard.resolve(input.path("path").asText())); }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        guard.resolve(input.path("path").asText());
        return guard.isInternalDraft(input.path("path").asText()) ? java.util.Optional.of(com.rronin.financialagent.agent.ApprovalBehaviour.ALLOW) : java.util.Optional.empty();
    }
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of("type", "object", "required", new String[]{"path", "content"}, "properties", Map.of(
                "path", Map.of("type", "string"),
                "content", Map.of("type", "string")
        ), "additionalProperties", false));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        return Mono.fromCallable(() -> {
            var path = guard.resolve(args.path("path").asText());
            Files.createDirectories(path.getParent());
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(path, args.path("content").asText(), false);
            return ToolResult.ok(name(), Map.of("path", path.toString(), "bytes", Files.size(path)));
        });
    }
}
