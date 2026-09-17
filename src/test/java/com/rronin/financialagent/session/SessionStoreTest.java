package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path root;
    private ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    @Test void historyExposesCurrentRunMetadataAndOnlyCompletedFinalAnswer() throws Exception {
        var store=new SessionStore(mapper(),root);String id=store.create("review").path("sessionId").asText(),run=UUID.randomUUID().toString();
        store.status(id,run,com.rronin.financialagent.agent.AgentLoopState.RunStatus.RUNNING);
        var draft=AgentMessage.text(run,AgentMessage.Role.ASSISTANT,"Candidate needing revision",false);store.message(id,draft);
        assertThat((java.util.Set<?>)store.history(id).get("acceptedMessageIds")).isEmpty();
        var answer=AgentMessage.text(run,AgentMessage.Role.ASSISTANT,"Reviewed final answer",false);store.message(id,answer);
        store.status(id,run,com.rronin.financialagent.agent.AgentLoopState.RunStatus.COMPLETED);
        assertThat((java.util.Set<String>)store.history(id).get("acceptedMessageIds")).containsExactly(answer.id());
        var metadata=(com.fasterxml.jackson.databind.JsonNode)store.history(id).get("metadata");
        assertThat(metadata.path("lastRunId").asText()).isEqualTo(run);
        assertThat(metadata.path("runStatus").asText()).isEqualTo("COMPLETED");
    }
    @Test void persistsMessagesAcrossReconstruction() throws Exception {
        var store = new SessionStore(mapper(), root);
        String id = store.create("研究 NBIS").path("sessionId").asText();
        String run = UUID.randomUUID().toString();
        store.message(id, AgentMessage.text(run, AgentMessage.Role.USER, "近七天观点", false));
        var reopened = new SessionStore(mapper(), root);
        assertThat(reopened.messages(id)).hasSize(1);
        assertThat(reopened.messages(id).getFirst().text()).isEqualTo("近七天观点");
        assertThat(reopened.metadata(id).path("messageCount").asInt()).isEqualTo(1);
    }
    @Test void partialTailIsPreservedSeparatelyBeforeAppending() throws Exception {
        var store = new SessionStore(mapper(), root);
        String id = store.create("test").path("sessionId").asText();
        Path transcript = store.directory(id).resolve("transcript.jsonl");
        Files.writeString(transcript, "{\"type\":", StandardOpenOption.APPEND);
        assertThat(store.events(id)).hasSize(1);
        store.message(id, AgentMessage.text(UUID.randomUUID().toString(), AgentMessage.Role.USER, "恢复", false));
        assertThat(store.events(id)).hasSize(2);
        assertThat(store.messages(id)).hasSize(1);
    }
    @Test void rejectsTraversalAndUnknownSession() {
        var store = new SessionStore(mapper(), root);
        assertThatThrownBy(() -> store.directory("../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.metadata(UUID.randomUUID().toString())).isInstanceOf(NoSuchFileException.class);
    }
    @Test void workEventsSurviveReopenInOrderWithoutDuplicatingLegacyAudit() throws Exception {
        var store=new SessionStore(mapper(),root);String id=store.create("timeline").path("sessionId").asText(),run=UUID.randomUUID().toString();
        store.append(id,run,"work.event",java.util.Map.of("type","audit.started","data",java.util.Map.of()));
        store.append(id,run,"audit.completed",java.util.Map.of("review",java.util.Map.of("verdict","PASS")));
        store.append(id,run,"work.event",java.util.Map.of("type","audit.completed","data",java.util.Map.of("verdict","PASS")));
        var history=new SessionStore(mapper(),root).history(id);
        var events=(java.util.Map<String,java.util.List<com.fasterxml.jackson.databind.JsonNode>>)history.get("workEvents");
        assertThat(events.get(run)).extracting(e->e.path("type").asText()).containsExactly("audit.started","audit.completed");
    }
}
