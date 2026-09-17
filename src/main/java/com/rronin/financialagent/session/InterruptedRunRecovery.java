package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.rronin.financialagent.model.AgentMessage;
import java.time.Instant;
import java.util.*;

/** Repairs conversation semantics, never resumes an arbitrary instruction or replays side effects. */
public final class InterruptedRunRecovery {
    public record Plan(String runId, List<AgentMessage> messages, List<String> uncertainCalls, List<JsonNode> pendingApprovals) {
        public Plan { messages = List.copyOf(messages); uncertainCalls = List.copyOf(uncertainCalls); pendingApprovals = List.copyOf(pendingApprovals); }
        public JsonNode pendingApproval() { return pendingApprovals.isEmpty() ? null : pendingApprovals.getFirst(); }
    }
    public Optional<Plan> plan(List<JsonNode> events, List<AgentMessage> messages) {
        String runId = null;
        String status = "";
        Map<String, JsonNode> pendingApprovals = new LinkedHashMap<>();
        Set<String> readonlyCalls = new HashSet<>();
        Map<String, JsonNode> finishedCalls = new HashMap<>();
        for (JsonNode event : events) {
            String type = event.path("type").asText();
            if (Set.of("run.running", "run.started", "run.waiting_approval", "run.interrupted", "run.completed", "run.failed", "run.cancelled").contains(type)
                    && event.hasNonNull("runId")) {
                if (!event.path("runId").asText().equals(runId)) {
                    pendingApprovals.clear();
                    readonlyCalls.clear();
                }
                runId = event.path("runId").asText();
                status = type.substring(4).toUpperCase(Locale.ROOT);
            }
            if (event.hasNonNull("runId") && !event.path("runId").asText().equals(runId)) continue;
            if ("approval.requested".equals(type)) pendingApprovals.put(event.path("data").path("id").asText(event.path("data").path("toolCallId").asText()), event.path("data"));
            if ("approval.decided".equals(type)) pendingApprovals.remove(event.path("data").path("approvalId").asText());
            if ("tool.started".equals(type) && event.path("data").path("readOnly").asBoolean(false))
                readonlyCalls.add(event.path("data").path("toolCallId").asText());
            if ("tool.finished".equals(type) && event.path("data").has("result"))
                finishedCalls.put(event.path("data").path("toolCallId").asText(), event.path("data").path("result"));
        }
        if (runId == null || !Set.of("RUNNING", "STARTED", "WAITING_APPROVAL", "INTERRUPTED").contains(status)) return Optional.empty();
        List<AgentMessage> repaired = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        Map<String, AgentMessage.Block> unresolved = new LinkedHashMap<>();
        Set<String> approvalCall = new HashSet<>();
        pendingApprovals.values().forEach(approval -> approvalCall.add(approval.path("toolCallId").asText()));
        for (AgentMessage message : messages) {
            if (message.content().isEmpty()) continue;
            // A new ordinary message cannot precede missing results from a previous complete group.
            boolean resultMessage = message.content().stream().allMatch(b -> b.type() == AgentMessage.BlockType.TOOL_RESULT);
            if (!resultMessage && !unresolved.isEmpty()) {
                repairGroup(repaired, unresolved, uncertain, readonlyCalls, finishedCalls, approvalCall, runId);
            }
            List<AgentMessage.Block> retained = new ArrayList<>();
            for (var block : message.content()) {
                if (block.type() == AgentMessage.BlockType.TOOL_USE) unresolved.put(block.toolCallId(), block);
                if (block.type() == AgentMessage.BlockType.TOOL_RESULT && unresolved.remove(block.toolCallId()) == null) continue;
                retained.add(block);
            }
            if (!retained.isEmpty()) repaired.add(new AgentMessage(message.id(), message.runId(), message.role(), message.meta(), message.createdAt(), retained));
        }
        repairGroup(repaired, unresolved, uncertain, readonlyCalls, finishedCalls, approvalCall, runId);
        pendingApprovals.values().removeIf(approval -> !unresolved.containsKey(approval.path("toolCallId").asText()));
        return Optional.of(new Plan(runId, repaired, uncertain, List.copyOf(pendingApprovals.values())));
    }
    private void repairGroup(List<AgentMessage> repaired, Map<String, AgentMessage.Block> unresolved,
                             List<String> uncertain, Set<String> readOnly, Map<String, JsonNode> finished, Set<String> approvalCall, String runId) {
        List<AgentMessage.Block> results = new ArrayList<>();
        for (String id : unresolved.keySet()) {
            if (finished.containsKey(id)) {
                JsonNode result = finished.get(id);
                results.add(AgentMessage.Block.toolResult(id, result.toString(), !result.path("success").asBoolean(false)));
                continue;
            }
            if (approvalCall.contains(id)) continue;
            boolean safeToRetry = readOnly.contains(id);
            if (!safeToRetry) uncertain.add(id);
            results.add(AgentMessage.Block.toolResult(id, safeToRetry
                    ? "Execution was interrupted; result unavailable. This recorded read-only query may be repeated."
                    : "Execution status UNKNOWN after interruption. Do not replay this operation. Verify its external status or request human confirmation before any retry.", true));
        }
        if (!results.isEmpty()) repaired.add(new AgentMessage(UUID.randomUUID().toString(), runId, AgentMessage.Role.TOOL, true, Instant.now(), results));
        unresolved.entrySet().removeIf(entry -> finished.containsKey(entry.getKey()) || !approvalCall.contains(entry.getKey()));
    }
}
