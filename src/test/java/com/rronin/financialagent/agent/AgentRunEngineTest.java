package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.audit.CandidateReviewService;
import com.rronin.financialagent.config.*;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.session.SessionStore;
import com.rronin.financialagent.skills.SkillRegistry;
import com.rronin.financialagent.tools.*;
import com.rronin.financialagent.tools.file.FileGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunEngineTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @Test void realLoopPairsToolsRunsBothReviewsAndPersistsSessionHistory() throws Exception {
        var calls = new AtomicInteger();
        var toolExecuted = new AtomicInteger();
        var fullMessageReceived = new java.util.concurrent.atomic.AtomicBoolean();
        var cancelStream=new java.util.concurrent.atomic.AtomicBoolean();
        var partialReady=new java.util.concurrent.CountDownLatch(1);
        var shutdownScenario=new java.util.concurrent.atomic.AtomicBoolean();
        var modelWaiting=new java.util.concurrent.CountDownLatch(1);
        ModelGateway gateway = (request, events) -> {
            MessageIntegrity.validate(request.messages());
            if (request.role() == ModelGateway.Role.PRIMARY) {
                assertThat(request.messages()).anyMatch(m->m.text().contains("0.1 position = 0.1 shares"));
                if(cancelStream.get()) {
                    events.accept(new ModelGateway.StreamEvent("message_start",Map.of()));
                    events.accept(new ModelGateway.StreamEvent("text_delta",Map.of("text","已生成的部分文本")));
                    events.accept(new ModelGateway.StreamEvent("content_block_start",mapper.createObjectNode().put("type","function_call").put("id","item-partial").put("call_id","call-partial").put("name","lookup").put("arguments","")));
                    events.accept(new ModelGateway.StreamEvent("tool_input_delta",Map.of("itemId","item-partial","delta","{\"query\":")));
                    partialReady.countDown();return Mono.never();
                }
                if(shutdownScenario.get()){modelWaiting.countDown();return Mono.never();}
                int attempt=calls.getAndIncrement();
                if (attempt == 0) {
                    fullMessageReceived.set(true);
                    events.accept(new ModelGateway.StreamEvent("message_stop", Map.of()));
                    var message = new AgentMessage(UUID.randomUUID().toString(), request.runId(), AgentMessage.Role.ASSISTANT, false, Instant.now(),
                            List.of(AgentMessage.Block.toolUse("call-1", "lookup", mapper.createObjectNode()), AgentMessage.Block.toolUse("bad-call", "lookup", mapper.createObjectNode().put("_invalid_function_arguments",true))));
                    return Mono.just(new ModelGateway.Reply(message, 100, 10, "completed", "test"));
                }
                if(attempt==1)return Mono.error(new ModelFailure(ModelFailure.Kind.TRANSIENT_SERVER,503));
                return Mono.just(reply(request, "完成研究，有明确来源。"));
            }
            return Mono.just(reply(request, switch (request.role()) {
                case GENERAL_VALIDATION -> "{\"taskCompletion\":{\"status\":\"PASS\",\"feedback\":[]},\"responseRequirement\":{\"status\":\"PASS\",\"feedback\":[]},\"deliverable\":{\"status\":\"PASS\",\"feedback\":[]}}";
                case CLAIM_EXTRACTION -> "{\"claims\":[]}";
                case FINANCIAL_AUDIT -> "{\"verdict\":\"PASS\",\"feedback\":[]}";
                default -> throw new AssertionError(request.role());
            }));
        };
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public String description() { return "Test lookup"; }
            public Mode mode() { return Mode.READ_ONLY; }
            public com.fasterxml.jackson.databind.JsonNode inputSchema() { return mapper.createObjectNode().put("type", "object"); }
            public boolean isReadOnly(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            public Optional<ApprovalBehaviour> evaluateApproval(com.fasterxml.jackson.databind.JsonNode input, ApprovalContext context) { return Optional.of(ApprovalBehaviour.ALLOW); }
            public Mono<ToolResult> execute(com.fasterxml.jackson.databind.JsonNode input) {
                assertThat(fullMessageReceived.get()).isTrue();
                toolExecuted.incrementAndGet();
                return Mono.just(ToolResult.ok(name(), Map.of("source", "test-source")));
            }
        };
        var properties = mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root, 50_000));
        when(properties.systemPromptPath()).thenReturn("classpath:com/rronin/financialagent/AGENT.md");
        var settings = new ModelSettings("http://localhost", "", "primary", "aux", "embedding", "high", "medium",
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), 1, 1_050_000, 400_000, 128_000, Map.of());
        var sessions = new SessionStore(mapper, root.resolve("sessions"));
        var userRules = mock(UserApprovalRuleStore.class);
        when(userRules.load()).thenReturn(List.of());
        var approvals = new ApprovalService(new ApprovalPolicyEngine(), userRules);
        approvals.setSessionStore(sessions);
        var skills = mock(SkillRegistry.class);
        when(skills.list()).thenReturn(List.of());
        var engine = new AgentRunEngine(mapper, gateway, settings, properties, new ToolRegistry(List.of(tool), mapper, properties),
                new ToolInputValidator(), approvals, new AutoApprovalService(gateway, settings, sessions, mapper),
                new CandidateReviewService(mapper, gateway, new FileGuard(properties)), sessions, skills, new MidRunMessageQueue(),
                mock(com.rronin.financialagent.memory.SessionMemoryService.class), new DefaultResourceLoader());
        var preferences=mock(com.rronin.financialagent.memory.TopicFileStore.class);
        when(preferences.index()).thenReturn("User terminology");
        when(preferences.list()).thenReturn(List.of(new com.rronin.financialagent.memory.TopicFileStore.Topic("topics/user/units.md","Units","user","Quantity convention","v1",Instant.now(),"0.1 position = 0.1 shares",List.of("position"),List.of())));
        engine.setTopicMemory(preferences,null,null);
        try {
            String id = sessions.create("test").path("sessionId").asText();
            var events = engine.run(id, "研究", null, null, List.of(), AgentRunner.BackgroundRunPolicy.none()).collectList().block(Duration.ofSeconds(10));
            assertThat(events.stream().map(e -> e.type())).contains("run.started", "tool.started", "tool.finished", "audit.completed", "run.completed").doesNotContain("run.failed");
            assertThat(toolExecuted.get()).isEqualTo(1);
            assertThat(sessions.messages(id).stream().flatMap(m->m.content().stream()).filter(b->b.type()==AgentMessage.BlockType.TOOL_RESULT&&"bad-call".equals(b.toolCallId())).findFirst().orElseThrow().text()).contains("INVALID_FUNCTION_ARGUMENTS");
            assertThat(events.stream().map(e->e.type())).contains("model.external_retry");
            assertThat(sessions.events(id).stream().filter(e->"model.failure".equals(e.path("type").asText())).count()).isEqualTo(1);
            assertThat(events.stream().map(e->e.runId()).distinct().count()).isEqualTo(1);
            MessageIntegrity.validate(sessions.messages(id));
            var next = engine.run(id, "继续", null, null, List.of(), AgentRunner.BackgroundRunPolicy.none()).collectList().block(Duration.ofSeconds(10));
            assertThat(next.getFirst().runId()).isNotEqualTo(events.getFirst().runId());
            assertThat(sessions.messages(id).stream().filter(m -> m.role() == AgentMessage.Role.USER && !m.meta())).hasSize(2);
            String interrupted = UUID.randomUUID().toString();
            sessions.message(id, AgentMessage.text(interrupted, AgentMessage.Role.USER, "Resume this lookup", false));
            sessions.message(id, new AgentMessage(UUID.randomUUID().toString(), interrupted, AgentMessage.Role.ASSISTANT, false, Instant.now(),
                    List.of(AgentMessage.Block.toolUse("original-interrupted-call", "lookup", mapper.createObjectNode()))));
            var approval = approvals.requestForCall(id, interrupted, "original-interrupted-call", tool, mapper.createObjectNode(), "Original approval");
            sessions.status(id, interrupted, AgentLoopState.RunStatus.INTERRUPTED);
            approvals.decide(approval.id(), ApprovalService.Decision.ALLOW_ONCE);
            var resumed = engine.resume(id).collectList().block(Duration.ofSeconds(10));
            assertThat(resumed.getFirst().runId()).isEqualTo(interrupted);
            assertThat(resumed.stream().map(e -> e.type())).contains("run.completed").doesNotContain("run.failed");
            assertThat(toolExecuted.get()).isEqualTo(2);
            MessageIntegrity.validate(sessions.messages(id));
            int beforeControl=calls.get();
            var control=engine.run(null,"能直接结束吗",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().block(Duration.ofSeconds(2));
            assertThat(control).extracting(AgentModels.AgentEvent::type).contains("run.completed","final.delta").doesNotContain("audit.started");
            assertThat(calls.get()).isEqualTo(beforeControl);
            cancelStream.set(true);
            String partialSession=sessions.create("Partial Stop").path("sessionId").asText();
            var partialRun=engine.run(partialSession,"Stop during streaming",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().toFuture();
            assertThat(partialReady.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            engine.cancel(sessions.metadata(partialSession).path("lastRunId").asText());
            assertThat(partialRun.get(2,java.util.concurrent.TimeUnit.SECONDS)).extracting(AgentModels.AgentEvent::type).contains("model.cancelled_partial","run.cancelled").doesNotContain("run.completed");
            MessageIntegrity.validate(sessions.messages(partialSession));
            assertThat(sessions.messages(partialSession)).anyMatch(m->m.text().equals("已生成的部分文本"));
            assertThat((java.util.Set<?>)sessions.history(partialSession).get("cancelledPartialMessageIds")).hasSize(1);
            cancelStream.set(false);
            shutdownScenario.set(true);
            var stream=engine.run(id,"Shutdown checkpoint",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().toFuture();
            assertThat(modelWaiting.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String active=sessions.metadata(id).path("lastRunId").asText();
            engine.enqueue(active,"Keep queued request for restart");
            engine.close();
            assertThat(sessions.metadata(id).path("runStatus").asText()).isEqualTo("INTERRUPTED");
            assertThat(stream.get(5,java.util.concurrent.TimeUnit.SECONDS).stream().map(e->e.type())).contains("run.interrupted").doesNotContain("run.completed","run.failed");
            assertThat(sessions.messages(id).stream().map(AgentMessage::text)).doesNotContain("Keep queued request for restart");
        } finally { engine.close(); }
    }
    @Test void reportsArePublishedOnlyAfterPassAndFailedRunsKeepExistingReport() throws Exception {
        var properties = mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root, 50000));
        when(properties.systemPromptPath()).thenReturn("classpath:com/rronin/financialagent/AGENT.md");
        var settings = new ModelSettings("http://localhost", "", "primary", "aux", "embedding", "medium", "medium",
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), 1, 100000, 50000, 16000, Map.of());
        var sessions = new SessionStore(mapper, root.resolve("sessions"));
        var rules = mock(UserApprovalRuleStore.class); when(rules.load()).thenReturn(List.of());
        var approvals = new ApprovalService(new ApprovalPolicyEngine(), rules); approvals.setSessionStore(sessions);
        var skills = mock(SkillRegistry.class); when(skills.list()).thenReturn(List.of());
        var calls = new AtomicInteger();
        ModelGateway gateway = (request, events) -> {
            if (calls.getAndIncrement() % 2 == 0) return Mono.just(new ModelGateway.Reply(
                    new AgentMessage(UUID.randomUUID().toString(),request.runId(),AgentMessage.Role.ASSISTANT,false,Instant.now(),
                            List.of(AgentMessage.Block.toolUse("write-"+calls.get(),"write_file",mapper.createObjectNode().put("path","reports/test.md").put("content","reviewed report")))),100,10,"completed","test"));
            return Mono.just(reply(request,"report ready"));
        };
        var guard = new FileGuard(properties);
        AgentTool tool = new com.rronin.financialagent.tools.file.WriteFileTool(guard, mapper) {
            public boolean isDestructive(com.fasterxml.jackson.databind.JsonNode input) { return false; }
            public Optional<ApprovalBehaviour> evaluateApproval(com.fasterxml.jackson.databind.JsonNode input, ApprovalContext context) { return Optional.of(ApprovalBehaviour.ALLOW); }
        };
        var review = mock(CandidateReviewService.class);
        var verdict = new java.util.concurrent.atomic.AtomicReference<>(CandidateReviewService.Verdict.PASS);
        when(review.review(any())).thenAnswer(invocation -> {
            CandidateReviewService.Input input = invocation.getArgument(0);
            assertThat(input.deliverables()).hasSize(1);
            assertThat(java.nio.file.Files.readString(Path.of(input.deliverables().getFirst().path()))).isEqualTo("reviewed report");
            if(verdict.get()==CandidateReviewService.Verdict.PASS)assertThat(root.resolve("reports/test.md")).doesNotExist();
            return Mono.just(new CandidateReviewService.Result(verdict.get(),List.of("test"),null,null,null));
        });
        var engine = new AgentRunEngine(mapper,gateway,settings,properties,new ToolRegistry(List.of(tool),mapper,properties),new ToolInputValidator(),approvals,
                new AutoApprovalService(gateway,settings,sessions,mapper),review,sessions,skills,new MidRunMessageQueue(),mock(com.rronin.financialagent.memory.SessionMemoryService.class),new DefaultResourceLoader());
        try {
            var passed=engine.run(null,"write report",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().block(Duration.ofSeconds(10));
            assertThat(passed).extracting(AgentModels.AgentEvent::type).contains("run.completed");
            assertThat(java.nio.file.Files.readString(root.resolve("reports/test.md"))).isEqualTo("reviewed report");
            java.nio.file.Files.writeString(root.resolve("reports/test.md"),"previous approved version");
            verdict.set(CandidateReviewService.Verdict.ERROR);
            var failed=engine.run(null,"write report",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().block(Duration.ofSeconds(10));
            assertThat(failed).extracting(AgentModels.AgentEvent::type).contains("run.failed").doesNotContain("run.completed");
            assertThat(java.nio.file.Files.readString(root.resolve("reports/test.md"))).isEqualTo("previous approved version");
            var reviewing = new java.util.concurrent.CountDownLatch(1);
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(invocation -> { reviewing.countDown(); return Mono.never().doOnCancel(() -> cancelled.set(true)); }).when(review).review(any());
            String cancellingSession = sessions.create("cancel during review").path("sessionId").asText();
            var running = engine.run(cancellingSession,"write report",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).collectList().toFuture();
            assertThat(reviewing.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(engine.cancel(sessions.metadata(cancellingSession).path("lastRunId").asText())).isTrue();
            assertThat(running.get(2,java.util.concurrent.TimeUnit.SECONDS)).extracting(AgentModels.AgentEvent::type)
                    .contains("run.cancelled").doesNotContain("run.completed","final.delta");
            assertThat(cancelled.get()).isTrue();
            assertThat(java.nio.file.Files.readString(root.resolve("reports/test.md"))).isEqualTo("previous approved version");
        } finally { engine.close(); }
    }
    @Test void approvalWaitKeepsQueuedMessagesUntilPairedToolResultAndDeletionIsDurable() throws Exception {
        var properties=mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root,50000));
        when(properties.systemPromptPath()).thenReturn("classpath:com/rronin/financialagent/AGENT.md");
        var settings=new ModelSettings("http://localhost","","primary","aux","embedding","medium","low",Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofSeconds(2),0,100000,50000,16000,Map.of());
        var sessions=new SessionStore(mapper,root.resolve("sessions"));
        var rules=mock(UserApprovalRuleStore.class);when(rules.load()).thenReturn(List.of());
        var approvals=new ApprovalService(new ApprovalPolicyEngine(),rules);approvals.setSessionStore(sessions);
        var skills=mock(SkillRegistry.class);when(skills.list()).thenReturn(List.of());
        var calls=new AtomicInteger();var executed=new AtomicInteger();
        var hang=new java.util.concurrent.atomic.AtomicBoolean();var toolRunning=new java.util.concurrent.CountDownLatch(1);
        var waiting=new java.util.concurrent.CountDownLatch(1);
        var approvalId=new java.util.concurrent.atomic.AtomicReference<String>();
        ModelGateway gateway=(request,events)->{
            MessageIntegrity.validate(request.messages());
            if(calls.getAndIncrement()==0)return Mono.just(new ModelGateway.Reply(new AgentMessage(UUID.randomUUID().toString(),request.runId(),AgentMessage.Role.ASSISTANT,false,Instant.now(),
                    List.of(AgentMessage.Block.toolUse("approval-call","test_mutation",mapper.createObjectNode()))),100,10,"completed","test"));
            assertThat(executed.get()).isEqualTo(1);
            var messages=request.messages();int tool=-1,steer=-1;
            for(int i=0;i<messages.size();i++){if(messages.get(i).role()==AgentMessage.Role.TOOL)tool=i;if(messages.get(i).text().contains("After approval use this instruction"))steer=i;}
            assertThat(steer).isGreaterThan(tool);assertThat(tool).isGreaterThanOrEqualTo(0);
            assertThat(messages).noneMatch(m->m.text().contains("Delete this queued request"));
            return Mono.just(reply(request,"Done"));
        };
        AgentTool tool=new AgentTool(){
            public String name(){return "test_mutation";}public String description(){return "Fixture only";}public Mode mode(){return Mode.MUTATING;}
            public com.fasterxml.jackson.databind.JsonNode inputSchema(){return mapper.createObjectNode().put("type","object");}
            public Mono<ToolResult> execute(com.fasterxml.jackson.databind.JsonNode input){executed.incrementAndGet();if(hang.get()){toolRunning.countDown();return Mono.never();}return Mono.just(ToolResult.ok(name(),Map.of("done",true)));}
        };
        var review=mock(CandidateReviewService.class);when(review.review(any())).thenReturn(Mono.just(new CandidateReviewService.Result(CandidateReviewService.Verdict.PASS,List.of(),null,null,null)));
        var queue=new MidRunMessageQueue();
        var engine=new AgentRunEngine(mapper,gateway,settings,properties,new ToolRegistry(List.of(tool),mapper,properties),new ToolInputValidator(),approvals,new AutoApprovalService(gateway,settings,sessions,mapper),review,sessions,skills,queue,mock(com.rronin.financialagent.memory.SessionMemoryService.class),new DefaultResourceLoader());
        try{
            String session=sessions.create("approval queue").path("sessionId").asText();
            var result=engine.run(session,"Fixture mutation",null,"temp-fixture",List.of(),AgentRunner.BackgroundRunPolicy.none()).doOnNext(event->{if(event.type().equals("approval.request")){approvalId.set(((ApprovalService.PendingApproval)event.data()).id());waiting.countDown();}}).collectList().toFuture();
            assertThat(waiting.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(sessions.metadata(session).path("approvalMode").asText("DEFAULT")).isEqualTo("DEFAULT");
            String run=sessions.metadata(session).path("lastRunId").asText();
            engine.enqueue(run,"After approval use this instruction");
            var removed=engine.enqueue(run,"Delete this queued request");
            assertThat(engine.deleteQueued(session,removed.id())).isTrue();
            assertThat(queue.peek(session)).hasSize(1);assertThat(calls.get()).isEqualTo(1);assertThat(executed.get()).isZero();
            assertThat(approvals.decide(approvalId.get(),ApprovalService.Decision.ALLOW_ONCE)).isTrue();
            assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).extracting(AgentModels.AgentEvent::type).contains("midrun.steered","run.completed").doesNotContain("run.failed");
            assertThat(sessions.events(session)).anyMatch(e->e.path("type").asText().equals("message.queue_deleted"));
            calls.set(0);hang.set(true);
            String stoppingSession=sessions.create("Stop mutating fixture").path("sessionId").asText();
            var stopping=engine.run(stoppingSession,"Fixture mutation",null,null,List.of(),AgentRunner.BackgroundRunPolicy.none()).doOnNext(event->{if(event.type().equals("approval.request"))approvals.decide(((ApprovalService.PendingApproval)event.data()).id(),ApprovalService.Decision.ALLOW_ONCE);}).collectList().toFuture();
            assertThat(toolRunning.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            engine.cancel(sessions.metadata(stoppingSession).path("lastRunId").asText());
            assertThat(stopping.get(2,java.util.concurrent.TimeUnit.SECONDS)).extracting(AgentModels.AgentEvent::type).contains("run.cancelled").doesNotContain("run.completed");
            MessageIntegrity.validate(sessions.messages(stoppingSession));
            assertThat(sessions.metadata(stoppingSession).path("uncertainSideEffectCalls").toString()).contains("approval-call");

        }finally{engine.close();}
    }
    private ModelGateway.Reply reply(ModelGateway.Request request, String text) {
        return new ModelGateway.Reply(AgentMessage.text(request.runId(), AgentMessage.Role.ASSISTANT, text, false), 100, 10, "completed", "test");
    }
}
