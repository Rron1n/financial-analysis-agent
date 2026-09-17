package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.tools.file.FileGuard;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CandidateReviewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private CandidateReviewService.Input input() {
        return new CandidateReviewService.Input("s", "r", "总结观点", "这是有出处的观点总结", List.of(), Map.of("tool1", "source"), "system");
    }
    private ModelGateway.Reply reply(String text) {
        return new ModelGateway.Reply(AgentMessage.text("r", AgentMessage.Role.ASSISTANT, text, false), 10, 10, "completed", "test");
    }
    @Test void malformedJsonRetryReceivesOriginalForSyntaxRepair() {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        String malformed="{\"claims\":[{\"statement\" \"bad\"}]}";
        ModelGateway gateway=(request,events)-> {
            if(request.role()==ModelGateway.Role.CLAIM_EXTRACTION) {
                if(attempts.getAndIncrement()==0)return Mono.just(reply(malformed));
                assertThat(request.instructions()).contains(malformed,"Repair the JSON syntax","Preserve its claim content");
                return Mono.just(reply("{\"claims\":[]}"));
            }
            if(request.role()==ModelGateway.Role.FINANCIAL_AUDIT)return Mono.just(reply("{\"verdict\":\"BLOCK\",\"feedback\":[\"unsupported\"]}"));
            return Mono.just(reply("{\"taskCompletion\":{\"status\":\"PASS\",\"feedback\":[]},\"responseRequirement\":{\"status\":\"PASS\",\"feedback\":[]},\"deliverable\":{\"status\":\"PASS\",\"feedback\":[]}}"));
        };
        assertThat(new CandidateReviewService(mapper,gateway,mock(FileGuard.class)).review(input()).block().verdict()).isEqualTo(CandidateReviewService.Verdict.BLOCK);
        assertThat(attempts.get()).isEqualTo(2);
    }
    @Test void missingExtractorUncertaintyIsExplicitlyUnknownNotSilentlyCertain() throws Exception {
        var value=mapper.readTree("{\"claims\":[{\"statement\":\"A claim\"},{\"statement\":\"Another\",\"uncertainties\":[\"Known limitation\"]}]}");
        CandidateReviewService.normalizeClaimUncertainties(value);
        assertThat(value.path("claims").get(0).path("uncertainties").get(0).asText()).contains("did not provide","assess the original");
        assertThat(value.path("claims").get(1).path("uncertainties").get(0).asText()).isEqualTo("Known limitation");
    }
    @Test void independentReviewsStartBeforeEitherFinishes() throws Exception {
        var general=reactor.core.publisher.Sinks.<ModelGateway.Reply>one();
        var claims=reactor.core.publisher.Sinks.<ModelGateway.Reply>one();
        var started=new java.util.concurrent.CountDownLatch(2);
        ModelGateway gateway=(request,events)->switch(request.role()){
            case GENERAL_VALIDATION -> general.asMono().doOnSubscribe(ignored->started.countDown());
            case CLAIM_EXTRACTION -> claims.asMono().doOnSubscribe(ignored->started.countDown());
            case FINANCIAL_AUDIT -> Mono.just(reply("{\"verdict\":\"PASS\",\"feedback\":[]}"));
            default -> Mono.error(new AssertionError());
        };
        var result=new CandidateReviewService(mapper,gateway,mock(FileGuard.class)).review(input()).toFuture();
        assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(result.isDone()).isFalse();
        general.tryEmitValue(reply("{\"taskCompletion\":{\"status\":\"PASS\",\"feedback\":[]},\"responseRequirement\":{\"status\":\"PASS\",\"feedback\":[]},\"deliverable\":{\"status\":\"PASS\",\"feedback\":[]}}"));
        claims.tryEmitValue(reply("{\"claims\":[]}"));
        assertThat(result.get(2,java.util.concurrent.TimeUnit.SECONDS).verdict()).isEqualTo(CandidateReviewService.Verdict.PASS);
    }
    @Test void normalPathUsesExactlyOneGeneralCallThenExtractorThenFinancialAudit() {
        List<ModelGateway.Role> roles = new ArrayList<>();
        ModelGateway gateway = (request, events) -> {
            roles.add(request.role());
            assertThat(request.instructions()).contains("Runtime task contract", "system");
            return Mono.just(reply(switch (request.role()) {
                case GENERAL_VALIDATION -> """
                        {"taskCompletion":{"status":"PASS","feedback":[]},"responseRequirement":{"status":"PASS","feedback":[]},"deliverable":{"status":"PASS","feedback":[]}}
                        """;
                case CLAIM_EXTRACTION -> "{\"claims\":[]}";
                case FINANCIAL_AUDIT -> "{\"verdict\":\"PASS\",\"feedback\":[]}";
                default -> throw new AssertionError("Unexpected role");
            }));
        };
        var service = new CandidateReviewService(mapper, gateway, mock(FileGuard.class));
        assertThat(service.review(input()).block().verdict()).isEqualTo(CandidateReviewService.Verdict.PASS);
        assertThat(roles).containsExactly(ModelGateway.Role.GENERAL_VALIDATION, ModelGateway.Role.CLAIM_EXTRACTION, ModelGateway.Role.FINANCIAL_AUDIT);
    }
    @Test void incompleteReviewGetsLargerBoundedOutputBudget() {
        List<Integer> limits = new ArrayList<>();
        ModelGateway gateway = (request, events) -> {
            limits.add(request.maxOutputTokens());
            return Mono.just(new ModelGateway.Reply(AgentMessage.text("r", AgentMessage.Role.ASSISTANT, "", false), 10, request.maxOutputTokens(), "max_output_tokens", "test"));
        };
        var result = new CandidateReviewService(mapper, gateway, mock(FileGuard.class)).review(input()).block();
        assertThat(result.verdict()).isEqualTo(CandidateReviewService.Verdict.ERROR);
        assertThat(limits).containsExactly(6000, 12000);
        assertThat(result.feedback().getFirst()).contains("max_output_tokens", "outputLimit=12000");
    }
    @Test void generalFeedbackNeverRunsFinancialAudit() {
        List<ModelGateway.Role> roles = new ArrayList<>();
        ModelGateway gateway = (request, events) -> {
            roles.add(request.role());
            if(request.role()==ModelGateway.Role.CLAIM_EXTRACTION)return Mono.just(reply("{\"claims\":[]}"));
            return Mono.just(reply("""
                    {"taskCompletion":{"status":"FEEDBACK","feedback":["missing requested time window"]},"responseRequirement":{"status":"PASS","feedback":[]},"deliverable":{"status":"PASS","feedback":[]}}
                    """));
        };
        var service = new CandidateReviewService(mapper, gateway, mock(FileGuard.class));
        assertThat(service.review(input()).block().verdict()).isEqualTo(CandidateReviewService.Verdict.REVISE);
        assertThat(roles).containsExactly(ModelGateway.Role.GENERAL_VALIDATION,ModelGateway.Role.CLAIM_EXTRACTION);
    }
    @Test void technicalFailuresAreNeverTreatedAsPassAndRetriesAreBounded() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = (request, events) -> { calls.incrementAndGet(); return Mono.error(new IllegalStateException("simulated")); };
        var service = new CandidateReviewService(mapper, gateway, mock(FileGuard.class));
        assertThat(service.review(input()).block().verdict()).isEqualTo(CandidateReviewService.Verdict.ERROR);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void permanentModelFailuresReportStageWithoutRepeatingInvalidRequests(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway=(request,events)->{calls.incrementAndGet();return Mono.error(new ModelFailure(ModelFailure.Kind.CONTEXT_OVERFLOW,400));};
        var result=new CandidateReviewService(mapper,gateway,mock(FileGuard.class)).review(input()).block();
        assertThat(result.verdict()).isEqualTo(CandidateReviewService.Verdict.ERROR);
        assertThat(result.feedback().getFirst()).contains("GENERAL_VALIDATION","CONTEXT_OVERFLOW","HTTP 400");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void extractorCannotInventEvidenceAssociationAndGeneralDoesNotReceiveFullPayload() {
        ModelGateway gateway=(request,events)->{
            try {
                var payload=mapper.readTree(request.messages().getFirst().text());
                if(request.role()==ModelGateway.Role.GENERAL_VALIDATION){
                    assertThat(payload.has("evidence")).isFalse();
                    return Mono.just(reply("{\"taskCompletion\":{\"status\":\"PASS\",\"feedback\":[]},\"responseRequirement\":{\"status\":\"PASS\",\"feedback\":[]},\"deliverable\":{\"status\":\"PASS\",\"feedback\":[]}}"));
                }
                if(request.role()==ModelGateway.Role.CLAIM_EXTRACTION)return Mono.just(reply("{\"claims\":[{\"kind\":\"VIEW\",\"statement\":\"A source opinion\",\"locator\":\"candidate_answer\",\"evidence\":[\"call_wrong\",\"https://example.com/source\",\"https://invented.test/\"],\"calculation\":\"\",\"assumptions\":[],\"uncertainties\":[]}]}"));
                assertThat(payload.path("claims").path("claims").path(0).path("evidence").toString()).isEqualTo("[\"https://example.com/source\"]");
                assertThat(payload.path("original_material").has("evidence")).isTrue();
                return Mono.just(reply("{\"verdict\":\"PASS\",\"feedback\":[]}"));
            }catch(Exception e){return Mono.error(e);}
        };
        var input=new CandidateReviewService.Input("s","r","Summarize", "[Source](https://example.com/source)",List.of(),Map.of("call_actual","Opinion at https://example.com/source"),"system");
        assertThat(new CandidateReviewService(mapper,gateway,mock(FileGuard.class)).review(input).block().verdict()).isEqualTo(CandidateReviewService.Verdict.PASS);
    }

    @Test void explicitJsonOnlyFormatIsCheckedWithoutPaidReview(){
        var gateway=mock(ModelGateway.class);
        var request=new CandidateReviewService.Input("s","r","只返回 JSON","prefix {\"ok\":true}",List.of(),Map.of(),"system");
        assertThat(new CandidateReviewService(mapper,gateway,mock(FileGuard.class)).review(request).block().verdict()).isEqualTo(CandidateReviewService.Verdict.REVISE);
        verifyNoInteractions(gateway);
    }
    @Test void feedbackNoteAliasPreservesFeedbackWithoutChangingVerdict() throws Exception {
        var node=mapper.readTree("{\"responseRequirement\":{\"status\":\"FEEDBACK\",\"feedback\":[\"existing\"],\"feedback_note\":\"missing field\"}}");
        CandidateReviewService.normalizeFeedbackNotes(node);
        assertThat(node.path("responseRequirement").path("status").asText()).isEqualTo("FEEDBACK");
        assertThat(node.path("responseRequirement").path("feedback")).hasSize(2);
        assertThat(node.path("responseRequirement").has("feedback_note")).isFalse();
    }
}
