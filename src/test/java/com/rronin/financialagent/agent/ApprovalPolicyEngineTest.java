package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.tools.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPolicyEngineTest {
    private final ApprovalPolicyEngine engine = new ApprovalPolicyEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fallsBackToToolModeWhenNoRuleMatches() {
        assertThat(engine.evaluate("web_search", mapper.createObjectNode(), AgentTool.Mode.READ_ONLY, List.of()).behaviour())
                .isEqualTo(ApprovalBehaviour.ALLOW);
        assertThat(engine.evaluate("write_file", mapper.createObjectNode(), AgentTool.Mode.MUTATING, List.of()).behaviour())
                .isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate("browser", mapper.createObjectNode(), AgentTool.Mode.INTERACTIVE, List.of()).behaviour())
                .isEqualTo(ApprovalBehaviour.ASK);
    }

    @Test
    void behaviourPriorityIsDenyThenAskThenAllow() {
        List<ApprovalRule> rules = List.of(
                ApprovalRule.wholeTool(ApprovalRuleSource.SESSION, ApprovalBehaviour.ALLOW, "write_file"),
                ApprovalRule.wholeTool(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.ASK, "write_file"),
                ApprovalRule.wholeTool(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.DENY, "write_file")
        );
        var result = engine.evaluate("write_file", mapper.createObjectNode(), AgentTool.Mode.MUTATING, rules);
        assertThat(result.behaviour()).isEqualTo(ApprovalBehaviour.DENY);
        assertThat(result.matchedRule().source()).isEqualTo(ApprovalRuleSource.USER_SETTINGS);
    }

    @Test
    void contentRuleOnlyMatchesItsArguments() {
        ApprovalRule rule = new ApprovalRule(ApprovalRuleSource.SESSION, ApprovalBehaviour.ALLOW,
                "write_file", Map.of("pathPattern", "reports/company-research/**"));
        var allowed = engine.evaluate("write_file", mapper.createObjectNode().put("path", "reports/company-research/NBIS.md"),
                AgentTool.Mode.MUTATING, List.of(rule));
        var outside = engine.evaluate("write_file", mapper.createObjectNode().put("path", "memory/private.md"),
                AgentTool.Mode.MUTATING, List.of(rule));
        assertThat(allowed.behaviour()).isEqualTo(ApprovalBehaviour.ALLOW);
        assertThat(outside.behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(outside.fallback()).isTrue();
    }

    @Test
    void supportsExactAndServerWideMcpRules() {
        ApprovalRule wildcard = ApprovalRule.wholeTool(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.ASK, "mcp__ibkr__*");
        ApprovalRule exact = ApprovalRule.wholeTool(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.DENY,
                "mcp__ibkr__place_order");
        assertThat(engine.evaluate("mcp__ibkr__positions", mapper.createObjectNode(), AgentTool.Mode.READ_ONLY,
                List.of(wildcard)).behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(engine.evaluate("mcp__ibkr__place_order", mapper.createObjectNode(), AgentTool.Mode.INTERACTIVE,
                List.of(wildcard, exact)).behaviour()).isEqualTo(ApprovalBehaviour.DENY);
    }

    @Test
    void askRuleOverridesAllowRegardlessOfRuleSource() {
        List<ApprovalRule> rules = List.of(
                ApprovalRule.wholeTool(ApprovalRuleSource.USER_SETTINGS, ApprovalBehaviour.ALLOW, "write_file"),
                new ApprovalRule(ApprovalRuleSource.SESSION, ApprovalBehaviour.ASK, "write_file",
                        Map.of("pathPattern", "reports/restricted/**"))
        );
        var restricted = engine.evaluate("write_file",
                mapper.createObjectNode().put("path", "reports/restricted/test.md"),
                AgentTool.Mode.MUTATING, rules);
        var ordinary = engine.evaluate("write_file",
                mapper.createObjectNode().put("path", "reports/ordinary/test.md"),
                AgentTool.Mode.MUTATING, rules);
        assertThat(restricted.behaviour()).isEqualTo(ApprovalBehaviour.ASK);
        assertThat(ordinary.behaviour()).isEqualTo(ApprovalBehaviour.ALLOW);
    }
}
