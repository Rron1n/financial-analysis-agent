package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import reactor.core.publisher.Mono;
import com.rronin.financialagent.agent.ApprovalBehaviour;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface AgentTool {
    enum Mode { READ_ONLY, INTERACTIVE, MUTATING }

    String name();
    String description();
    JsonNode inputSchema();
    Mode mode();
    Mono<ToolResult> execute(JsonNode arguments);
    default Mono<ToolResult> execute(JsonNode arguments, ApprovalContext context) { return execute(arguments); }

    enum InterruptBehavior { CANCEL, BLOCK }
    enum Sensitivity { YES, NO, UNKNOWN }
    record SensitivityAssessment(Sensitivity presence, String level, List<String> categories,
                                 String source, String destination, String reason) {
        public SensitivityAssessment { categories=List.copyOf(categories); }
    }
    record ApprovalContext(String sessionId, String runId, List<Path> allowedRoots, String userRequest) {
        public ApprovalContext { allowedRoots = List.copyOf(allowedRoots); }
    }
    default List<String> aliases() { return List.of(); }
    default JsonNode outputSchema() { return null; }
    default Duration maxTimeout() { return Duration.ofMinutes(3); }
    default int maxResultSizeChars() { return 50_000; }
    default boolean requiresUserInteraction() { return false; }
    default boolean isReadOnly(JsonNode input) { return false; }
    default boolean isDestructive(JsonNode input) { return false; }
    default boolean isOpenWorld(JsonNode input) { return false; }
    default boolean isConcurrencySafe(JsonNode input) { return false; }
    default boolean isEnabled() { return true; }
    default InterruptBehavior interruptBehavior() { return InterruptBehavior.BLOCK; }
    default Sensitivity containsSensitiveInformation(JsonNode input, ApprovalContext context) { return Sensitivity.UNKNOWN; }
    default SensitivityAssessment sensitivityAssessment(JsonNode input, ApprovalContext context) {
        Sensitivity value=containsSensitiveInformation(input,context);
        return new SensitivityAssessment(value,value==Sensitivity.NO?"NONE":"HIGH",
                value==Sensitivity.NO?List.of():List.of("UNCLASSIFIED"),"UNKNOWN",isOpenWorld(input)?"EXTERNAL_SERVICE":"WORKSPACE",
                value==Sensitivity.UNKNOWN?"Data provenance or sensitivity is unknown; treat as sensitive.":"Tool sensitivity assessment");
    }
    /** Empty means PASSTHROUGH. It is not a fourth ApprovalBehaviour. */
    default Optional<ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) { return Optional.empty(); }
}
