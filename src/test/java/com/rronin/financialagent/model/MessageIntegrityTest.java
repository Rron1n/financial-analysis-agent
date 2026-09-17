package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MessageIntegrityTest {
    private AgentMessage use() {
        return new AgentMessage("m1", "run", AgentMessage.Role.ASSISTANT, false, Instant.now(),
                List.of(AgentMessage.Block.toolUse("t1", "Read", new ObjectMapper().createObjectNode())));
    }
    private AgentMessage result(String id) {
        return new AgentMessage("m2", "run", AgentMessage.Role.TOOL, false, Instant.now(),
                List.of(AgentMessage.Block.toolResult(id, "data", false)));
    }
    @Test void acceptsPairedCalls() { assertThatCode(() -> MessageIntegrity.validate(List.of(use(), result("t1")))).doesNotThrowAnyException(); }
    @Test void rejectsOrphanAndMissingResults() {
        assertThatThrownBy(() -> MessageIntegrity.validate(List.of(result("missing")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageIntegrity.validate(List.of(use()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void queuedMessageCannotSplitAGroup() {
        var user = AgentMessage.text("run", AgentMessage.Role.USER, "追加请求", true);
        assertThatThrownBy(() -> MessageIntegrity.validate(List.of(use(), user, result("t1")))).isInstanceOf(IllegalArgumentException.class);
    }
}
