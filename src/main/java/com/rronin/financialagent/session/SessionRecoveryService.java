package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.*;
import com.rronin.financialagent.model.AgentMessage;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.*;

/** Restores durable conversational state on startup, without invoking any external tool. */
@Service
public class SessionRecoveryService {
    private final SessionStore sessions;
    private final ApprovalService approvals;
    private final MidRunMessageQueue queue;
    private final ObjectMapper mapper;
    public SessionRecoveryService(SessionStore sessions, ApprovalService approvals, MidRunMessageQueue queue, ObjectMapper mapper) {
        this.sessions = sessions; this.approvals = approvals; this.queue = queue; this.mapper = mapper;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() throws Exception {
        for (var metadata : sessions.list()) {
            String id = metadata.path("sessionId").asText();
            try { recoverSession(id); }
            catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Session recovery requires attention for {} ({})", id, error.getClass().getSimpleName());
            }
        }
    }
    public void recoverSession(String id) throws Exception {
        synchronized (sessions.lock(id)) {
            var events = sessions.events(id);
            var messages = sessions.messages(id);
            Set<String> consumed = new HashSet<>();
            messages.forEach(message -> consumed.add(message.id()));
            events.stream().filter(event -> "message.queue_deleted".equals(event.path("type").asText())).forEach(event -> consumed.add(event.path("data").path("id").asText()));
            for (var event : events) {
                if (!"message.queued".equals(event.path("type").asText())) continue;
                var pending = mapper.treeToValue(event.path("data"), MidRunMessageQueue.QueuedMessage.class);
                if (!consumed.contains(pending.id())) queue.restore(id, pending);
            }
            var plan = new InterruptedRunRecovery().plan(events, messages);
            if (plan.isEmpty()) return;
            var recovered = plan.get();
            Set<String> existing = new HashSet<>();
            messages.forEach(message -> existing.add(message.id()));
            for (AgentMessage message : recovered.messages()) {
                if (!existing.contains(message.id())) sessions.message(id, message);
            }
            for (var pendingApproval : recovered.pendingApprovals())
                approvals.restore(mapper.treeToValue(pendingApproval, ApprovalService.PendingApproval.class));
            sessions.updateMetadata(id, meta -> {
                Set<String> uncertain = new LinkedHashSet<>(recovered.uncertainCalls());
                meta.path("uncertainSideEffectCalls").forEach(call -> uncertain.add(call.asText()));
                meta.set("uncertainSideEffectCalls", mapper.valueToTree(uncertain));
                meta.put("recoveryRequired", true);
            });
            sessions.status(id, recovered.runId(), AgentLoopState.RunStatus.INTERRUPTED);
        }
    }
}
