package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.*;
import com.rronin.financialagent.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRecoveryServiceTest {
    @TempDir Path root;
    @Test void repeatedStartupPreservesUnknownEffectsAndRestoresQueueWithoutDuplicates() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new SessionStore(mapper, root);
        String session = store.create("recovery").path("sessionId").asText();
        String run = UUID.randomUUID().toString();
        store.status(session, run, AgentLoopState.RunStatus.RUNNING);
        store.message(session, new AgentMessage(UUID.randomUUID().toString(), run, AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("order", "place_order", mapper.createObjectNode()))));
        store.append(session, run, "message.queued", new MidRunMessageQueue.QueuedMessage("queued", "Check status before retrying"));
        var queue = new MidRunMessageQueue();
        var approvals = mock(ApprovalService.class);
        var recovery = new SessionRecoveryService(store, approvals, queue, mapper);
        recovery.recoverSession(session);
        recovery.recoverSession(session);
        assertThat(queue.peek(session)).hasSize(1);
        assertThat(store.messages(session)).hasSize(2);
        MessageIntegrity.validate(store.messages(session));
        assertThat(store.metadata(session).path("uncertainSideEffectCalls").toString()).contains("order");
        assertThat(store.metadata(session).path("runStatus").asText()).isEqualTo("INTERRUPTED");
        verifyNoInteractions(approvals);
    }
}
