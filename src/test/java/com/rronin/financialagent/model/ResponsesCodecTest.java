package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResponsesCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesCodec codec = new ResponsesCodec(mapper);
    @Test void publicProgressMarkersAreNotStoredAsAnswerText() throws Exception {
        var response=mapper.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"<progress>Reading the source.</progress>\"}]}]}");
        assertThat(codec.decode("run",response).message().text()).isEmpty();
        assertThat(codec.decode("run",response).message().content().getFirst().type()).isEqualTo(AgentMessage.BlockType.PROGRESS);
    }
    @Test void progressAndCandidateRemainSeparateInTheSameResponse() throws Exception {
        var response=mapper.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"<progress>Checking existing evidence.</progress>Candidate conclusion\"}]}]}");
        var message=codec.decode("run",response).message();
        assertThat(message.text()).isEqualTo("Candidate conclusion");
        assertThat(message.content().getFirst().type()).isEqualTo(AgentMessage.BlockType.PROGRESS);
        assertThat(message.content().getFirst().text()).isEqualTo("Checking existing evidence.");
    }
    @Test void encodesMatchedSyntheticAttachmentReadWithoutProviderFieldsLeakingIntoMessages() {
        var use = new AgentMessage("m1", "run", AgentMessage.Role.ASSISTANT, true, Instant.now(),
                List.of(AgentMessage.Block.toolUse("read1", "Read", mapper.createObjectNode().put("path", "attachment.md"))));
        var result = new AgentMessage("m2", "run", AgentMessage.Role.TOOL, true, Instant.now(),
                List.of(AgentMessage.Block.toolResult("read1", "contents", false)));
        var request = new ModelGateway.Request("run", ModelGateway.Role.PRIMARY, "system", List.of(use, result), List.of(), 64_000, null);
        var body = codec.encode(request, "gpt-5.6-sol", "high", 128_000, true);
        assertThat(body.path("input").path(0).path("type").asText()).isEqualTo("function_call");
        assertThat(body.path("input").path(1).path("call_id").asText()).isEqualTo("read1");
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(64_000);
        assertThat(body.path("store").asBoolean()).isFalse();
    }
    @Test void truncatedReplyCannotBeMistakenForNormalCompletion() throws Exception {
        var reply = codec.decode("run", mapper.readTree("""
                {"status":"incomplete","model":"gpt-5.6-sol","incomplete_details":{"reason":"max_output_tokens"},
                 "usage":{"input_tokens":10,"output_tokens":64000},"output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}
                """));
        assertThat(reply.truncated()).isTrue();
        assertThat(reply.outputTokens()).isEqualTo(64_000);
    }
    @Test void malformedToolInputFailsBeforeAnyToolCanExecute() throws Exception {
        var response = mapper.readTree("""
                {"status":"completed","output":[{"type":"function_call","call_id":"x","name":"Read","arguments":"{"}]}
                """);
        assertThat(codec.decode("run", response).message().toolUses().getFirst().input().path("_invalid_function_arguments").asBoolean()).isTrue();
    }
}
