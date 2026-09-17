package com.rronin.financialagent.controller;

import com.rronin.financialagent.agent.AgentRunner;
import com.rronin.financialagent.agent.ApprovalService;
import com.rronin.financialagent.model.AgentModels.AgentEvent;
import com.rronin.financialagent.model.AgentModels.RunRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentRunner runner;
    private final ApprovalService approvals;
    public AgentController(AgentRunner runner, ApprovalService approvals) {
        this.runner = runner;
        this.approvals = approvals;
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> run(@Valid @RequestBody RunRequest request) {
        return runner.runSession(request.sessionId(), request.message(), request.skill(), request.scheduler(), request.attachments());
    }

    @PostMapping("/runs/{runId}/messages")
    public reactor.core.publisher.Mono<java.util.Map<String, Object>> appendMessage(@PathVariable String runId, @RequestBody java.util.Map<String, String> body) {
        String text=body.getOrDefault("message", "");
        if(com.rronin.financialagent.agent.RunControlIntent.stop(text))return reactor.core.publisher.Mono.fromCallable(() -> java.util.Map.<String,Object>of("queued",false,"stopped",runner.cancelFromMessage(runId,text))).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        if(com.rronin.financialagent.agent.RunControlIntent.status(text))return reactor.core.publisher.Mono.just(java.util.Map.of("queued",false,"progress",runner.runProgress(runId)));
        return runner.enqueueUserMessage(runId, body.getOrDefault("message", "")).map(message -> java.util.Map.<String,Object>of("queued", true, "id", message.id()));
    }

    @PostMapping("/approvals/{approvalId}")
    public java.util.Map<String, Object> decideApproval(@PathVariable String approvalId, @RequestBody java.util.Map<String, String> body) {
        String raw = body.getOrDefault("decision", "deny").toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        ApprovalService.Decision decision = switch (raw) {
            case "ALLOW_ONCE" -> ApprovalService.Decision.ALLOW_ONCE;
            case "ALLOW_SESSION", "ALLOW_FOR_SESSION" -> ApprovalService.Decision.ALLOW_SESSION;
            case "ALLOW_ALWAYS" -> ApprovalService.Decision.ALLOW_ALWAYS;
            default -> ApprovalService.Decision.DENY;
        };
        return java.util.Map.of("accepted", approvals.decide(approvalId, decision));
    }

    @PostMapping("/runs/{runId}/cancel")
    public java.util.Map<String, Object> cancel(@PathVariable String runId) {
        return java.util.Map.of("accepted", runner.cancel(runId));
    }
}
