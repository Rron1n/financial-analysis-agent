package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.EmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChromaMemoryIndexTest {
 @TempDir Path root;
 private AgentProperties properties(String endpoint){
  var p=mock(AgentProperties.class);
  when(p.memory()).thenReturn(new AgentProperties.Memory(root.resolve("memory"),true,false,50000,50000,30,.72));
  when(p.chroma()).thenReturn(new AgentProperties.Chroma(endpoint,true));
  return p;
 }
 @Test void restartRetainsVersionsButDifferentServerDoesNotReuseThem(){
  var embedding=mock(EmbeddingGateway.class);
  var mapper=new ObjectMapper();
  var first=new ChromaMemoryIndex(properties("http://localhost:8000"),WebClient.builder(),mapper,embedding);
  first.markIndexed("topics/user/preferences.md","version-a");
  var restarted=new ChromaMemoryIndex(properties("http://localhost:8000"),WebClient.builder(),mapper,embedding);
  assertThat(restarted.indexedVersion("topics/user/preferences.md")).isEqualTo("version-a");
  var changed=new ChromaMemoryIndex(properties("http://localhost:8001"),WebClient.builder(),mapper,embedding);
  assertThat(changed.indexedVersion("topics/user/preferences.md")).isNull();
  verifyNoInteractions(embedding);
 }
}
