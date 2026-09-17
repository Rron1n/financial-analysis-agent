package com.rronin.financialagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class InterruptedRunRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void durableFinishedResultIsRestoredWithoutRepeatingTheTool() throws Exception {
        var use = new AgentMessage("m", "r", AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("order", "place_order", mapper.createObjectNode())));
        var events = List.of(mapper.readTree("{\"type\":\"run.running\",\"runId\":\"r\"}"),
                mapper.readTree("{\"type\":\"tool.finished\",\"runId\":\"r\",\"data\":{\"toolCallId\":\"order\",\"result\":{\"success\":true,\"orderId\":\"known\"}}}"));
        var plan = new InterruptedRunRecovery().plan(events, List.of(use)).orElseThrow();
        assertThat(plan.uncertainCalls()).isEmpty();
        assertThat(plan.messages().getLast().content().getFirst().text()).contains("known");
        MessageIntegrity.validate(plan.messages());
    }
    @Test void unknownSideEffectIsPairedButNeverAutomaticallyReplayed() throws Exception {
        var use = new AgentMessage("m", "r", AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("order", "place_order", mapper.createObjectNode())));
        var events = List.of(mapper.readTree("{\"type\":\"run.running\",\"runId\":\"r\"}"));
        var plan = new InterruptedRunRecovery().plan(events, List.of(use)).orElseThrow();
        assertThat(plan.runId()).isEqualTo("r");
        assertThat(plan.uncertainCalls()).containsExactly("order");
        assertThat(plan.messages().getLast().content().getFirst().text()).contains("UNKNOWN", "Do not replay");
        assertThatCode(() -> MessageIntegrity.validate(plan.messages())).doesNotThrowAnyException();
    }
    @Test void terminalEventPreventsRecoveryEvenIfMetadataWasNotUpdated() throws Exception {
        var events = List.of(mapper.readTree("{\"type\":\"run.running\",\"runId\":\"r\"}"),
                mapper.readTree("{\"type\":\"run.completed\",\"runId\":\"r\"}"));
        assertThat(new InterruptedRunRecovery().plan(events, List.of())).isEmpty();
    }
    @Test void approvalRestoresTheOriginalCallInsteadOfSynthesizingAnUnknownResult() throws Exception {
        var use = new AgentMessage("m", "r", AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("order", "place_order", mapper.createObjectNode().put("quantity", 1))));
        var events = List.of(mapper.readTree("{\"type\":\"run.waiting_approval\",\"runId\":\"r\"}"),
                mapper.readTree("{\"type\":\"approval.requested\",\"data\":{\"toolCallId\":\"order\",\"input\":{\"quantity\":1}}}"));
        var plan = new InterruptedRunRecovery().plan(events, List.of(use)).orElseThrow();
        assertThat(plan.uncertainCalls()).isEmpty();
        assertThat(plan.pendingApproval().path("input").path("quantity").asInt()).isEqualTo(1);
        assertThat(plan.messages()).hasSize(1);
    }
}
