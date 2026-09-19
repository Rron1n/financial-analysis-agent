# Code guide

Use this guide to connect user-facing capabilities to the implementation. The project has one primary agent execution loop with specialized auxiliary model calls; tools and skills do not each create a separate autonomous agent.

## Suggested reading order

1. Read the six responsibilities below.
2. Follow one run through `AgentRunEngine`.
3. Inspect the associated tests before changing behavior.
4. Read [Behavior and limits](behavior-and-limits.md) for contracts that are deliberately narrower than the feature names.

## Six responsibilities

| Area | Main source | Starting test | What to look for |
|---|---|---|---|
| Agent Loop | [AgentRunEngine](../src/main/java/com/rronin/financialagent/agent/AgentRunEngine.java) | [AgentRunEngineTest](../src/test/java/com/rronin/financialagent/agent/AgentRunEngineTest.java) | Model calls, safe tool groups, user amendments, review, completion. |
| Memory System | [TopicRetrievalService](../src/main/java/com/rronin/financialagent/memory/TopicRetrievalService.java) | [TopicRetrievalServiceTest](../src/test/java/com/rronin/financialagent/memory/TopicRetrievalServiceTest.java) | Keyword/vector ranking and context injection; follow SessionMemoryService and SessionMemoryCompactor for extraction and compaction. |
| Financial tools and skills | [ToolRegistry](../src/main/java/com/rronin/financialagent/tools/ToolRegistry.java) | [SkillRegistryTest](../src/test/java/com/rronin/financialagent/skills/SkillRegistryTest.java) | Registered tools and task-specific skill instructions. |
| Approvals and safety | [ApprovalPolicyEngine](../src/main/java/com/rronin/financialagent/agent/ApprovalPolicyEngine.java) | [ApprovalPolicyEngineTest](../src/test/java/com/rronin/financialagent/agent/ApprovalPolicyEngineTest.java) | Permission decisions before tool execution. |
| Financial answer review | [CandidateReviewService](../src/main/java/com/rronin/financialagent/audit/CandidateReviewService.java) | [CandidateReviewServiceTest](../src/test/java/com/rronin/financialagent/audit/CandidateReviewServiceTest.java) | Deterministic checks, parallel preparation, audit and revisions. |
| Scheduling and events | [EventOutcomeService](../src/main/java/com/rronin/financialagent/events/EventOutcomeService.java) | [EventOutcomeServiceTest](../src/test/java/com/rronin/financialagent/events/EventOutcomeServiceTest.java) | Post-release checks; see schedulers for task lifecycle. |

## Follow a request

1. A run receives the user request and current session state.
2. The engine builds context, checks budgets, and attempts compaction when due.
3. The model returns a response; complete tool requests are validated and evaluated for approval.
4. Consecutive concurrency-safe tool calls form groups with up to six concurrent executions. Unsafe calls form serial boundaries; not all tools run in parallel.
5. Tool results become messages for the next iteration. Large results are represented by previews and file references.
6. A candidate answer enters review. Feedback can trigger another bounded iteration; a successful review permits completion.

Mid-run amendments are consumed at runtime boundaries, not injected retroactively into a request already sent to a provider. Tool permission decisions are enforced by runtime code, not just prompt wording.

## Memory entry points

- [SessionMemoryService](../src/main/java/com/rronin/financialagent/memory/SessionMemoryService.java): Serialized per-session background extraction and revision-checked commit.
- [SessionMemoryCompactor](../src/main/java/com/rronin/financialagent/memory/SessionMemoryCompactor.java): Coverage cursor, complete tool groups and safe retained history.
- [TopicFileStore](../src/main/java/com/rronin/financialagent/memory/TopicFileStore.java): Durable topic documents.
- [ChromaMemoryIndex](../src/main/java/com/rronin/financialagent/memory/ChromaMemoryIndex.java): Vector index access.
- [TopicRetrievalService](../src/main/java/com/rronin/financialagent/memory/TopicRetrievalService.java): Keyword and vector rank fusion, freshness weighting and diversity selection.
- [SessionStore](../src/main/java/com/rronin/financialagent/session/SessionStore.java): Persistent messages and audit events.

## Skills and integrations

- [company-research](../src/main/java/com/rronin/financialagent/skills/company-research/SKILL.md)
- [x-research](../src/main/java/com/rronin/financialagent/skills/x-research/SKILL.md)
- [portfolio-news-radar](../src/main/java/com/rronin/financialagent/skills/portfolio-news-radar/SKILL.md)

MCP tools are discovered and proxied through `McpDiscoveryService` and `McpTool`. Local tools expose input schemas and safety information through `AgentTool`. A skill provides reusable research guidance; it does not replace runtime permissions.

## Verification

See [Testing](testing.md) for offline checks and live-integration boundaries. Tests document concrete behaviors, but passing tests are not evidence of a universal financial-accuracy or latency guarantee.
