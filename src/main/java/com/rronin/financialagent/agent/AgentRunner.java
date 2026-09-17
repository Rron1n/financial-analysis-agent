package com.rronin.financialagent.agent;

import com.rronin.financialagent.model.AgentModels.AgentEvent;
import com.rronin.financialagent.model.AgentModels.Attachment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.function.Function;

/** Application entry point; the loop depends on ModelGateway, not a provider SDK/client. */
@Service
public class AgentRunner {
    private final AgentRunEngine engine;
    public AgentRunner(AgentRunEngine engine) { this.engine = engine; }
    public Flux<AgentEvent> run(String message) { return run(message, null, null); }
    public Flux<AgentEvent> run(String message, String skill, String scheduler) { return run(message, skill, scheduler, List.of()); }
    public Flux<AgentEvent> run(String message, String skill, String scheduler, List<Attachment> attachments) {
        return runSession(null, message, skill, scheduler, attachments);
    }
    public Flux<AgentEvent> runSession(String sessionId, String message, String skill, String scheduler, List<Attachment> attachments) {
        return engine.run(sessionId, message, skill, scheduler, attachments, BackgroundRunPolicy.none());
    }
    public Mono<String> runToCompletion(String message) { return runToCompletion(message, null, null, BackgroundRunPolicy.none()); }
    public Mono<String> runToCompletion(String message, String skill, String scheduler, BackgroundRunPolicy policy) {
        return completion(engine.run(null, message, skill, scheduler, List.of(), policy));
    }
    public Mono<String> resumeToCompletion(String sessionId) {
        return completion(engine.resume(sessionId));
    }
    public Mono<String> resumeToCompletion(String sessionId, BackgroundRunPolicy policy) {
        return completion(engine.resume(sessionId, policy));
    }
    public Mono<String> resumeToCompletion(String sessionId, BackgroundRunPolicy policy, Runnable onStarted) {
        return completion(engine.resume(sessionId, policy).doOnNext(event -> {
            if ("run.started".equals(event.type())) onStarted.run();
        }));
    }
    private Mono<String> completion(Flux<AgentEvent> events) {
        var runId = new java.util.concurrent.atomic.AtomicReference<String>();
        return events
                .doOnNext(event -> { if ("run.started".equals(event.type())) runId.set(event.runId()); })
                .handle((event, sink) -> {
                    if("run.interrupted".equals(event.type()))sink.error(new InterruptedRunException());
                    else if ("run.failed".equals(event.type()) || "run.cancelled".equals(event.type())) sink.error(new IllegalStateException(String.valueOf(event.data())));
                    else if ("final.delta".equals(event.type())) sink.next(String.valueOf(event.data()));
                }).cast(String.class).collectList().map(parts -> String.join("", parts))
                .doOnCancel(() -> { if (runId.get() != null) engine.cancel(runId.get()); });
    }
    public Mono<MidRunMessageQueue.QueuedMessage> enqueueUserMessage(String runId, String message) {
        return Mono.fromCallable(() -> engine.enqueue(runId, message)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    public String runProgress(String runId){return engine.runProgress(runId);}
    public boolean cancelFromMessage(String runId,String text){return engine.cancelFromMessage(runId,text);}
    public boolean cancel(String runId) { return engine.cancel(runId); }

    public static class SchedulerApprovalDeniedException extends RuntimeException {
        public SchedulerApprovalDeniedException(String message) { super(message); }
    }
    public static class InterruptedRunException extends RuntimeException {
        public InterruptedRunException(){super("Run interrupted by application shutdown; resume the original session");}
    }
    public static class MissingScheduledOutputException extends RuntimeException {
        public MissingScheduledOutputException(String message) { super(message); }
    }
    public record BackgroundRunPolicy(OutputPolicy outputPolicy,
                                      Function<ApprovalService.PendingApproval, Mono<ApprovalService.Decision>> approvalHandler,
                                      List<ApprovalRule> approvalRules, ApprovalRuleSource approvalTargetSource, String approvalScopeId) {
        public BackgroundRunPolicy { outputPolicy = OutputPolicy.normalize(outputPolicy); approvalRules = List.of(); }
        public static BackgroundRunPolicy none() { return new BackgroundRunPolicy(OutputPolicy.none(), null, List.of(), ApprovalRuleSource.USER_SETTINGS, null); }
    }
}
