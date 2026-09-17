package com.rronin.financialagent.agent;

import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.session.SessionStore;
import java.io.IOException;
import java.util.*;
import static com.rronin.financialagent.config.TokenBudgetPolicy.*;

/** Counts distinct run results, not retransmission of the same context on subsequent model requests. */
public final class ToolResultBudget {
    private record Entry(String full, int limit, boolean error) { }
    private final Map<String, Entry> results = new LinkedHashMap<>();
    private final Set<String> compactedCalls=new HashSet<>();
    private final Map<String, String> paths = new HashMap<>();
    private final SessionStore store;
    private final String sessionId;
    public ToolResultBudget(SessionStore store, String sessionId) { this.store = store; this.sessionId = sessionId; }
    public void add(String callId, String full, int toolLimit, boolean error) {
        if (results.putIfAbsent(callId, new Entry(full, Math.min(TOOL_RESULT_CHARS, toolLimit), error)) != null)
            throw new IllegalArgumentException("Duplicate tool result id");
    }
    public List<AgentMessage> fit(List<AgentMessage> messages) throws IOException {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (var entry : results.entrySet()) rendered.put(entry.getKey(), entry.getValue().full().length() > entry.getValue().limit()
                ? preview(entry.getKey(), 2048) : entry.getValue().full());
        for (String id : results.keySet().stream().sorted(Comparator.comparingInt((String key) -> results.get(key).full().length()).reversed()).toList()) {
            if (size(rendered) <= RUN_TOOL_RESULT_CHARS) break;
            String preview = preview(id, 2048);
            if (preview.length() < rendered.get(id).length()) rendered.put(id, preview);
        }
        if (size(rendered) > RUN_TOOL_RESULT_CHARS) {
            for (String id : rendered.keySet()) {
                if (size(rendered) <= RUN_TOOL_RESULT_CHARS) break;
                String small = preview(id, 0);
                if (small.length() < rendered.get(id).length()) rendered.put(id, small);
            }
        }
        if (size(rendered) > RUN_TOOL_RESULT_CHARS) throw new IllegalStateException("Too many tool result references for the run context budget");
        List<AgentMessage> fitted = new ArrayList<>();
        for (var message : messages) {
            List<AgentMessage.Block> blocks = message.content().stream().map(block -> {
                if (block.type() != AgentMessage.BlockType.TOOL_RESULT || !rendered.containsKey(block.toolCallId())) return block;
                return AgentMessage.Block.toolResult(block.toolCallId(), rendered.get(block.toolCallId()), block.error());
            }).toList();
            fitted.add(new AgentMessage(message.id(), message.runId(), message.role(), message.meta(), message.createdAt(), blocks));
        }
        return reduceResults(fitted,false);
    }
    /** Preserve pairing and the complete source while reducing an old transcript's large payloads. */
    public List<AgentMessage> compactResults(List<AgentMessage> messages) throws IOException {
        return reduceResults(messages,true);
    }
    private List<AgentMessage> reduceResults(List<AgentMessage> messages,boolean capture) throws IOException {
        var compacted=new ArrayList<AgentMessage>();
        for(var message:messages){var blocks=new ArrayList<AgentMessage.Block>();
            for(var block:message.content()){
                if(capture&&block.type()==AgentMessage.BlockType.TOOL_RESULT)compactedCalls.add(block.toolCallId());
                if(compactedCalls.contains(block.toolCallId())&&block.type()==AgentMessage.BlockType.TOOL_RESULT&&block.text()!=null&&block.text().length()>1000&&!block.text().startsWith("[Full tool result:")){
                    String path=store.spill(sessionId,block.toolCallId(),Map.of("toolCallId",block.toolCallId(),"content",block.text(),"error",block.error())).toString();
                    blocks.add(AgentMessage.Block.toolResult(block.toolCallId(),"[Full tool result: "+path+"; original characters="+block.text().length()+"]\n"+block.text().substring(0,256),block.error()));
                }else blocks.add(block);
            }
            compacted.add(new AgentMessage(message.id(),message.runId(),message.role(),message.meta(),message.createdAt(),blocks));
        }
        return compacted;
    }
    private String preview(String id, int maxChars) throws IOException {
        Entry entry = results.get(id);
        String path = paths.get(id);
        if (path == null) {
            path = store.spill(sessionId, id, Map.of("toolCallId", id, "content", entry.full(), "error", entry.error())).toString();
            paths.put(id, path);
        }
        return "[Full tool result: " + path + "; original characters=" + entry.full().length() + "]\n"
                + entry.full().substring(0, Math.min(maxChars, entry.full().length()));
    }
    private long size(Map<String, String> values) { return values.values().stream().mapToLong(String::length).sum(); }
}
