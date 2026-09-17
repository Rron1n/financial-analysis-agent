package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Provider-independent, durable messages. Meta content remains distinguishable from user instructions. */
public record AgentMessage(String id, String runId, Role role, boolean meta, Instant createdAt, List<Block> content, List<AgentModels.Attachment> attachments) {
    public AgentMessage(String id, String runId, Role role, boolean meta, Instant createdAt, List<Block> content) {
        this(id, runId, role, meta, createdAt, content, List.of());
    }
    public enum Role { USER, ASSISTANT, TOOL }
    public enum BlockType { TEXT, PROGRESS, THINKING, TOOL_USE, TOOL_RESULT }

    public AgentMessage {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Message id is required");
        if (role == null || createdAt == null) throw new IllegalArgumentException("Message role and timestamp are required");
        content = List.copyOf(content);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record Block(BlockType type, String text, String toolCallId, String name, JsonNode input, boolean error) {
        public Block {
            if (type == null) throw new IllegalArgumentException("Block type is required");
            if ((type == BlockType.TOOL_USE || type == BlockType.TOOL_RESULT)
                    && (toolCallId == null || toolCallId.isBlank())) throw new IllegalArgumentException("Tool call id is required");
            if (type == BlockType.TOOL_USE && (name == null || name.isBlank() || input == null || !input.isObject()))
                throw new IllegalArgumentException("Tool use needs a name and object input");
            input = input == null ? null : input.deepCopy();
        }
        public static Block text(String value) { return new Block(BlockType.TEXT, value, null, null, null, false); }
        public static Block toolUse(String id, String name, JsonNode input) {
            return new Block(BlockType.TOOL_USE, null, id, name, input, false);
        }
        public static Block toolResult(String id, String text, boolean error) {
            return new Block(BlockType.TOOL_RESULT, text, id, null, null, error);
        }
    }

    public static AgentMessage text(String runId, Role role, String text, boolean meta) {
        return new AgentMessage(UUID.randomUUID().toString(), runId, role, meta, Instant.now(), List.of(Block.text(text)));
    }
    public String text() {
        return content.stream().filter(b -> b.type() == BlockType.TEXT).map(Block::text)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
    }
    public List<Block> toolUses() { return content.stream().filter(b -> b.type() == BlockType.TOOL_USE).toList(); }
}
