package com.rronin.financialagent.memory;
import com.rronin.financialagent.model.AgentMessage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class MemoryInputBudgetTest {
    @Test void fileWritesAreExcerptedWithoutLosingPathsOrToolPairing(){
        var args=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path","reports/PL/report.md").put("content","x".repeat(20000));
        var call=new AgentMessage("call-message","run",AgentMessage.Role.ASSISTANT,false,Instant.now(),List.of(AgentMessage.Block.toolUse("call","write_file",args)));
        var result=new AgentMessage("result","run",AgentMessage.Role.TOOL,false,Instant.now(),List.of(AgentMessage.Block.toolResult("call","Written",false)));
        var fitted=MemoryInputBudget.fit(List.of(call,result));
        com.rronin.financialagent.model.MessageIntegrity.validate(fitted);
        assertEquals("reports/PL/report.md",fitted.getFirst().toolUses().getFirst().input().path("path").asText());
        assertTrue(fitted.getFirst().toolUses().getFirst().input().path("content").asText().length()<2100);
        assertEquals(20000,args.path("content").asText().length());
    }
    @Test void preservesUserInstructionsAndToolIdentityWhileBoundingRawResults(){
        var user=AgentMessage.text("run",AgentMessage.Role.USER,"Keep this exact requirement",false);
        var tool=new AgentMessage("result","run",AgentMessage.Role.TOOL,false,Instant.now(),List.of(AgentMessage.Block.toolResult("call","x".repeat(20000),false)));
        var fitted=MemoryInputBudget.fit(List.of(user,tool));
        assertEquals(user,fitted.getFirst());assertEquals("call",fitted.getLast().content().getFirst().toolCallId());
        assertTrue(fitted.getLast().content().getFirst().text().length()<2000);
        assertTrue(fitted.getLast().content().getFirst().text().contains("Do not infer omitted facts"));
        assertEquals(20000,tool.content().getFirst().text().length());
    }
}
