package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMemoryServiceTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void stagedEditCommitsRevisionAndAuthoritativeCursorOnlyAfterCompletion() throws Exception {
        var store = new SessionStore(mapper, root);
        String session = store.create("memory-test").path("sessionId").asText();
        String run = UUID.randomUUID().toString();
        var message = AgentMessage.text(run, AgentMessage.Role.ASSISTANT, "Research completed", false);
        store.append(session, run, "message.created", message);
        var calls = new AtomicInteger();
        ModelGateway model = (request, stream) -> {
            MessageIntegrity.validate(request.messages());
            if (calls.getAndIncrement() == 0) {
                var input = mapper.createObjectNode().put("path", store.directory(session).resolve("session-memory.md").toString())
                        .put("old_text", "## Key Results").put("new_text", "## Key Results\nResearch completed; sources still need review.");
                return Mono.just(new ModelGateway.Reply(new AgentMessage("edit", request.runId(), AgentMessage.Role.ASSISTANT, false,
                        Instant.now(), List.of(AgentMessage.Block.toolUse("edit-1", "Edit", input))), 1, 1, "completed", "test"));
            }
            try {
                assertThat(SessionMemorySnapshot.parse(Files.readString(store.directory(session).resolve("session-memory.md"))).revisionEdition()).isZero();
            } catch (Exception error) { throw new AssertionError(error); }
            return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(), AgentMessage.Role.ASSISTANT, "Done", false), 1, 1, "completed", "test"));
        };
        var service = new SessionMemoryService(store, model, mapper, properties());
        service.afterAssistant(session, run, List.of(message), 16_000, 0, true);
        var snapshot = service.stableSnapshot(session).block(Duration.ofSeconds(5));
        assertThat(snapshot.revisionEdition()).isEqualTo(1);
        assertThat(snapshot.coveredThroughMessageId()).isEqualTo(message.id());
        assertThat(snapshot.body()).contains("sources still need review");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void confirmedNoChangeStillAdvancesCoverageCursor() throws Exception {
        var store=new SessionStore(mapper,root);String session=store.create("no-change").path("sessionId").asText();
        var message=AgentMessage.text("11111111-1111-4111-8111-111111111111",AgentMessage.Role.ASSISTANT,"No new information",false);store.append(session,"11111111-1111-4111-8111-111111111111","message.created",message);
        ModelGateway model=(request,stream)->Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,"Done",false),1,1,"completed","test"));
        var service=new SessionMemoryService(store,model,mapper,properties());
        service.afterAssistant(session,"11111111-1111-4111-8111-111111111111",List.of(message),16000,0,true);
        var snapshot=service.stableSnapshot(session).block(Duration.ofSeconds(5));
        assertThat(snapshot.coveredThroughMessageId()).isEqualTo(message.id());
        assertThat(snapshot.revisionEdition()).isEqualTo(1);
    }
    @Test void finalTurnIsConfirmationOnlyAndFailedEditsCannotAdvanceCursor() throws Exception {
        var store=new SessionStore(mapper,root);String session=store.create("failed-edit").path("sessionId").asText();
        var message=AgentMessage.text("11111111-1111-4111-8111-111111111111",AgentMessage.Role.ASSISTANT,"Important new constraint",false);store.append(session,"11111111-1111-4111-8111-111111111111","message.created",message);
        var count=new AtomicInteger();
        ModelGateway model=(request,stream)->{
            int turn=count.getAndIncrement();
            if(turn==2){assertThat(request.tools()).isEmpty();assertThat(request.messages()).hasSize(1);assertThat(request.messages().getFirst().toolUses()).isEmpty();assertThat(request.messages().getFirst().text()).contains("Current staged memory body", "Important new constraint");return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,"Done",false),1,1,"completed","test"));}
            var args=mapper.createObjectNode().put("path",store.directory(session).resolve("session-memory.md").toString()).put("old_text","DOES NOT EXIST").put("new_text","New information");
            return Mono.just(new ModelGateway.Reply(new AgentMessage("edit"+turn,request.runId(),AgentMessage.Role.ASSISTANT,false,Instant.now(),List.of(AgentMessage.Block.toolUse("call"+turn,"Edit",args))),1,1,"completed","test"));
        };
        var service=new SessionMemoryService(store,model,mapper,properties());service.afterAssistant(session,"11111111-1111-4111-8111-111111111111",List.of(message),16000,0,true);
        assertThat(service.stableSnapshot(session).block(Duration.ofSeconds(5)).coveredThroughMessageId()).isBlank();
        assertThat(count.get()).isEqualTo(3);
    }
    @Test void incompleteToolGroupIsExcludedFromMemoryCursor() {
        var user = AgentMessage.text("run", AgentMessage.Role.USER, "Research", false);
        var assistant = new AgentMessage("assistant", "run", AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("pending", "Read", mapper.createObjectNode())));
        assertThat(SessionMemoryService.completePrefix(List.of(user, assistant))).containsExactly(user);
    }

    @Test void immediateSnapshotDoesNotWaitForPendingExtraction() throws Exception {
        var store=new SessionStore(mapper,root);
        String session=store.create("pending").path("sessionId").asText();
        var message=AgentMessage.text("11111111-1111-4111-8111-111111111111",AgentMessage.Role.ASSISTANT,"New information",false);
        ModelGateway model=(request,stream)->Mono.never();
        var service=new SessionMemoryService(store,model,mapper,properties());
        service.afterAssistant(session,message.runId(),List.of(message),16000,0,true);
        assertThat(service.stableSnapshot(session,false).block(Duration.ofSeconds(2))).isNotNull();
    }

    private AgentProperties properties() {
        var properties = mock(AgentProperties.class);
        when(properties.memory()).thenReturn(new AgentProperties.Memory(root, true, false, 100_000, 100_000, 30, .7));
        return properties;
    }
    @Test void commonCredentialFormatsAreRejectedWithoutRejectingOrdinaryDiscussion() {
        assertThat(SessionMemoryService.containsCredential("sk-proj-" + "x".repeat(40))).isTrue();
        assertThat(SessionMemoryService.containsCredential("Bearer " + "a".repeat(30))).isTrue();
        assertThat(SessionMemoryService.containsCredential("Store the API key outside the repository.")).isFalse();
    }
}
