package com.rronin.financialagent.schedulers;

import com.rronin.financialagent.agent.ApprovalRule;
import com.rronin.financialagent.agent.ApprovalRuleSource;
import com.rronin.financialagent.tools.AgentTool;

import java.time.Instant;
import java.util.Map;

public record SchedulerPendingApproval(
        String id,
        String runId,
        ApprovalRuleSource targetSource,
        String scopeId,
        ApprovalRule rule,
        AgentTool.Mode toolMode,
        Map<String, Object> arguments,
        String summary,
        Instant createdAt
) { }
