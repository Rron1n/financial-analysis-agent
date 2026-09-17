package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecallServiceTest {
    @Test void diversityPrefersDifferentEvidenceWhenRelevanceIsComparable(){
        var scores=Map.of("first",1.0,"duplicate",.95,"different",.9);
        var vectors=Map.of("first",List.of(1.0,0.0),"duplicate",List.of(1.0,0.0),"different",List.of(0.0,1.0));
        assertEquals(List.of("first","different"),SessionRecallService.diverse(List.of("first","duplicate","different"),scores,vectors,2));
    }
    @Test void timeDecayKeepsSpecifiedFloorAndHalfLife() {
        var now=Instant.parse("2026-09-06T00:00:00Z");
        assertEquals(1,SessionRecallService.temporalScore(now,now));
        assertEquals(.675,SessionRecallService.temporalScore(now.minusSeconds(180L*86400),now),1e-9);
        assertEquals(.35,SessionRecallService.temporalScore(now.minusSeconds(18000L*86400),now),1e-9);
    }
    @Test void budgetPrioritizesPrimaryExcerptsOverSecondaryOnes() {
        var a=new SessionRecallService.Hit("a","A","v","1","2",2,0,"first",false);
        var secondary=new SessionRecallService.Hit("a","A","v","3","4",2,0,"extra",false);
        var b=new SessionRecallService.Hit("b","B","v","1","2",2,0,"other",false);
        assertEquals(List.of(a,b),SessionRecallService.withinBudget(List.of(a,secondary,b),10));
    }
    @Test void exchangesRetainToolIdentityAndResults() {
        var user=AgentMessage.text("run",AgentMessage.Role.USER,"Recall NBIS",false);
        var call=new AgentMessage("call-message","run",AgentMessage.Role.ASSISTANT,false,Instant.now(),List.of(
                AgentMessage.Block.toolUse("call-1","research",new ObjectMapper().createObjectNode().put("ticker","NBIS"))));
        var result=new AgentMessage("result-message","run",AgentMessage.Role.TOOL,false,Instant.now(),List.of(
                AgentMessage.Block.toolResult("call-1","Evidence unavailable",true)));
        var answer=AgentMessage.text("run",AgentMessage.Role.ASSISTANT,"No verified evidence",false);
        var next=AgentMessage.text("next",AgentMessage.Role.USER,"Another question",false);
        var units=SessionRecallService.units(List.of(user,call,result,answer,next));
        assertEquals(2,units.size());
        assertEquals(user.id(),units.getFirst().firstMessageId());
        assertEquals(answer.id(),units.getFirst().lastMessageId());
        assertTrue(units.getFirst().text().contains("tool=research"));
        assertEquals(2,units.getFirst().text().split("call=call-1",-1).length-1);
        assertTrue(units.getFirst().text().contains("[error] Evidence unavailable"));
    }
    @Test void metaMessagesDoNotSplitAnExchange() {
        var user=AgentMessage.text("run",AgentMessage.Role.USER,"Question",false);
        var meta=AgentMessage.text("run",AgentMessage.Role.USER,"Historical context",true);
        assertEquals(1,SessionRecallService.units(List.of(user,meta)).size());
        assertTrue(SessionRecallService.units(List.of()).isEmpty());
    }
    @Test void lexicalGateAcceptsTickerAndDatesButRejectsGenericSingleWord(){
        Instant from=Instant.parse("2026-09-01T00:00:00Z"),to=Instant.parse("2026-09-03T00:00:00Z");
        assertTrue(SessionRecallService.lexicalEligible("NBIS","Cloud thesis","NBIS capacity analysis",from,to));
        assertTrue(SessionRecallService.lexicalEligible("2026-09-02","Cloud thesis","capacity",from,to));
        assertFalse(SessionRecallService.lexicalEligible("2026-09-05","Cloud thesis","capacity",from,to));
        assertFalse(SessionRecallService.lexicalEligible("analysis unrelated","Cloud thesis","capacity analysis",from,to));
    }
}
