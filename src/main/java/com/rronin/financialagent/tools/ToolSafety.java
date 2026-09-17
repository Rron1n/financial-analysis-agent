package com.rronin.financialagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.rronin.financialagent.agent.ApprovalBehaviour;

public final class ToolSafety {
    private ToolSafety() { }
    public static ApprovalBehaviour publicReadAdvice(JsonNode input) {
        String text = input == null ? "" : input.toString();
        if (text.matches("(?is).*(sk-(?:proj-)?[A-Za-z0-9_-]{20,}|Bearer\\s+[A-Za-z0-9._-]{20,}|-----BEGIN.*PRIVATE KEY).*")) return ApprovalBehaviour.DENY;
        if (text.matches("(?is).*\\\"(?:password|api_key|access_token|account_id)\\\"\\s*:.*")) return ApprovalBehaviour.ASK;
        return ApprovalBehaviour.ALLOW;
    }
}
