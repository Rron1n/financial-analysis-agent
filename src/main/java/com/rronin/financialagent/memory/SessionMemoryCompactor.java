package com.rronin.financialagent.memory;

import com.rronin.financialagent.agent.TokenEstimator;
import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.model.MessageIntegrity;
import java.util.*;

/** Reuses an existing consistent memory snapshot; never starts a hidden full-compaction LLM. */
public final class SessionMemoryCompactor {
    public record Boundary(long revisionEdition, String coveredThroughMessageId, String retainedFromMessageId,
                           String memoryBody, List<AgentMessage> retained, long estimatedTokens) {
        public Boundary { retained = List.copyOf(retained); }
    }
    public Boundary compact(SessionMemorySnapshot snapshot, List<AgentMessage> messages, long fixedContextTokens, long threshold) {
        if (snapshot.body().isBlank() || snapshot.coveredThroughMessageId().isBlank()) throw new IllegalStateException("No usable session memory snapshot");
        int covered = -1;
        for (int i = 0; i < messages.size(); i++) if (snapshot.coveredThroughMessageId().equals(messages.get(i).id())) covered = i;
        if (covered < 0) throw new IllegalStateException("Memory cursor is not present in transcript");
        List<List<AgentMessage>> groups = groups(messages);
        int coveredGroup = -1;
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            if (group.stream().anyMatch(m -> m.id().equals(snapshot.coveredThroughMessageId())))
                coveredGroup = group.getLast().id().equals(snapshot.coveredThroughMessageId()) ? i : i - 1;
        }
        // Every not-yet-covered group is mandatory, even if it exceeds the target tail size.
        int start = coveredGroup + 1;
        long tokens = tailTokens(groups, start);
        long textMessages = textMessages(groups, start);
        long overhead = fixedContextTokens + TokenEstimator.text(snapshot.body());
        while (start > 0 && (tokens < 10_000 || textMessages < 5)) {
            long preceding = groupTokens(groups.get(start - 1));
            if (tokens + preceding > 40_000 || overhead + tokens + preceding >= threshold * 3 / 4) break;
            start--;
            tokens += preceding;
            textMessages = textMessages(groups, start);
        }
        // An oversized uncovered tail must not be silently deleted to make compaction appear successful.
        if (tokens > 40_000 || overhead + tokens >= threshold) throw new IllegalStateException("Context cannot be safely compacted using the current memory cursor");
        List<AgentMessage> retained = groups.subList(start, groups.size()).stream().flatMap(List::stream).toList();
        MessageIntegrity.validate(retained);
        return new Boundary(snapshot.revisionEdition(), snapshot.coveredThroughMessageId(), retained.isEmpty() ? "" : retained.getFirst().id(),
                snapshot.body(), retained, overhead + tokens);
    }
    private List<List<AgentMessage>> groups(List<AgentMessage> messages) {
        List<List<AgentMessage>> groups = new ArrayList<>();
        List<AgentMessage> current = new ArrayList<>();
        Set<String> pending = new HashSet<>();
        for (var message : messages) {
            current.add(message);
            for (var block : message.content()) {
                if (block.type() == AgentMessage.BlockType.TOOL_USE) pending.add(block.toolCallId());
                else if (block.type() == AgentMessage.BlockType.TOOL_RESULT) pending.remove(block.toolCallId());
            }
            if (pending.isEmpty()) { groups.add(List.copyOf(current)); current.clear(); }
        }
        if (!current.isEmpty()) throw new IllegalArgumentException("Compaction cannot split an unfinished tool group");
        return groups;
    }
    private long groupTokens(List<AgentMessage> group) {
        return group.stream().flatMap(m -> m.content().stream()).filter(b -> b.type()!=AgentMessage.BlockType.THINKING && b.type()!=AgentMessage.BlockType.PROGRESS).mapToLong(b ->
                b.type() == AgentMessage.BlockType.TOOL_USE ? TokenEstimator.toolJson(b.input().toString()) + TokenEstimator.text(b.name())
                        : b.type() == AgentMessage.BlockType.TOOL_RESULT ? TokenEstimator.toolJson(b.text()) : TokenEstimator.text(b.text())).sum();
    }
    private long tailTokens(List<List<AgentMessage>> groups, int start) { return groups.subList(start, groups.size()).stream().mapToLong(this::groupTokens).sum(); }
    private long textMessages(List<List<AgentMessage>> groups, int start) {
        return groups.subList(start, groups.size()).stream().flatMap(List::stream)
                .filter(m -> !m.text().isBlank() && m.role() != AgentMessage.Role.TOOL).count();
    }
}
