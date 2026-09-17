package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.function.Consumer;

/** A single model invocation capability. Neither tool execution nor business prompts belong here. */
public interface ModelGateway {
    enum Role { PRIMARY, FALLBACK, AUTO_APPROVAL, GENERAL_VALIDATION, CLAIM_EXTRACTION, FINANCIAL_AUDIT, MEMORY_EXTRACTION, SEMANTIC_CHUNKING, QUERY_REWRITE }
    record ToolDefinition(String name, String description, JsonNode inputSchema) {}
    record Request(String runId, Role role, String instructions, List<AgentMessage> messages,
                   List<ToolDefinition> tools, int maxOutputTokens, JsonNode outputSchema) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
            if (role == null || maxOutputTokens < 1) throw new IllegalArgumentException("Invalid model request");
        }
    }
    record StreamEvent(String type, Object data) {}
    record Reply(AgentMessage message, long inputTokens, long outputTokens, String stopReason, String model) {
        public boolean truncated() { return "max_output_tokens".equals(stopReason); }
    }
    Mono<Reply> call(Request request, Consumer<StreamEvent> events);
    default void selectPrimary(String runId, String model) { }
    default long recordedInputTokens(String runId) { return 0; }
    default long recordedOutputTokens(String runId) { return 0; }
    default void restoreOutputUsage(String runId, long tokens) { }
    default void releaseRun(String runId) { }
}
