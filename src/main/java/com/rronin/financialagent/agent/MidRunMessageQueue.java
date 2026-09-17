package com.rronin.financialagent.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MidRunMessageQueue {
    public record QueuedMessage(String id, String message) { }
    private final Map<String, List<QueuedMessage>> queued = new ConcurrentHashMap<>();

    public void append(String runId, String message) {
        if (runId == null || runId.isBlank() || message == null || message.isBlank()) return;
        queued.compute(runId, (key, existing) -> {
            List<QueuedMessage> messages = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            messages.add(new QueuedMessage(java.util.UUID.randomUUID().toString(), message.trim()));
            return List.copyOf(messages);
        });
    }

    public List<QueuedMessage> peek(String runId) { return queued.getOrDefault(runId, List.of()); }
    public void clear(String id) { queued.remove(id); }
    public void restore(String id, QueuedMessage message) {
        queued.compute(id, (key, existing) -> {
            List<QueuedMessage> messages = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            if (messages.stream().noneMatch(value -> value.id().equals(message.id()))) messages.add(message);
            return List.copyOf(messages);
        });
    }

    /** Acknowledge only the snapshot already persisted and incorporated into a valid context. */
    public void acknowledge(String runId, List<String> messageIds) {
        var ids = java.util.Set.copyOf(messageIds);
        queued.computeIfPresent(runId, (key, messages) -> {
            List<QueuedMessage> remaining = messages.stream().filter(message -> !ids.contains(message.id())).toList();
            return remaining.isEmpty() ? null : remaining;
        });
    }

}
