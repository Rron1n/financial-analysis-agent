package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.*;
import com.rronin.financialagent.model.EmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in only: real embedding charge and isolated local Chroma. Never runs in ordinary tests. */
@EnabledIfSystemProperty(named="agent.liveMemoryTest",matches="true")
class LiveMemoryIntegrationTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @Test void realTopicExtractionAndSessionSnapshotCommit() throws Exception {
        Path secret=Path.of(System.getProperty("user.home"),".config/financial-analysis-agent/application-secrets.yml");
        if(!Files.isRegularFile(secret))secret=Path.of(".secrets/application-secrets.yml");
        String key;
        try(var stream=Files.newInputStream(secret)){
            Map<?,?> config=new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);
            key=Objects.toString(((Map<?,?>)config.get("secrets-dashscope")).get("api-key"),"");
        }
        var settings=new ModelSettings("https://api.openai.com/v1",key,"qwen3.8-max","qwen3.8-flash","text-embedding-v4","medium","low",Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(90),0,1050000,400000,128000,Map.of());
        var mapper=new ObjectMapper().findAndRegisterModules();
        var gateway=new com.rronin.financialagent.model.OpenAiResponsesGateway(mapper,settings);
        gateway.setQwen(new QwenSettings("https://dashscope.aliyuncs.com/compatible-mode/v1",key));
        var properties=mock(AgentProperties.class);
        when(properties.memory()).thenReturn(new AgentProperties.Memory(temporary.resolve("memory"),true,false,50000,50000,30,.72));
        var store=new com.rronin.financialagent.session.SessionStore(mapper,temporary.resolve("sessions"));
        String session=store.create("Isolated memory acceptance").path("sessionId").asText(),run=UUID.randomUUID().toString();
        var user=com.rronin.financialagent.model.AgentMessage.text(run,com.rronin.financialagent.model.AgentMessage.Role.USER,"记住一个测试工作流约定：我用 Cedar Lantern 表示先核对原始文件日期，再核对单位的研究检查流程。这是工作流名称，不是股票。",false);
        store.message(session,user);
        var topics=new TopicFileStore(properties);
        new TopicExtractionService(topics,store,gateway,mapper,properties).afterCompleted(session,run);
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(store.metadata(session).path("topicMemoryCursor").asText().isBlank()&&System.nanoTime()<deadline)Thread.sleep(100);
        assertEquals(user.id(),store.metadata(session).path("topicMemoryCursor").asText(),"Topic extraction must commit its cursor");
        assertTrue(topics.list().stream().anyMatch(t->t.text().contains("Cedar Lantern")),"The model must write the new convention, not read a pre-seeded topic");
        var chroma=mock(ChromaMemoryIndex.class);
        when(chroma.searchDetailed(anyString(),anyInt())).thenReturn(reactor.core.publisher.Mono.just(ChromaMemoryIndex.SearchResponse.empty()));
        when(chroma.indexedVersion(anyString())).thenAnswer(call->topics.list().stream().filter(t->t.path().equals(call.getArgument(0))).findFirst().orElseThrow().version());
        var retrieval=new TopicRetrievalService(topics,chroma,new MemoryLanguageService(gateway,mapper));
        String recalled=retrieval.retrieve("Cedar Lantern 工作流").block(Duration.ofSeconds(10));
        assertTrue(recalled.contains("Cedar Lantern"));
        var memory=new SessionMemoryService(store,gateway,mapper,properties);
        memory.afterAssistant(session,run,List.of(user),16000,0,true);
        deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        SessionMemorySnapshot snapshot;
        do{snapshot=memory.stableSnapshot(session).block(Duration.ofSeconds(35));}while(snapshot.revisionEdition()==0&&System.nanoTime()<deadline);
        assertTrue(snapshot.revisionEdition()>0);assertEquals(user.id(),snapshot.coveredThroughMessageId());assertTrue(snapshot.body().contains("Cedar Lantern"));
    }
    @Test void realEmbeddingRoundTripsThroughCosineCollection() throws Exception {
        String key=System.getenv("DASHSCOPE_API_KEY");
        if(key==null||key.isBlank()){
            Path file=Path.of(System.getProperty("user.home"),".config/financial-analysis-agent/application-secrets.yml");
            if(!Files.isRegularFile(file))file=Path.of(".secrets/application-secrets.yml");
            try(var input=Files.newInputStream(file)){
                Map<?,?> configuration=new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                key=Objects.toString(((Map<?,?>)configuration.getOrDefault("secrets-dashscope",null)).get("api-key"),"");
            }
        }
        assertFalse(key==null||key.isBlank(),"Private API key required for explicit live test");
        var settings=new ModelSettings("https://api.openai.com/v1",key,"qwen3.8-max","qwen3.8-flash","text-embedding-v4",
                "medium","low",Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(60),1,1050000,400000,128000,Map.of());
        var mapper=new ObjectMapper();var properties=mock(AgentProperties.class);
        when(properties.chroma()).thenReturn(new AgentProperties.Chroma("http://127.0.0.1:8000",true));
        var embedding=new EmbeddingGateway(settings,mapper);embedding.setQwen(new QwenSettings("https://dashscope.aliyuncs.com/compatible-mode/v1",key));
        var chroma=new ChromaMemoryIndex(properties,WebClient.builder(),mapper,embedding);
        String id="acceptance-"+UUID.randomUUID(),document="Acceptance test: NBIS company research distinguishes dated evidence from investor sentiment.";
        try{
            chroma.index(id,document,Map.of("path","acceptance-only","version","test")).block(Duration.ofSeconds(90));
            var result=chroma.searchDetailed(document,5).block(Duration.ofSeconds(90));
            assertNotNull(result);assertFalse(result.queryEmbedding().isEmpty());
            var hit=result.hits().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
            assertEquals(document,hit.document());assertTrue(1-hit.distance()>.95,"Identical evidence should have high cosine similarity");
        }finally{chroma.delete(id).block(Duration.ofSeconds(10));}
    }
}
