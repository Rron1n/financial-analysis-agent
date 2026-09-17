# Architecture

## Agent execution

`AgentRunEngine` manages a run through model calls, tool dispatch, candidate review, and completion. Model replies use an application-owned message protocol. Tool results return to the next model call. Incoming user amendments update the current task contract at execution boundaries. Events persist for recovery and stream to the UI over SSE.

Tool calls pass through permission checks before execution. Decisions combine approval mode, explicit rules, and tool safety metadata. Denied actions are tracked to prevent repeated approval requests for the same action. Internal drafts use a separate location from published reports.

## Candidate review

1. Normalize the candidate and declared text/PDF deliverables.
2. Run deterministic checks for basic identity/content, declared files, supported JSON-only requirements, and X citation presence.
3. Run general validation and claim extraction concurrently.
4. After general validation passes and extraction succeeds, run financial audit against the supplied evidence.
5. Publish the reviewed candidate on PASS, request a bounded local correction on REVISE, or stop on BLOCK/ERROR.

General validation handles task completion and response requirements. Financial audit handles material facts, source attribution, metric scope, calculations, and reasoning. Reviewer calls do not autonomously browse: evidence is supplied by the runtime. Evidence excerpts are bounded and omissions are explicit.

Per-stage records support reuse and delta review within a run. Dependency changes and unresolved findings can broaden rechecking; this is not a guarantee of sentence-only review. In-memory records are released after the run. Latest audit results and historical audit events remain persisted separately.

## Memory and context

Session memory is extracted asynchronously and committed with a coverage cursor. Topic memory supports cross-session retrieval. Compaction reuses an existing snapshot rather than starting a hidden full-summary model call. Uncovered message groups are retained, and tool calls/results remain complete groups.

Reads and commits use per-session synchronization and snapshot checks. Routine compaction does not wait for background extraction; at the high-water boundary it may wait up to 30 seconds. Failed compaction preserves the original context when continuing is allowed.

## Operating budgets

These are application policies, not provider model capacities. Consult `TokenBudgetPolicy` and `AgentRunEngine` before changing them.

| Policy | Current value |
|---|---:|
| Cumulative run output budget | 96,000 tokens |
| Normal primary-call output | 6,000 tokens |
| Primary truncation recovery limit | 10,000 tokens |
| Review reserve | 25,000 tokens |
| General / claims / financial outputs | 6,000 / 7,000 / 12,000 tokens |
| Effective global tool-result cap | 12,000 characters |
| Run tool-result budget | 80,000 characters |
| Audit evidence budget | 60,000 characters |
| Initial routine compaction trigger | 48,000 tokens |
| Compaction target baseline | 32,000 tokens |

The next compaction trigger is `min(window - 16000, max(48000, current + 16000))`. The target adapts to fixed context and memory size. If compaction fails at or above `min(128000, window - 16000)`, the run stops. This is checked at compaction attempts, not an unconditional hard cutoff at exactly 128k.

The UI's context-token estimate is current context occupancy, not cumulative billed usage. Background memory extraction has a separate task identity. There are no benchmark claims attached to these budget choices.
