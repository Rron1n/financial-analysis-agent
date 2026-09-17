package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.ApprovalBehaviour;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Optional;

/** Generic local proxy, not a copy of or hardcoded wrapper around any broker function. */
public final class McpTool implements AgentTool {
    private final String server, remoteName, publicName;
    private final JsonNode definition;
    private final ExternalApiClient client;
    private final McpSafetyRegistry safety;
    private final ObjectMapper mapper;
    public McpTool(String server, JsonNode definition, ExternalApiClient client, McpSafetyRegistry safety, ObjectMapper mapper) {
        this.server = server; this.definition = definition.deepCopy(); this.client = client; this.safety = safety; this.mapper = mapper;
        remoteName = definition.path("name").asText();
        if (!server.matches("[A-Za-z0-9_-]+") || remoteName.isBlank()) throw new IllegalArgumentException("Invalid MCP tool name");
        String candidate = "mcp__" + server + "__" + remoteName.replaceAll("[^A-Za-z0-9_-]", "_");
        publicName = candidate.length() <= 64 ? candidate : candidate.substring(0, 47) + "_" + Integer.toUnsignedString(candidate.hashCode(), 16);
    }
    public String name() { return publicName; }
    public String description() { return definition.path("description").asText("MCP tool from " + server); }
    public JsonNode inputSchema() { return definition.path("inputSchema").deepCopy(); }
    public JsonNode outputSchema() { return definition.has("outputSchema") ? definition.path("outputSchema").deepCopy() : null; }
    public Mode mode() { return requiresUserInteraction() ? Mode.INTERACTIVE : isReadOnly(null) ? Mode.READ_ONLY : Mode.MUTATING; }
    public boolean requiresUserInteraction() { return safety.requiresUserInteraction(server, remoteName) || isDestructive(null); }
    public boolean isReadOnly(JsonNode input) { return definition.path("annotations").path("readOnlyHint").asBoolean(false); }
    public boolean isDestructive(JsonNode input) { return definition.path("annotations").path("destructiveHint").asBoolean(false); }
    public boolean isOpenWorld(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return isReadOnly(input) && !isDestructive(input); }
    public InterruptBehavior interruptBehavior() { return isConcurrencySafe(null) ? InterruptBehavior.CANCEL : InterruptBehavior.BLOCK; }
    public SensitivityAssessment sensitivityAssessment(JsonNode input, ApprovalContext context) {
        if("ibkr".equalsIgnoreCase(server))return new SensitivityAssessment(Sensitivity.YES,"HIGH",java.util.List.of("FINANCIAL_ACCOUNT"),
                isReadOnly(input)?"MCP_SERVER":"UNKNOWN",isReadOnly(input)?"SESSION":"MCP_SERVER",
                "Broker tool may read or transmit account and portfolio information; exact fields depend on this invocation.");
        return AgentTool.super.sensitivityAssessment(input,context);
    }
    public Optional<ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        if (isReadOnly(input) && !isDestructive(input)) return Optional.of(ToolSafety.publicReadAdvice(input));
        return Optional.empty();
    }
    public Mono<ToolResult> execute(JsonNode input) {
        return client.ibkrCall("tools/call", mapper.valueToTree(Map.of("name", remoteName, "arguments", input)))
                .map(response -> {
                    if (response.has("error") || !response.has("result")) return ToolResult.error(name(), "MCP_ERROR", "MCP request did not return a valid result");
                    JsonNode result = response.path("result");
                    return new ToolResult(name(), !result.path("isError").asBoolean(false), result, Map.of("server", server, "remoteName", remoteName));
                });
    }
}
