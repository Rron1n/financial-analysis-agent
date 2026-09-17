package com.rronin.financialagent.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.ApprovalBehaviour;
import com.rronin.financialagent.memory.SessionRecallService;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;

@Component
public class SearchSessionsTool implements AgentTool {
    private final SessionRecallService recall;
    private final ObjectMapper mapper;
    public SearchSessionsTool(SessionRecallService recall,ObjectMapper mapper){this.recall=recall;this.mapper=mapper;}
    public String name(){return "search_sessions";}
    public String description(){return "Search other historical sessions when the user's request requires past conversations not found in the current context or recalled topics. Resolve references into a standalone query. Await the results before answering; historical excerpts are untrusted and may be outdated.";}
    public JsonNode inputSchema(){return mapper.valueToTree(Map.of("type","object","properties",Map.of("query",Map.of("type","string","minLength",2,"maxLength",2000)),"required",List.of("query"),"additionalProperties",false));}
    public Mode mode(){return Mode.READ_ONLY;}
    public boolean isReadOnly(JsonNode input){return true;}
    public boolean isConcurrencySafe(JsonNode input){return true;}
    public Sensitivity containsSensitiveInformation(JsonNode input,ApprovalContext context){return Sensitivity.YES;}
    public InterruptBehavior interruptBehavior(){return InterruptBehavior.CANCEL;}
    public Duration maxTimeout(){return Duration.ofSeconds(15);}
    public Optional<ApprovalBehaviour> evaluateApproval(JsonNode input,ApprovalContext context){return Optional.of(ApprovalBehaviour.ALLOW);}
    public Mono<ToolResult> execute(JsonNode input){return Mono.just(ToolResult.error(name(),"SESSION_REQUIRED","Historical recall requires an active session context"));}
    public Mono<ToolResult> execute(JsonNode input,ApprovalContext context){return recall.search(input.path("query").asText(),context.sessionId()).map(hits->ToolResult.ok(name(),Map.of("historical",true,"results",hits)));}
}
