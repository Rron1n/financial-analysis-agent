package com.rronin.financialagent.model;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentModels {
    private AgentModels() {}

    public record RunRequest(@NotBlank String message, String skill, String scheduler, List<Attachment> attachments, String sessionId) {}
    public record Attachment(String name, String path, long size) {}
    public record AgentEvent(String type, String runId, Instant timestamp, Object data) {
        public static AgentEvent of(String type, String runId, Object data) {
            return new AgentEvent(type, runId, Instant.now(), data);
        }
    }
    public record ToolResult(String tool, boolean success, Object data, Map<String, Object> metadata) {
        public static ToolResult ok(String tool, Object data) {
            return new ToolResult(tool, true, data, Map.of());
        }
        public static ToolResult error(String tool, String code, String message) {
            return new ToolResult(tool, false, Map.of("code", code, "message", message), Map.of());
        }
    }
}
