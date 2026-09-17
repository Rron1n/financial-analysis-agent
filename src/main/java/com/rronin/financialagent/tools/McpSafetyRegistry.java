package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.persistence.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Safety properties are not ApprovalRules and do not create an additional rule source. */
@Component
public class McpSafetyRegistry {
    private final Map<String, Set<String>> requiresHuman = new ConcurrentHashMap<>();
    private final Set<String> configured = ConcurrentHashMap.newKeySet();
    private final Path file;
    private final ObjectMapper mapper;
    public McpSafetyRegistry(ObjectMapper mapper,
            @Value("${agent.mcp-safety-file:${user.home}/.financial-analysis-agent/mcp-tool-safety.json}") Path file) {
        this.mapper = mapper; this.file = file;
        try {
            if (Files.exists(file)) {
                JsonNode root = mapper.readTree(file.toFile());
                JsonNode names = root.path("requiresUserInteraction");
                if (!names.isArray()) throw new IllegalArgumentException("Invalid MCP safety configuration");
                for (JsonNode name : names) {
                    if (!name.isTextual() || !name.asText().startsWith("mcp__")) throw new IllegalArgumentException("Invalid MCP safety tool name");
                    configured.add(name.asText());
                }
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot load MCP safety configuration", error); }
    }
    public synchronized void replaceServerTools(String serverName, JsonNode response) {
        JsonNode tools = response.path("result").path("tools");
        if (!tools.isArray()) tools = response.path("tools");
        if (!tools.isArray()) throw new IllegalArgumentException("MCP discovery returned no tool list");
        Set<String> names = ConcurrentHashMap.newKeySet();
        for (JsonNode tool : tools) {
            if (tool.path("annotations").path("destructiveHint").asBoolean(false)) names.add(tool.path("name").asText());
        }
        requiresHuman.put(serverName, Set.copyOf(names));
        var updated = new java.util.TreeSet<>(configured);
        names.forEach(name -> updated.add("mcp__" + serverName + "__" + name));
        if (!updated.equals(configured)) setConfiguredNames(updated);
    }
    public boolean requiresUserInteraction(String server, String tool) {
        return configured.contains("mcp__" + server + "__" + tool) || requiresHuman.getOrDefault(server, Set.of()).contains(tool);
    }
    public synchronized void setConfiguredNames(Set<String> names) {
        var updated = Set.copyOf(names);
        if (updated.stream().anyMatch(name -> !name.startsWith("mcp__"))) throw new IllegalArgumentException("Invalid MCP safety tool name");
        try {
            new AtomicFileWriter().write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("schemaVersion", 1, "requiresUserInteraction", new java.util.TreeSet<>(updated))), false);
            configured.clear(); configured.addAll(updated);
        } catch (Exception error) { throw new IllegalStateException("Cannot persist MCP safety configuration", error); }
    }
    // Disconnection must never erase safety classifications learned from the server.
    public void disconnect(String server) { }
}
