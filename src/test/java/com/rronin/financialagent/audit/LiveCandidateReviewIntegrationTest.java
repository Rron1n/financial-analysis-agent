package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.*;
import com.rronin.financialagent.model.*;
import com.rronin.financialagent.session.SessionStore;
import com.rronin.financialagent.tools.file.FileGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly requested diagnostic only: rechecks existing test research without repeating research tools. */
@EnabledIfSystemProperty(named="agent.liveReviewTest",matches="true")
class LiveCandidateReviewIntegrationTest {
    @Test void recheckPersistedCandidateWithRealReviewModels() throws Exception {
        String sessionId=System.getProperty("agent.liveReviewSession","");
        assertTrue(sessionId.matches("[a-f0-9-]{36}"),"Explicit isolated test session required");
        String key=System.getenv("DASHSCOPE_API_KEY");
        if(key==null||key.isBlank()){
            Path privateFile=Path.of(System.getProperty("user.home"),".config/financial-analysis-agent/application-secrets.yml");
            if(!Files.isRegularFile(privateFile))privateFile=Path.of(".secrets/application-secrets.yml");
            try(var input=Files.newInputStream(privateFile)){
                Map<?,?> configuration=new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                key=Objects.toString(((Map<?,?>)configuration.getOrDefault("secrets-dashscope",null)).get("api-key"),"");
            }
        }
        assertFalse(key==null||key.isBlank(),"Private API key required");
        var mapper=new ObjectMapper().findAndRegisterModules();
        var store=new SessionStore(mapper,Path.of(".test-runtime/sessions"));
        var messages=store.messages(sessionId);String originalRun=System.getProperty("agent.liveReviewRun",store.metadata(sessionId).path("lastRunId").asText());
        String candidate=messages.stream().filter(m->m.runId().equals(originalRun)&&m.role()==AgentMessage.Role.ASSISTANT&&!m.meta()&&!m.text().isBlank()).reduce((a,b)->b).orElseThrow().text();
        String userRequest=messages.stream().filter(m->m.runId().equals(originalRun)&&m.role()==AgentMessage.Role.USER&&!m.meta()).findFirst().orElseThrow().text();
        var deliverables=new ArrayList<CandidateReviewService.Deliverable>();
        Path staged=Path.of(".financial-agent/staged-reports",originalRun,"reports");
        if(Files.isDirectory(staged))try(var paths=Files.walk(staged)){paths.filter(Files::isRegularFile).forEach(path->deliverables.add(new CandidateReviewService.Deliverable(path.toAbsolutePath().toString(),"text/markdown",true)));}
        Map<String,Object> evidence=new LinkedHashMap<>();
        for(var event:store.events(sessionId))if(event.path("type").asText().equals("tool.finished"))
            evidence.put(event.path("data").path("toolCallId").asText(),event.path("data").path("result"));
        var settings=new ModelSettings("https://api.openai.com/v1",key,"qwen3.8-max","qwen3.8-flash","text-embedding-v4","medium","low",
                Duration.ofSeconds(30),Duration.ofSeconds(90),Duration.ofSeconds(300),1,1050000,400000,128000,Map.of());
        var properties=mock(AgentProperties.class);when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(Path.of(".").toAbsolutePath(),50000));
        var gateway=new OpenAiResponsesGateway(mapper,settings);gateway.setQwen(new QwenSettings("https://dashscope.aliyuncs.com/compatible-mode/v1",key));String diagnosticRun="review-test-"+UUID.randomUUID();
        try{
            var result=new CandidateReviewService(mapper,gateway,new FileGuard(properties)).review(new CandidateReviewService.Input(
                    sessionId,diagnosticRun,userRequest,candidate,deliverables,evidence,"Review diagnostic")).block(Duration.ofMinutes(8));
            assertNotNull(result);
            System.out.println("Live review verdict: "+result.verdict());
            System.out.println(result.feedback());
            assertNotEquals(CandidateReviewService.Verdict.ERROR,result.verdict(),"Review pipeline must complete technically");
        }finally{gateway.releaseRun(diagnosticRun);}
    }
}
