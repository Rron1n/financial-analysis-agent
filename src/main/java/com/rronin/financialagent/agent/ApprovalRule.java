package com.rronin.financialagent.agent;

import java.util.Map;

public record ApprovalRule(ApprovalRuleSource source, ApprovalBehaviour behaviour, String toolName, Map<String, Object> ruleContent) {
    public ApprovalRule {
        source = source == null ? ApprovalRuleSource.SESSION : source;
        behaviour = behaviour == null ? ApprovalBehaviour.ASK : behaviour;
        toolName = toolName == null || toolName.isBlank() ? "*" : toolName.trim();
        ruleContent = ruleContent == null ? Map.of() : Map.copyOf(ruleContent);
    }
    public static ApprovalRule wholeTool(ApprovalRuleSource source, ApprovalBehaviour behaviour, String toolName) {
        return new ApprovalRule(source, behaviour, toolName, Map.of());
    }
}
