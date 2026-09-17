package com.rronin.financialagent.schedulers;

import com.rronin.financialagent.agent.OutputPolicy;

import java.time.Instant;
import java.util.List;

public record ScheduledPrompt(
        String id,
        String title,
        String prompt,
        String frequency,
        String dayOfWeek,
        String time,
        String cron,
        String zone,
        boolean enabled,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastRunStatus,
        String lastResultPreview,
        String lastError,
        Integer consecutiveFailures,
        Integer maxRetries,
        Integer retryDelaySeconds,
        String pausedReason,
        List<String> skills,
        SchedulerApprovalPolicy approvalPolicy,
        OutputPolicy outputPolicy,
        Integer maxRuntimeMinutes,
        SchedulerPendingApproval pendingApproval
) {}
