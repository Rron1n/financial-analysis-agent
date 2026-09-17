package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.tools.AgentTool;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolApprovalPriorityTest {
    private final ApprovalPolicyEngine engine = new ApprovalPolicyEngine();
    private final com.fasterxml.jackson.databind.JsonNode args = new ObjectMapper().createObjectNode();
    private final AgentTool.ApprovalContext context = new AgentTool.ApprovalContext("session", "run", List.of(), "request");
    private AgentTool tool(ApprovalBehaviour advice, boolean human, boolean destructive) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("test");
        when(tool.evaluateApproval(args, context)).thenReturn(Optional.ofNullable(advice));
        when(tool.requiresUserInteraction()).thenReturn(human);
        when(tool.isDestructive(args)).thenReturn(destructive);
        return tool;
    }
    private ApprovalRule rule(ApprovalRuleSource source, ApprovalBehaviour behaviour) {
        return ApprovalRule.wholeTool(source, behaviour, "test");
    }
    @Test void sourceDoesNotOverrideRiskPriority() {
        for (var source : List.of(ApprovalRuleSource.SESSION, ApprovalRuleSource.USER_SETTINGS)) {
            var other = source == ApprovalRuleSource.SESSION ? ApprovalRuleSource.USER_SETTINGS : ApprovalRuleSource.SESSION;
            var rules = List.of(rule(source, ApprovalBehaviour.ASK), rule(other, ApprovalBehaviour.ALLOW));
            assertThat(engine.evaluate(tool(ApprovalBehaviour.ALLOW, false, false), args, context, rules).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        }
    }
    @Test void denyAlwaysWinsAndMandatoryInteractionCannotBeAllowedByRules() {
        var allow = List.of(rule(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.ALLOW));
        assertThat(engine.evaluate(tool(ApprovalBehaviour.DENY, true, true), args, context, allow).behaviour()).isEqualTo(ApprovalBehaviour.DENY);
        assertThat(engine.evaluate(tool(ApprovalBehaviour.ALLOW, true, false), args, context, allow).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate(tool(ApprovalBehaviour.ALLOW, false, true), args, context, allow).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate(tool(ApprovalBehaviour.ALLOW, false, false), args, context,
                List.of(rule(ApprovalRuleSource.SESSION, ApprovalBehaviour.DENY))).behaviour()).isEqualTo(ApprovalBehaviour.DENY);
    }
    @Test void passthroughDefaultsToAskAndAdviceAskBeatsAllowRule() {
        assertThat(engine.evaluate(tool(null, false, false), args, context, List.of()).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate(tool(ApprovalBehaviour.ASK, false, false), args, context,
                List.of(rule(ApprovalRuleSource.SESSION, ApprovalBehaviour.ALLOW))).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate(tool(ApprovalBehaviour.ALLOW, false, false), args, context, List.of()).behaviour()).isEqualTo(ApprovalBehaviour.ALLOW);
    }
}
