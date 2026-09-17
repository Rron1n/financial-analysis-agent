package com.rronin.financialagent.agent;

import com.rronin.financialagent.model.AgentMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned by one run worker. Cancellation is the only cross-thread mutable flag. */
public final class AgentLoopState {
    public enum RunStatus { RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, INTERRUPTED, CANCELLED }
    public enum Transition { START, TOOLS_COMPLETED, COMPACTED, OUTPUT_LIMIT_RETRY, REVIEW_REVISION, RECOVERY, QUEUED_INPUT }
    public final String sessionId;
    public final String runId;
    public final Instant startedAt = Instant.now();
    public final List<AgentMessage> messages = new ArrayList<>();
    public final Map<String, AgentMessage.Block> toolResults = new LinkedHashMap<>();
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public RunStatus status = RunStatus.RUNNING;
    public Transition transition = Transition.START;
    public int loopCount;
    public int maxLoops;
    public boolean hasAttemptedReactiveCompact;
    public int consecutiveCompactionFailures;
    public int maxOutputTokensRecoveryCount;
    public int maxOutputTokens = com.rronin.financialagent.config.TokenBudgetPolicy.PRIMARY_OUTPUT;
    public long outputTokens;
    public long lastInputTokens;
    public long estimatedNewTokens;
    public long toolResultChars;
    public long nextCompactionAt = 48_000;
    public int reviewRevisionCount;
    public long memoryRevisionEdition;
    public String coveredThroughMessageId;
    public String pendingApprovalId;
    public com.rronin.financialagent.tools.AgentTool.ApprovalContext toolUseContext;
    public final List<String> uncertainSideEffectCalls = new ArrayList<>();

    public AgentLoopState(String sessionId, String runId, int maxLoops) {
        if (sessionId == null || runId == null || maxLoops < 1) throw new IllegalArgumentException("Invalid run state");
        this.sessionId = sessionId;
        this.runId = runId;
        this.maxLoops = maxLoops;
    }
    public int remainingOutputTokens() { return (int) Math.max(0, com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT - outputTokens); }
    public int requestOutputLimit(int modelOutputLimit) {
        return Math.min(Math.min(maxOutputTokens, modelOutputLimit), Math.max(0,remainingOutputTokens()-com.rronin.financialagent.config.TokenBudgetPolicy.REVIEW_RESERVE));
    }
    public boolean terminal() {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }
}
