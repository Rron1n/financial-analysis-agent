package com.rronin.financialagent.memory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.ToolResultBudget;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class HistoricalCompactionTest {
 @TempDir Path root;
 @Test void largeHistoricalResultsCanBeCompactedWithoutLosingPairingOrSource() throws Exception {
  var mapper=new ObjectMapper().findAndRegisterModules();var store=new SessionStore(mapper,root);
  var id=store.create("fixture").path("sessionId").asText();var messages=new ArrayList<AgentMessage>();
  var user=AgentMessage.text("r",AgentMessage.Role.USER,"Research",false);messages.add(user);
  for(int i=0;i<8;i++){
   messages.add(new AgentMessage("m"+i,"r",AgentMessage.Role.ASSISTANT,false,Instant.now(),List.of(AgentMessage.Block.toolUse("t"+i,"lookup",mapper.createObjectNode()))));
   messages.add(new AgentMessage("result"+i,"r",AgentMessage.Role.TOOL,false,Instant.now(),List.of(AgentMessage.Block.toolResult("t"+i,"evidence".repeat(4000),false))));
  }
  messages.add(AgentMessage.text("r2",AgentMessage.Role.USER,"Continue",false));
  var snapshot=new SessionMemorySnapshot(1,user.id(),"Earlier request summarized");
  String diagnostic=System.getProperty("agent.compactionSession","");
  if(!diagnostic.isBlank()){
   var source=Path.of(diagnostic);snapshot=SessionMemorySnapshot.parse(Files.readString(source.resolve("session-memory.md")));messages.clear();
   for(String line:Files.readAllLines(source.resolve("transcript.jsonl"))){var event=mapper.readTree(line);if("message.created".equals(event.path("type").asText()))messages.add(mapper.treeToValue(event.path("data"),AgentMessage.class));}
  }
  var reduced=new ToolResultBudget(store,id).compactResults(messages);MessageIntegrity.validate(reduced);
  var boundary=new SessionMemoryCompactor().compact(snapshot,reduced,20000,34000);
  assertThat(boundary.estimatedTokens()).isLessThan(34000);
  assertThat(boundary.retained()).anyMatch(m->m.id().equals(messages.getLast().id()));
  assertThat(reduced.stream().flatMap(m->m.content().stream()).filter(b->b.type()==AgentMessage.BlockType.TOOL_RESULT)).anyMatch(b->b.text().startsWith("[Full tool result:"));
 }
}
