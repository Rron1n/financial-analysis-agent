package com.rronin.financialagent.tools.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadFileToolTest {
    @TempDir Path root;
    @Test void largeTextCanBeReadToCompletionWithoutRepeatingFirstPage() throws Exception {
        Files.writeString(root.resolve("result.json"), "0123456789abcdefghij");
        var properties = mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root, 10));
        var mapper = new ObjectMapper();
        var tool = new ReadFileTool(new FileGuard(properties), mapper);
        var first = mapper.valueToTree(tool.execute(mapper.createObjectNode().put("path", "result.json")).block().data());
        assertThat(first.path("content").asText()).isEqualTo("0123456789");
        assertThat(first.path("truncated").asBoolean()).isTrue();
        var second = mapper.valueToTree(tool.execute(mapper.createObjectNode().put("path", "result.json").put("offset", first.path("nextOffset").asLong())).block().data());
        assertThat(second.path("content").asText()).isEqualTo("abcdefghij");
        assertThat(second.path("truncated").asBoolean()).isFalse();
    }
    @Test void spilledPdfTextIsUnwrappedBeforeApplyingOffsets() throws Exception {
        var mapper=new ObjectMapper();
        Files.createDirectories(root.resolve("tool-results"));
        String full=mapper.writeValueAsString(java.util.Map.of("data",java.util.Map.of("content","Revenue 123\nCash 456")));
        Files.writeString(root.resolve("tool-results/call_test.json"),mapper.writeValueAsString(java.util.Map.of("content",full)));
        var properties=mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root,100));
        var result=new ReadFileTool(new FileGuard(properties),mapper).execute(mapper.createObjectNode().put("path","tool-results/call_test.json").put("offset",12)).block();
        assertThat(mapper.valueToTree(result.data()).path("content").asText()).isEqualTo("Cash 456");
    }
    @Test void credentialFileIsRejectedBeforeRead() {
        var properties = mock(AgentProperties.class);
        when(properties.filesystem()).thenReturn(new AgentProperties.Filesystem(root, 10));
        var guard = new FileGuard(properties);
        assertThatThrownBy(() -> guard.resolve(".secrets/application-secrets.yml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.resolve(".env.production")).isInstanceOf(IllegalArgumentException.class);
    }
}
