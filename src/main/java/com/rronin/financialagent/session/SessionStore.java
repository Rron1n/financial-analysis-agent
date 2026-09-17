package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.agent.AgentLoopState.RunStatus;
import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.persistence.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Append-only events are authoritative; metadata is a replaceable session index. */
@Repository
public class SessionStore {
    private final ObjectMapper mapper;
    private final Path root;
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> deleting = ConcurrentHashMap.newKeySet();

    public SessionStore(ObjectMapper mapper, @Value("${agent.sessions-root:${user.home}/.financial-analysis-agent/sessions}") Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
    }

    public ObjectNode create(String title) throws IOException {
        String id = UUID.randomUUID().toString();
        synchronized (lock(id)) {
            Path folder = directory(id);
            Files.createDirectories(folder.resolve("runs"));
            Files.createDirectories(folder.resolve("tool-results"));
            ObjectNode meta = mapper.createObjectNode().put("schemaVersion", 1).put("sessionId", id)
                    .put("title", title == null || title.isBlank() ? "New Chat" : title.substring(0, Math.min(80, title.length())))
                    .put("createdAt", Instant.now().toString()).put("updatedAt", Instant.now().toString())
                    .put("messageCount", 0).put("status", "active").put("approvalMode", "DEFAULT");
            writer.write(folder.resolve("metadata.json"), mapper.writeValueAsString(meta), false);
            writer.write(folder.resolve("session-memory.md"), "---\nschemaVersion: 1\nrevisionEdition: 0\ncoveredThroughMessageId: \"\"\n---\n\n# Session Memory\n\n## Current State\n\n## Key Results\n\n## Open Questions\n", true);
            append(id, null, "session.created", meta);
            return meta;
        }
    }

    public Path directory(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid session id");
        Path folder = root.resolve(id);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(folder)) throw new IllegalArgumentException("Symlink session directory is not allowed");
        return folder;
    }
    public Object lock(String id) { return locks.computeIfAbsent(id, ignored -> new Object()); }
    public ObjectNode metadata(String id) throws IOException {
        synchronized (lock(id)) {
            ensureActive(id);
            return (ObjectNode) mapper.readTree(directory(id).resolve("metadata.json").toFile());
        }
    }
    public List<ObjectNode> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<ObjectNode> result = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path p : entries.toList()) {
                try { result.add(metadata(p.getFileName().toString())); }
                catch (IOException | IllegalArgumentException ignored) { /* One corrupt session cannot hide the other sessions. */ }
            }
        }
        result.sort(Comparator.comparing((ObjectNode n) -> n.path("updatedAt").asText()).reversed());
        return result;
    }
    public void updateMetadata(String id, java.util.function.Consumer<ObjectNode> update) throws IOException {
        synchronized (lock(id)) {
            ObjectNode meta = metadata(id);
            update.accept(meta);
            meta.put("updatedAt", Instant.now().toString());
            writer.write(directory(id).resolve("metadata.json"), mapper.writeValueAsString(meta), false);
        }
    }
    public void message(String id, AgentMessage message) throws IOException {
        synchronized (lock(id)) {
            append(id, message.runId(), "message.created", message);
            updateMetadata(id, meta -> {
                meta.put("lastMessageId", message.id());
                meta.put("messageCount", meta.path("messageCount").asInt() + 1);
            });
        }
    }
    public void status(String id, String runId, RunStatus status) throws IOException {
        synchronized (lock(id)) {
            append(id, runId, "run." + status.name().toLowerCase(Locale.ROOT), Map.of("status", status));
            updateMetadata(id, meta -> meta.put("lastRunId", runId).put("runStatus", status.name()));
        }
    }
    public void append(String id, String runId, String type, Object data) throws IOException {
        synchronized (lock(id)) {
            ensureActive(id);
            if (runId != null) UUID.fromString(runId);
            ObjectNode event = mapper.createObjectNode().put("schemaVersion", 1).put("eventId", UUID.randomUUID().toString())
                    .put("sessionId", id).put("runId", runId).put("type", type).put("timestamp", Instant.now().toString());
            event.set("data", mapper.valueToTree(data));
            String line = mapper.writeValueAsString(event) + "\n";
            appendLine(directory(id).resolve("transcript.jsonl"), line);
            if (runId != null) {
                appendLine(directory(id).resolve("runs").resolve(runId + ".jsonl"), line);
            }
        }
    }
    private void appendLine(Path path, String text) throws IOException {
        boolean partialTail = false;
        if (Files.exists(path)) {
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (file.size() > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    file.read(last, file.size() - 1);
                    partialTail = last.array()[0] != '\n';
                }
            }
        }
        // Normal appends inspect one byte, not the entire ever-growing transcript.
        if (partialTail) {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                int boundary = bytes.length - 1;
                while (boundary >= 0 && bytes[boundary] != '\n') boundary--;
                String tail = new String(bytes, boundary + 1, bytes.length - boundary - 1, StandardCharsets.UTF_8);
                boolean complete;
                try { complete = mapper.readTree(tail) != null; }
                catch (IOException ignored) { complete = false; }
                if (complete) text = "\n" + text;
                else {
                    // Retain crash debris for diagnostics, but never append a new event onto broken JSON.
                    writer.write(path.resolveSibling(path.getFileName() + ".partial-" + UUID.randomUUID()), tail, false);
                    try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        file.truncate(boundary + 1L);
                        file.force(true);
                    }
                }
            }
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
    }
    public List<JsonNode> events(String id) throws IOException {
        synchronized (lock(id)) {
            ensureActive(id);
            Path file = directory(id).resolve("transcript.jsonl");
            if (!Files.exists(file)) return List.of();
            String text = Files.readString(file);
            String[] lines = text.split("\n", -1);
            List<JsonNode> result = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                try { result.add(mapper.readTree(lines[i])); }
                catch (IOException error) {
                    // A crash may leave one partial tail. Interior corruption is not silently discarded.
                    if (i != lines.length - 1 || text.endsWith("\n")) throw error;
                }
            }
            return result;
        }
    }
    public List<AgentMessage> messages(String id) throws IOException {
        List<AgentMessage> messages = new ArrayList<>();
        for (JsonNode event : events(id)) {
            if ("message.created".equals(event.path("type").asText()))
                messages.add(mapper.treeToValue(event.path("data"), AgentMessage.class));
        }
        return messages;
    }
    public Map<String, Object> history(String id) throws IOException {
        synchronized (lock(id)) {
            var messages = new ArrayList<AgentMessage>();
            var latestAssistant = new HashMap<String, String>();
            var accepted = new LinkedHashSet<String>();
            var cancelledPartials = new LinkedHashSet<String>();
            var failures = new LinkedHashMap<String, String>();
            var runs = new LinkedHashMap<String, Map<String,Object>>();
            var legacyWork = new LinkedHashMap<String,List<JsonNode>>();
            var toolNames = new HashMap<String,String>();
            var workEvents = new LinkedHashMap<String,List<JsonNode>>();
            var hiddenRuns=metadata(id).path("hiddenRunIds");
            for (JsonNode event : events(id)) {
                String runId = event.path("runId").asText();
                boolean hidden=false;for(var hiddenRun:hiddenRuns)if(hiddenRun.asText().equals(runId))hidden=true;
                if(hidden)continue;
                if("work.event".equals(event.path("type").asText()))workEvents.computeIfAbsent(runId,k->new ArrayList<>()).add(event.path("data"));
                var legacy=legacyWork.computeIfAbsent(runId,k->new ArrayList<>());
                String type=event.path("type").asText();var data=event.path("data");
                if(type.equals("tool.started")){toolNames.put(data.path("toolCallId").asText(),data.path("name").asText());legacy.add(event);}
                if(type.equals("tool.finished")){
                    var copy=event.deepCopy();var value=mapper.createObjectNode().put("toolCallId",data.path("toolCallId").asText()).put("name",toolNames.getOrDefault(data.path("toolCallId").asText(),"Tool")).put("success",data.path("result").path("success").asBoolean());
                    ((ObjectNode)copy).set("data",value);legacy.add(copy);
                }
                if(type.equals("audit.completed")){var copy=event.deepCopy();((ObjectNode)copy).set("data",data.path("review"));legacy.add(copy);}
                if(type.equals("message.created")&&data.path("role").asText().equals("ASSISTANT")&&!data.path("meta").asBoolean()){
                    boolean hasTool=false;for(var block:data.path("content"))if(block.path("type").asText().equals("TOOL_USE"))hasTool=true;
                    for(var block:data.path("content"))if(block.path("type").asText().equals("PROGRESS")||(hasTool&&block.path("type").asText().equals("TEXT"))){
                        legacy.add(mapper.valueToTree(Map.of("type","model.message_start","data",Map.of())));
                        legacy.add(mapper.valueToTree(Map.of("type","model.text_delta","data","<progress>"+block.path("text").asText()+"</progress>")));
                        legacy.add(mapper.valueToTree(Map.of("type","model.message_stop","data",Map.of())));
                    }
                }
                if (!runId.isBlank()) {
                    var info=runs.computeIfAbsent(runId,key->new LinkedHashMap<>());
                    info.putIfAbsent("startedAt",event.path("timestamp").asText());
                    if (java.util.Set.of("run.completed","run.failed","run.cancelled").contains(event.path("type").asText())) {
                        info.put("endedAt",event.path("timestamp").asText());info.put("status",event.path("type").asText());
                    }
                }
                if ("message.cancelled_partial".equals(event.path("type").asText()))cancelledPartials.add(event.path("data").path("messageId").asText());
                if ("failure.recorded".equals(event.path("type").asText())) failures.put(runId,event.path("data").path("message").asText("Run failed"));
                if ("message.created".equals(event.path("type").asText())) {
                    var message = mapper.treeToValue(event.path("data"), AgentMessage.class);
                    messages.add(message);
                    if (message.role() == AgentMessage.Role.ASSISTANT && !message.meta() && !message.text().isBlank())
                        latestAssistant.put(runId, message.id());
                } else if ("run.completed".equals(event.path("type").asText()) && latestAssistant.containsKey(runId)) {
                    accepted.add(latestAssistant.get(runId));
                }
            }
            legacyWork.forEach((run,items)->{if(!items.isEmpty())workEvents.putIfAbsent(run,items);});
            return Map.of("workEvents",workEvents,"messages", messages, "cancelledPartialMessageIds",cancelledPartials,"acceptedMessageIds", accepted, "failures", failures, "runs", runs, "metadata", metadata(id));
        }
    }
    public Path spill(String id, String callId, Object result) throws IOException {
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid tool call id");
        synchronized (lock(id)) {
            ensureActive(id);
            Path file = directory(id).resolve("tool-results").resolve(callId + ".json");
            writer.write(file, mapper.writeValueAsString(result), false);
            return file;
        }
    }
    public void audit(String id, String runId, Object result) throws IOException {
        UUID.fromString(runId);
        synchronized (lock(id)) {
            ensureActive(id);
            writer.write(directory(id).resolve("runs").resolve(runId + ".audit.json"), mapper.writeValueAsString(result), false);
        }
    }
    public Path trash(String id) throws IOException {
        synchronized (lock(id)) {
            ensureActive(id);
            deleting.add(id);
            try {
                Path trash = Path.of(System.getProperty("user.home"), ".Trash");
                if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"))
                    throw new IOException("OS trash is not configured on this platform; session was not deleted");
                Files.createDirectories(trash);
                Path destination = trash.resolve("financial-session-" + id + "-" + Instant.now().toEpochMilli());
                Files.move(directory(id), destination);
                return destination;
            } catch (IOException error) {
                deleting.remove(id);
                throw error;
            }
        }
    }
    private void ensureActive(String id) throws IOException {
        if (deleting.contains(id) || !Files.isRegularFile(directory(id).resolve("metadata.json")))
            throw new NoSuchFileException("Session is unavailable: " + id);
    }
}
