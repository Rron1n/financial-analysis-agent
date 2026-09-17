package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.tools.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {
    private final UserApprovalRuleStore userRules = mock(UserApprovalRuleStore.class);
    private final ApprovalService service = new ApprovalService(new ApprovalPolicyEngine(), userRules);

    @Test
    void pendingRequestCarriesAskRuleAndAllowAlwaysPersistsUserAllowRule() {
        when(userRules.load()).thenReturn(List.of());
        var args = new ObjectMapper().createObjectNode().put("path", "reports/example.md");
        var pending = service.request("run-1", "write_file", args, AgentTool.Mode.MUTATING,
                "Write report", ApprovalRuleSource.USER_SETTINGS, null);

        assertThat(pending.rule().behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(pending.toolMode()).isEqualTo(AgentTool.Mode.MUTATING);
        assertThat(service.decide(pending.id(), ApprovalService.Decision.ALLOW_ALWAYS)).isTrue();
        verify(userRules).add(argThat(rule -> rule.source() == ApprovalRuleSource.USER_SETTINGS
                && rule.behaviour() == ApprovalBehaviour.ALLOW
                && rule.toolName().equals("write_file")));
    }

    @Test
    void allowOnceDoesNotCreatePersistentOrSessionGrants() {
        when(userRules.load()).thenReturn(List.of());
        var pending = service.request("run-2", "write_file", null, AgentTool.Mode.MUTATING,
                "Write scheduled report", ApprovalRuleSource.USER_SETTINGS, "scheduler-1");
        assertThat(service.decide(pending.id(), ApprovalService.Decision.ALLOW_ONCE)).isTrue();
        verify(userRules, never()).add(any());
        assertThat(service.evaluate("run-2", "write_file", null, AgentTool.Mode.MUTATING, List.of()).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
    }
}
