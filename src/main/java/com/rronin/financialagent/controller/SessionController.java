package com.rronin.financialagent.controller;

import com.rronin.financialagent.agent.AgentRunEngine;
import com.rronin.financialagent.agent.ApprovalMode;
import com.rronin.financialagent.agent.ApprovalService;
import com.rronin.financialagent.session.SessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionStore store;
    private final AgentRunEngine engine;
    private final ApprovalService approvals;
    public SessionController(SessionStore store, AgentRunEngine engine, ApprovalService approvals) { this.store = store; this.engine = engine; this.approvals = approvals; }
    @GetMapping public Mono<?> list() { return io(store::list); }
    @PostMapping public Mono<?> create(@RequestBody(required = false) Map<String, String> input) { return io(() -> {var session=store.create(input == null ? "New Chat" : input.get("title"));engine.initializeSession(session.path("sessionId").asText());return store.metadata(session.path("sessionId").asText());}); }
    @GetMapping("/{sessionId}/messages") public Mono<?> messages(@PathVariable String sessionId) { return io(() -> store.messages(sessionId)); }
    @GetMapping("/{sessionId}/history") public Mono<?> history(@PathVariable String sessionId) { return io(() -> {var history=new java.util.LinkedHashMap<String,Object>(store.history(sessionId));history.put("queuedMessages",engine.queuedMessages(sessionId));return history;}); }
    @PostMapping(value = "/{sessionId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<?> resume(@PathVariable String sessionId) { return engine.resume(sessionId); }
    @GetMapping("/{sessionId}/approvals") public Mono<?> approvals(@PathVariable String sessionId) {
        return io(() -> { store.metadata(sessionId); return approvals.pendingForSession(sessionId); });
    }
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<?> events(@PathVariable String sessionId) { return io(() -> store.metadata(sessionId)).thenMany(engine.events(sessionId)); }
    @PatchMapping("/{sessionId}") public Mono<?> update(@PathVariable String sessionId, @RequestBody Map<String, String> input) {
        return io(() -> {
            String primary = input.get("primaryModel");
            if(primary != null && !java.util.Set.of("gpt-5.6-sol","qwen3.8-max").contains(primary))throw new IllegalArgumentException("Unsupported primary model");
            if(primary != null && engine.active(sessionId))throw new IllegalStateException("Change model after the current run finishes");
            String mode = input.get("approvalMode");
            if (mode != null) ApprovalMode.valueOf(mode);
            store.updateMetadata(sessionId, meta -> {
                if (input.containsKey("title")) meta.put("title", input.get("title"));
                if (mode != null) meta.put("approvalMode", mode);
                if (primary != null) meta.put("primaryModel", primary);
            });
            return store.metadata(sessionId);
        });
    }
    @DeleteMapping("/{sessionId}/queued/{messageId}") public Mono<?> deleteQueued(@PathVariable String sessionId,@PathVariable String messageId) {
        return io(() -> Map.of("deleted",engine.deleteQueued(sessionId,messageId)));
    }
    @DeleteMapping("/{sessionId}") public Mono<?> delete(@PathVariable String sessionId) {
        return io(() -> { store.updateMetadata(sessionId, meta -> meta.put("status", "deleting")); engine.cancelSession(sessionId); return true; })
                .then(Flux.interval(Duration.ZERO, Duration.ofMillis(100)).filter(ignored -> !engine.active(sessionId)).next()
                        .timeout(Duration.ofSeconds(30)).onErrorMap(error -> new ResponseStatusException(HttpStatus.CONFLICT, "A non-interruptible tool is still finishing; session has not been removed. Retry deletion later.")))
                .then(io(() -> {
                    var destination = store.trash(sessionId);
                    engine.clearSession(sessionId);
                    return Map.of("deleted", true, "recoverable", true, "trashPath", destination.toString());
                })).onErrorResume(error -> io(() -> {
                    // A failed Trash move must not leave the session permanently marked as deleting.
                    try { store.updateMetadata(sessionId, meta -> meta.put("status", "active")); } catch (Exception ignored) { }
                    if (error instanceof ResponseStatusException response) throw response;
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Session retained: unable to move it to the system Trash. Check application filesystem permissions and retry.", error);
                }));
    }
    private <T> Mono<T> io(java.util.concurrent.Callable<T> action) { return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()); }
}
