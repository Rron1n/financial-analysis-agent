package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.tools.file.FileGuard;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewReuseTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private CandidateReviewService.Input input(String run,String text,String request,Map<String,Object> evidence){
        return new CandidateReviewService.Input("s",run,request,text,List.of(),evidence,"system");
    }
    private String result(ModelGateway.Role role){return switch(role){
        case GENERAL_VALIDATION -> "{\"taskCompletion\":{\"status\":\"PASS\",\"feedback\":[]},\"responseRequirement\":{\"status\":\"PASS\",\"feedback\":[]},\"deliverable\":{\"status\":\"PASS\",\"feedback\":[]}}";
        case CLAIM_EXTRACTION -> "{\"claims\":[]}";
        case FINANCIAL_AUDIT -> "{\"verdict\":\"PASS\",\"feedback\":[]}";
        default -> throw new AssertionError(role);
    };}
    @Test void exactReplayReusesResultsButTaskEvidenceAndRunChangesInvalidateThem(){
        var requests=new java.util.concurrent.CopyOnWriteArrayList<ModelGateway.Request>();
        ModelGateway model=(request,events)->{requests.add(request);return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,result(request.role()),false),1,1,"completed","test"));};
        var service=new CandidateReviewService(mapper,model,mock(FileGuard.class));
        var original=input("r","Answer","question",Map.of("source","value"));
        service.review(original).block();service.review(original).block();assertThat(requests).hasSize(3);
        service.review(input("r","Answer","new question",Map.of("source","value"))).block();assertThat(requests).hasSize(6);
        service.review(input("r","Answer","new question",Map.of("source","updated"))).block();assertThat(requests).hasSize(7);
        service.releaseRun("r");service.review(input("r","Answer","new question",Map.of("source","updated"))).block();assertThat(requests).hasSize(10);
    }
    @Test void reuseNeverPromotesAnUnresolvedFailureToPass(){
        var count=new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway model=(request,events)->{
            count.incrementAndGet();
            String text=request.role()==ModelGateway.Role.FINANCIAL_AUDIT?"{\"verdict\":\"REVISE\",\"feedback\":[\"Incorrect ratio\"]}":result(request.role());
            return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,text,false),1,1,"completed","test"));
        };
        var service=new CandidateReviewService(mapper,model,mock(FileGuard.class));
        var value=input("r","Ratio 50%","q",Map.of());
        assertThat(service.review(value).block().verdict()).isEqualTo(CandidateReviewService.Verdict.REVISE);
        assertThat(service.review(value).block().verdict()).isEqualTo(CandidateReviewService.Verdict.REVISE);
        assertThat(count.get()).isEqualTo(3);
        service.review(input("another-run","Ratio 50%","q",Map.of())).block();
        assertThat(count.get()).isEqualTo(6);
    }
    @Test void revisionsCarryExactChangedLinesAndPreviousVerdictWithDependencyChecks() throws Exception {
        var requests=new java.util.concurrent.CopyOnWriteArrayList<ModelGateway.Request>();
        ModelGateway model=(request,events)->{requests.add(request);return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,result(request.role()),false),1,1,"completed","test"));};
        var service=new CandidateReviewService(mapper,model,mock(FileGuard.class));
        service.review(input("r","# A\nRatio 50%\n# B\nUnchanged","q",Map.of())).block();
        service.review(input("r","# A\nRatio 39%\n# B\nUnchanged","q",Map.of())).block();
        assertThat(requests).hasSize(6);
        for(var request:requests.subList(3,6)){
            var payload=mapper.readTree(request.messages().getFirst().text());
            var revision=payload.path("revision_context");
            assertThat(revision.path("mode").asText()).isEqualTo("delta_only");
            assertThat(revision.path("changes").get(0).path("currentText").asText()).isEqualTo("Ratio 39%");
            assertThat(payload.toString()).doesNotContain("# B\\nUnchanged");
        }
    }

    @Test void changedEvidenceKeepsUnrelatedClaimRecordsAndMarksRemovedDependencies() throws Exception {
        var docs=List.of(new CandidateReviewService.AuditTargetDocument("candidate_answer","text/markdown","A revenue 10. B revenue 20."));
        var before=ReviewReuse.snapshot(mapper,input("r","","q",Map.of("a","https://a.example revenue 10","b","https://b.example revenue 20")),docs);
        before.results().put(ModelGateway.Role.CLAIM_EXTRACTION,mapper.readTree("{\"claims\":[{\"statement\":\"A revenue 10.\",\"locator\":\"candidate_answer\",\"evidence\":[\"https://a.example\"]},{\"statement\":\"B revenue 20.\",\"locator\":\"candidate_answer\",\"evidence\":[\"https://b.example\"]}]}"));
        before.results().put(ModelGateway.Role.FINANCIAL_AUDIT,mapper.readTree("{\"verdict\":\"PASS\",\"feedback\":[]}"));
        var after=ReviewReuse.snapshot(mapper,input("r","","q",Map.of("a","https://a.example revenue 11","b","https://b.example revenue 20")),docs);
        assertThat(ReviewReuse.compatible(before,after)).isTrue();
        var ledger=ReviewReuse.ledger(mapper,before,after);
        assertThat(ledger.get(0).path("status").asText()).isEqualTo("RECHECK");
        assertThat(ledger.get(1).path("status").asText()).isEqualTo("UNCHANGED");
        var removed=ReviewReuse.snapshot(mapper,input("r","","q",Map.of("b","https://b.example revenue 20")),docs);
        assertThat(ReviewReuse.ledger(mapper,before,removed).get(0).path("status").asText()).isEqualTo("RECHECK");
        assertThat(ReviewReuse.canReuse(before,after,ModelGateway.Role.FINANCIAL_AUDIT)).isFalse();
        assertThat(ReviewReuse.canReuse(before,after,ModelGateway.Role.GENERAL_VALIDATION)).isTrue();
    }
    @Test void skippedFinancialStageKeepsItsOwnLastReviewedCandidate() throws Exception {
        var requests=new java.util.concurrent.CopyOnWriteArrayList<ModelGateway.Request>();
        ModelGateway model=(request,events)->{
            requests.add(request);String answer=result(request.role());
            if(request.role()==ModelGateway.Role.GENERAL_VALIDATION&&request.messages().getFirst().text().contains("\"text\":\"bad preamble\""))
                answer=answer.replace("\"status\":\"PASS\"","\"status\":\"FEEDBACK\"");
            return Mono.just(new ModelGateway.Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,answer,false),1,1,"completed","test"));
        };
        var service=new CandidateReviewService(mapper,model,mock(FileGuard.class));
        service.review(input("r","Original","q",Map.of())).block();
        service.review(input("r","bad preamble","q",Map.of())).block();
        service.review(input("r","Final","q",Map.of())).block();
        var financial=requests.stream().filter(r->r.role()==ModelGateway.Role.FINANCIAL_AUDIT).toList();
        assertThat(financial).hasSize(2);
        var revision=mapper.readTree(financial.getLast().messages().getFirst().text()).path("revision_context");
        assertThat(revision.path("changes").get(0).path("previousText").asText()).isEqualTo("Original");
    }
}
