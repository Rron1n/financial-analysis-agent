# Testing

## Offline checks

Requirements: JDK 21, Maven, and Node.js for renderer tests. Maven may need network access to download dependencies; live provider credentials are not required for the default test suite.

```bash
mvn test
node --check src/main/resources/static/session-ui.js
node --check src/main/resources/static/ui-polish.js
node --test src/test/answer-renderer.test.cjs
```

Inspect `target/surefire-reports` for Java test results. Some live or local-snapshot tests are intentionally skipped by default. Do not interpret a skipped check as a successful live integration test.

## Test areas

| Area | Representative tests |
|---|---|
| Run control | AgentRunEngineTest, AgentLoopStateTest, MidRunMessageQueueTest |
| Permissions | ApprovalPolicyEngineTest, ToolApprovalPriorityTest, InternalDraftApprovalTest |
| Memory | SessionMemoryServiceTest, SessionMemoryCompactorTest, TopicRetrievalServiceTest |
| Review | CandidateReviewServiceTest, ReviewReuseTest, ReviewDeltaTest, AuditEvidenceBudgetTest |
| Persistence | SessionStoreTest, InterruptedRunRecoveryTest |
| Scheduling | ScheduleTimingTest, TemporarySchedulerServiceTest, SchedulerNotificationServiceTest |
| Events | UpcomingEventServiceTest, EventOutcomeServiceTest |

## Optional local acceptance

With your own credentials, start the application and try a short company overview. Observe tool activity and final review. Add a requirement during execution to check amendment handling. Reopen the session to inspect persisted history. For file testing, use synthetic or public documents only.

Live tests and scheduled research may incur charges. Inspect the opt-in conditions in LiveMemoryIntegrationTest and LiveCandidateReviewIntegrationTest before enabling them. Never commit output containing credentials, personal account data, private documents or real transcripts.

## Reporting results

Keep offline test results, live acceptance, financial correctness, and latency measurements separate. A successful mock-based test does not prove provider availability. Do not infer performance improvements from the number of architectural optimizations alone.
