package com.rronin.financialagent.schedulers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledPromptStoreTest {
    @TempDir Path tempDir;

    @Test
    void migratesLegacySchedulerApprovalPolicyAndRemovesOldFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentProperties properties = mock(AgentProperties.class);
        when(properties.scheduler()).thenReturn(new AgentProperties.Scheduler(false, "", "", tempDir));
        Files.writeString(tempDir.resolve("scheduled-prompts.json"), """
                [{
                  "id":"scheduler-1","title":"Daily Portfolio Brief","prompt":"Run it",
                  "frequency":"daily","dayOfWeek":"SUN","time":"09:00","cron":"0 0 9 * * *",
                  "zone":"Asia/Shanghai","enabled":true,"status":"IDLE","skills":[],
                  "approvalPolicy":{"mode":"CHATGPT","preApprovedTools":["write_file","web_search"],
                    "allowedWritePathPatterns":["reports/portfolio-news-radar/*.md"]},
                  "outputPolicy":{"type":"file_output","completeWhen":"file_written",
                    "pathPattern":"reports/portfolio-news-radar/*.md"},"maxRuntimeMinutes":8
                }]
                """);

        ScheduledPromptStore store = new ScheduledPromptStore(mapper, properties);
        assertThat(store.load()).hasSize(1);

        JsonNode saved = mapper.readTree(tempDir.resolve("schedulers.json").toFile());
        JsonNode persistent=saved.get(0);
        assertThat(persistent.path("type").asText()).isEqualTo("PERSISTENT");
        assertThat(persistent.path("schedule").path("cron").asText()).isEqualTo("0 0 9 * * *");
        assertThat(persistent.path("execution").path("approvalMode").asText()).isEqualTo("AUTO");
        assertThat(persistent.has("approvalPolicy")).isFalse();
        assertThat(persistent.toString()).doesNotContain("preApprovedTools", "allowedWritePathPatterns", "SCHEDULER");
        assertThat(store.load()).singleElement().extracting(ScheduledPrompt::title).isEqualTo("Daily Portfolio Brief");
    }
}
