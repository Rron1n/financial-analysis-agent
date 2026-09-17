package com.rronin.financialagent.tools.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.schedulers.ScheduledPromptService;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ListSchedulersTool implements AgentTool {
    private final ScheduledPromptService service;
    private final com.rronin.financialagent.schedulers.TemporarySchedulerService temporary;
    private final ObjectMapper mapper;
    public ListSchedulersTool(ScheduledPromptService service, com.rronin.financialagent.schedulers.TemporarySchedulerService temporary, ObjectMapper mapper) { this.service = service; this.temporary=temporary; this.mapper = mapper; }
    public String name() { return "list_schedulers"; }
    public String description() { return "List the user's scheduled prompt tasks, including frequency, next run time and status."; }
    public JsonNode inputSchema() { return mapper.createObjectNode().put("type", "object").set("properties", mapper.createObjectNode()); }
public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return false; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }
    public Mono<ToolResult> execute(JsonNode arguments) { return Mono.just(ToolResult.ok(name(), java.util.Map.of("persistent",service.list(),"temporary",temporary.list()))); }
}
