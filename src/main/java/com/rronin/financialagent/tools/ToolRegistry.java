package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools;
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public ToolRegistry(@org.springframework.beans.factory.annotation.Qualifier("builtinTools") List<AgentTool> tools, ObjectMapper mapper, AgentProperties properties) {
        this.tools = new LinkedHashMap<>();
        tools.forEach(tool -> { new ToolInputValidator().validateSchema(tool.inputSchema()); this.tools.putIfAbsent(tool.name(), tool); });
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<String> names() {
        return snapshot().stream().map(AgentTool::name).toList();
    }

    public synchronized List<AgentTool> snapshot() { return tools.values().stream().filter(AgentTool::isEnabled).toList(); }
    public synchronized AgentTool find(String name) {
        AgentTool exact = tools.get(name);
        if (exact != null && exact.isEnabled()) return exact;
        return tools.values().stream().filter(AgentTool::isEnabled).filter(t -> t.aliases().contains(name)).findFirst().orElse(null);
    }
    public synchronized void replaceMcpTools(String serverName, List<AgentTool> discovered) {
        String prefix = "mcp__" + serverName + "__";
        var validated = new LinkedHashMap<String, AgentTool>();
        for (AgentTool tool : discovered) {
            new ToolInputValidator().validateSchema(tool.inputSchema());
            if (!tool.name().startsWith(prefix)) throw new IllegalArgumentException("MCP tool server namespace mismatch");
            if (validated.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("Duplicate MCP tool name");
        }
        tools.keySet().removeIf(name -> name.startsWith(prefix));
        tools.putAll(validated);
    }

    public List<ObjectNode> definitions() {
        return snapshot().stream().map(tool -> {
            return definitionFor(tool);
        }).toList();
    }

    public List<ObjectNode> definitionsFor(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        return names.stream()
                .map(this::find)
                .filter(tool -> tool != null)
                .map(this::definitionFor)
                .toList();
    }

    private ObjectNode definitionFor(AgentTool tool) {
            ObjectNode function = mapper.createObjectNode();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.inputSchema());
            ObjectNode definition = mapper.createObjectNode();
            definition.put("type", "function");
            definition.set("function", function);
            return definition;
    }

    public AgentTool.Mode modeOf(String name) {
        AgentTool tool = find(name);
        return tool == null ? AgentTool.Mode.INTERACTIVE : tool.mode();
    }

    public Mono<ToolResult> execute(String name, JsonNode arguments) {
        AgentTool tool = find(name);
        if (tool == null) return Mono.just(ToolResult.error(name, "UNKNOWN_TOOL", "Tool is not registered"));
        return tool.execute(arguments)
                .onErrorResume(error -> Mono.just(ToolResult.error(name, "TOOL_ERROR", error.getMessage())));
    }

    private ToolResult limit(ToolResult result) {
        try {
            String json = mapper.writeValueAsString(result.data());
            int limit = properties.maxToolResultChars();
            if (json.length() <= limit) return result;
            return new ToolResult(result.tool(), result.success(),
                    Map.of("truncated", true, "preview", json.substring(0, limit)),
                    Map.of("originalChars", json.length()));
        } catch (Exception error) {
            return ToolResult.error(result.tool(), "SERIALIZATION_ERROR", error.getMessage());
        }
    }
}
