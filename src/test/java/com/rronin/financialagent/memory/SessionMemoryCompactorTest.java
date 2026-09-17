package com.rronin.financialagent.memory;

import com.rronin.financialagent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class SessionMemoryCompactorTest {
    @Test void nonProviderBlocksDoNotCreateAnOversizedTail() {
        var covered=AgentMessage.text("r",AgentMessage.Role.USER,"covered",false);
        var recent=new AgentMessage("recent","r",AgentMessage.Role.ASSISTANT,false,java.time.Instant.now(),java.util.List.of(
                new AgentMessage.Block(AgentMessage.BlockType.THINKING,"x".repeat(200000),null,null,null,false),
                AgentMessage.Block.text("Completed")));
        var boundary=new SessionMemoryCompactor().compact(new SessionMemorySnapshot(1,covered.id(),"Summary"),java.util.List.of(covered,recent),100,32000);
        assertThat(boundary.retained()).contains(recent);
        assertThat(boundary.estimatedTokens()).isLessThan(1000);
    }
    @Test void replayExistingSnapshotWithoutModelCalls() throws Exception {
        String session=System.getProperty("agent.compactionReplaySession","");
        org.junit.jupiter.api.Assumptions.assumeTrue(!session.isBlank());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var store=new com.rronin.financialagent.session.SessionStore(mapper,java.nio.file.Path.of(".test-runtime/sessions"));
        var snapshot=SessionMemorySnapshot.parse(java.nio.file.Files.readString(store.directory(session).resolve("session-memory.md")));
        var messages=SessionMemoryService.completePrefix(store.messages(session));
        var boundary=new SessionMemoryCompactor().compact(snapshot,messages,10000,128000);
        com.rronin.financialagent.model.MessageIntegrity.validate(boundary.retained());
        System.out.println("Existing snapshot replay: messages="+messages.size()+", retained="+boundary.retained().size()+", estimatedTokens="+boundary.estimatedTokens());
    }
    @Test void snapshotRoundTripKeepsRevisionAndCursorTogether() {
        var snapshot = new SessionMemorySnapshot(6, "message_1", "# Memory\nA result");
        assertThat(SessionMemorySnapshot.parse(snapshot.serialize())).isEqualTo(snapshot);
    }
    @Test void compactionCannotDiscardUncoveredMessages() {
        var messages = new ArrayList<AgentMessage>();
        messages.add(AgentMessage.text("r", AgentMessage.Role.USER, "covered", false));
        messages.add(AgentMessage.text("r", AgentMessage.Role.USER, "中".repeat(50_000), false));
        var snapshot = new SessionMemorySnapshot(1, messages.getFirst().id(), "A summary");
        assertThatThrownBy(() -> new SessionMemoryCompactor().compact(snapshot, messages, 1000, 100_000))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void unknownCursorIsNotTreatedAsFullCoverage() {
        var messages = java.util.List.of(AgentMessage.text("r", AgentMessage.Role.USER, "not covered", false));
        assertThatThrownBy(() -> new SessionMemoryCompactor().compact(new SessionMemorySnapshot(1, "missing", "summary"), messages, 100, 100_000))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void optionalOldContextDoesNotDefeatLowerOperatingThreshold() {
        var old=AgentMessage.text("r",AgentMessage.Role.USER,"中".repeat(15000),false);
        var recent=AgentMessage.text("r",AgentMessage.Role.ASSISTANT,"Completed with evidence",false);
        var snapshot=new SessionMemorySnapshot(1,recent.id(),"Earlier material summarized");
        var boundary=new SessionMemoryCompactor().compact(snapshot,java.util.List.of(old,recent),100,10000);
        assertThat(boundary.retained()).containsExactly(recent);
        assertThat(boundary.estimatedTokens()).isLessThan(10000);
    }

}
