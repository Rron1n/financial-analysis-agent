package com.rronin.financialagent.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ToolResultCompactionTest {
 @TempDir Path root;
 @Test void newFileReadsRemainReadableAfterOldResultsAreCompacted() throws Exception {
  var store=new SessionStore(new ObjectMapper().findAndRegisterModules(),root);
  String session=store.create("compaction").path("sessionId").asText();
  var budget=new ToolResultBudget(store,session);
  var old=message("old","x".repeat(7000));
  var compacted=budget.compactResults(List.of(old));
  assertTrue(compacted.getFirst().content().getFirst().text().startsWith("[Full tool result:"));
  String page="New source page "+"a".repeat(5000);
  budget.add("new",page,12000,false);
  var all=new ArrayList<>(compacted);all.add(message("new",page));
  var fitted=budget.fit(all);
  assertEquals(page,fitted.getLast().content().getFirst().text());
  assertEquals(page,budget.fit(fitted).getLast().content().getFirst().text());
 }
 private AgentMessage message(String id,String text){return new AgentMessage(id,"run",AgentMessage.Role.TOOL,false,Instant.now(),List.of(AgentMessage.Block.toolResult(id,text,false)));}
}
