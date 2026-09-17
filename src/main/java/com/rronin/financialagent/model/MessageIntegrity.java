package com.rronin.financialagent.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates a complete request boundary; a tool group cannot be split by ordinary text. */
public final class MessageIntegrity {
    private MessageIntegrity() {}
    public static void validate(List<AgentMessage> messages) {
        Map<String, AgentMessage.Block> pending = new LinkedHashMap<>();
        var seen = new HashSet<String>();
        for (var message : messages) {
            boolean resultsOnly = !message.content().isEmpty() && message.content().stream()
                    .allMatch(b -> b.type() == AgentMessage.BlockType.TOOL_RESULT);
            if (!pending.isEmpty() && !resultsOnly) throw new IllegalArgumentException("Unresolved tool group before " + message.id());
            for (var block : message.content()) {
                if (block.type() == AgentMessage.BlockType.TOOL_USE) {
                    if (message.role() != AgentMessage.Role.ASSISTANT || !seen.add(block.toolCallId()))
                        throw new IllegalArgumentException("Invalid or duplicate tool use");
                    pending.put(block.toolCallId(), block);
                } else if (block.type() == AgentMessage.BlockType.TOOL_RESULT) {
                    if (pending.remove(block.toolCallId()) == null) throw new IllegalArgumentException("Orphan tool result");
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls: " + pending.keySet());
    }
}
