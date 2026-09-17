package com.rronin.financialagent.memory;

import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.persistence.AtomicFileWriter;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Authoritative topic files; MEMORY.md is a bounded, derived map, never a second source of truth. */
@Component
public class TopicFileStore {
    public record Topic(String path, String title, String type, String description, String version, Instant updatedAt, String text, List<String> entities, List<String> tags) {
        public Topic { entities=List.copyOf(entities);tags=List.copyOf(tags); }
    }
    private final Path root;
    private final AtomicFileWriter writer = new AtomicFileWriter();
    public TopicFileStore(AgentProperties properties) { root = properties.memory().root().toAbsolutePath().normalize(); }
    public synchronized Path resolve(String path) {
        Path target = root.resolve(path).toAbsolutePath().normalize();
        if (!target.startsWith(root) || !root.relativize(target).toString().replace('\\', '/').matches("topics/(user|workflow|investment|research)/[A-Za-z0-9_-]+\\.md"))
            throw new IllegalArgumentException("Only typed topic Markdown files are editable");
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Symlink memory root");
        for (Path segment : root.relativize(target)) { cursor = cursor.resolve(segment); if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Symlink topic path"); }
        return target;
    }
    public synchronized String read(String path) throws Exception {
        Path file = resolve(path);
        return Files.exists(file) ? Files.readString(file) : "";
    }
    public synchronized List<Topic> list() throws Exception {
        List<Topic> topics = new ArrayList<>();
        Path folder = root.resolve("topics");
        if (!Files.exists(folder)) return topics;
        try (var files = Files.walk(folder, 3)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                try { topics.add(parse(path, read(path))); }
                catch (IllegalArgumentException error) { /* An invalid topic is excluded, never injected as trusted memory. */ }
            }
        }
        return topics.stream().sorted(Comparator.comparing(Topic::path)).toList();
    }
    public synchronized String index() throws Exception {
        StringBuilder text = new StringBuilder("# Memory Index\n");
        int lines = 1;
        for (var topic : list()) {
            String line = "- [" + oneLine(topic.title()) + "](" + topic.path() + ") — " + topic.type() + ": " + oneLine(topic.description()) + "\n";
            if (lines >= 200 || (text.toString() + line).getBytes(StandardCharsets.UTF_8).length > 25_000) break;
            text.append(line); lines++;
        }
        return text.toString();
    }
    public synchronized List<Topic> commit(Map<String, String> originals, Map<String, String> changes) throws Exception {
        for (var entry : changes.entrySet()) {
            if (!Objects.equals(read(entry.getKey()), originals.get(entry.getKey()))) throw new IllegalStateException("Topic changed after extraction read");
            parse(entry.getKey(), entry.getValue());
        }
        List<Topic> updated = new ArrayList<>();
        for (var entry : changes.entrySet()) {
            String text = entry.getValue();
            writer.write(resolve(entry.getKey()), text, true);
            updated.add(parse(entry.getKey(), text));
        }
        writer.write(root.resolve("MEMORY.md"), index(), true);
        return updated;
    }
    public Topic parse(String path, String text) throws Exception {
        resolve(path);
        if (text.length() > 50_000 || SessionMemoryService.containsCredential(text)) throw new IllegalArgumentException("Invalid topic size or credential content");
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("Topic front matter is required");
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("Unclosed topic front matter");
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        Object loaded = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
        if (!(loaded instanceof Map<?, ?> fields)) throw new IllegalArgumentException("Invalid topic front matter");
        String title = Objects.toString(fields.get("title"), ""), type = Objects.toString(fields.get("type"), ""), description = Objects.toString(fields.get("description"), "");
        if (title.isBlank() || description.isBlank() || !Set.of("user", "workflow", "investment", "research").contains(type) || !path.startsWith("topics/" + type + "/"))
            throw new IllegalArgumentException("Topic title, description and matching type are required");
        String version = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        Path file = resolve(path);
        Instant time = Files.exists(file) ? Files.getLastModifiedTime(file).toInstant() : Instant.now();
        return new Topic(path, title, type, description, version, time, text, values(fields.get("entities")), values(fields.get("tags")));
    }
    private static List<String> values(Object value){return value instanceof List<?> list?list.stream().map(Object::toString).toList():value==null?List.of():List.of(value.toString());}
    private String oneLine(String text) { return text.replaceAll("[\\r\\n\\[\\]]", " "); }
}
