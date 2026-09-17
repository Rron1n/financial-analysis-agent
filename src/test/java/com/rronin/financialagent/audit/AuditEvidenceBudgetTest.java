package com.rronin.financialagent.audit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AuditEvidenceBudgetTest {
    @Test void recentSnapshotSurvivesCrowdedHistoricalEvidence() {
        var evidence=new LinkedHashMap<String,Object>();
        for(int i=0;i<30;i++)evidence.put("old-"+i,Map.of("tool","mcp__ibkr__get_account_summary","data","old snapshot ".repeat(1000)));
        evidence.put("latest",Map.of("tool","mcp__ibkr__get_account_summary","net_liquidation",4227.945597340037));
        var input=new CandidateReviewService.Input("s","r","q","answer",List.of(),evidence,"");
        var packed=AuditEvidenceBudget.pack(new ObjectMapper(),input.evidence(),"answer",3000);
        assertEquals("latest",packed.path("entries").fieldNames().next());
        assertTrue(packed.path("entries").path("latest").path("excerpt").asText().contains("4227.945597340037"));
        assertFalse(packed.path("entries").path("latest").path("truncated").asBoolean());
        assertTrue(packed.path("omittedEntries").asInt()>0);
    }
    @Test void keepsExactCitationContextAndLabelsMissingMaterial(){
        String source="header "+"irrelevant ".repeat(2000)+"Author A said cautiously optimistic https://x.com/example/status/123 on 2026-09-06. Risks remain.";
        String excerpt=AuditEvidenceBudget.excerpt(source,Set.of("https://x.com/example/status/123"),3000);
        assertTrue(excerpt.contains("Author A said cautiously optimistic"));assertTrue(excerpt.contains("Risks remain"));
        assertTrue(excerpt.contains("source omitted"));assertTrue(excerpt.length()<=3000);
    }
    @Test void aggregateBudgetIsBoundedWithoutPretendingFullEvidenceWasReviewed(){
        Map<String,Object> evidence=new LinkedHashMap<>();for(int i=0;i<50;i++)evidence.put("call-"+i,"quoted evidence ".repeat(1000));
        var packed=AuditEvidenceBudget.pack(new ObjectMapper(),evidence,"candidate",60000);
        assertTrue(packed.toString().length()<=60000);assertTrue(packed.path("entries").path("call-49").path("truncated").asBoolean());
        assertTrue(packed.path("notice").asText().contains("require REVISE"));
    }
}
