package com.rronin.financialagent.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class DownloadFileTool implements AgentTool {
    private final FileGuard guard;
    private final ObjectMapper mapper;

    public DownloadFileTool(FileGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "download_file";
    }

    @Override
    public String description() {
        return "Create a browser-download URL for a Markdown, PDF, text, CSV, JSON or other local file under the configured workspace root. Read-only; returns metadata and a relative download URL instead of file contents.";
    }

    @Override
    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "required", new String[]{"path"},
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Relative path under the configured workspace root, for example reports/NBIS/company-research-NBIS-2026-08-10.md or uploads/filing.pdf.")
                ),
                "additionalProperties", false
        ));
    }

    @Override
    public Mode mode() {
        return Mode.READ_ONLY;
    }

    @Override
    public Mono<ToolResult> execute(JsonNode arguments) {
        return Mono.fromCallable(() -> {
            String requestedPath = arguments.path("path").asText();
            Path resolved = guard.resolve(requestedPath);
            if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException("File not found");
            String filename = resolved.getFileName().toString();
            String contentType = Files.probeContentType(resolved);
            String downloadUrl = "/api/files/download?path=" + URLEncoder.encode(requestedPath, StandardCharsets.UTF_8);
            return ToolResult.ok(name(), Map.of(
                    "path", requestedPath,
                    "filename", filename,
                    "bytes", Files.size(resolved),
                    "contentType", contentType == null ? "application/octet-stream" : contentType,
                    "downloadUrl", downloadUrl
            ));
        });
    }
}
