# Behavior and limits

This document describes the checked-in implementation. Configuration and code take precedence over earlier design drafts.

## Execution and dependencies

The application owns the loop and provider gateway. Spring AI is a dependency, but the main loop is not a Spring AI Alibaba StateGraph workflow. Provider model identifiers are configurable and may not be available on every account. External tools require the user's credentials and entitlements.

## Review ordering and responsibility

`normalize → deterministic checks → [general validation || claim extraction] → financial audit → completion or revision`

Financial audit only starts after general validation passes and claims are available. The three semantic general validators share one model call. Review requests have no browsing/file tools of their own: the runtime supplies evidence.

Deterministic checks currently cover nonempty candidate/identifiers, declared nonempty files, recognized JSON-only output requests, and the presence of original-post links in supported X-research cases. They are not a universal validator for arbitrary schemas, sections, citations, or financial calculations.

The general stage checks completion, response requirements and deliverables. Financial audit checks material facts, metric scope, arithmetic, source attribution and conclusions. Prompt instructions accept harmless rounding and clearly labeled legitimate reporting bases; they do not provide a mathematical guarantee of correctness.

REVISE requests a local correction; at most two primary revisions are allowed. ERROR is a technical review failure, not a factual verdict. The structured-review layer permits one extra retry for retryable errors, separate from provider transport retries. Unreviewed candidates are not automatically released after a failure.

## Review state versus durable records

- Per-stage reuse snapshots live in memory for one run and are released at its end.
- `runs/<run-id>.audit.json` stores the latest review result, including unsuccessful outcomes.
- Appended `audit.completed` events preserve review history separately.

Reuse is conditional. Evidence changes, unresolved failures, calculations, conclusions and uncertain anchoring can expand the recheck scope. Do not assume unchanged sentences always bypass financial review.

## Token accounting

The 96k run budget counts model output recorded under the run identity, including its review calls. It is not the sum of input and output tokens. The 25k review reserve is preserved when sizing primary calls; it does not create a fresh 25k allocation each loop or guarantee unlimited revisions.

The UI displays estimated current primary-context occupancy. It includes retained messages, tool schemas/results and injected context, rather than summing every auxiliary model's input. Background memory extraction has its own task identity.

The global effective single-tool limit is 12k characters, combined with each tool's declaration by taking the smaller value. Declarations above 12k do not raise this limit. Large results use previews and recoverable file references; the run-level tool-result budget is 80k characters.

## Compaction

Routine compaction begins around 48k tokens and uses committed session memory rather than a new full-summary model call. After an attempt, the trigger moves to `min(window - 16000, max(48000, current + 16000))`.

The result target is `min(window - 16000, max(32000, fixedContext + memoryBody + 12000))`. The 32k baseline is not the trigger and is not an exact resulting size. Uncovered complete message groups cannot be discarded just to reach the target. The preferred 10k/five-text-message tail is budget-dependent, not unconditional.

Routine reads do not wait for extraction. At the high-water boundary, a read may wait up to 30 seconds. Failed compaction retains the original context when continuing is allowed. A failure at or above `min(128000, window - 16000)` stops the run; three consecutive failures are not the stopping rule. These numbers are operating choices, not benchmark-derived optima or provider capacity guarantees.

## Permissions, files and broker actions

Approval modes are DEFAULT/AUTO; outcomes are ALLOW/ASK/DENY. Persistent rule sources are USER_SETTINGS/SESSION. MCP annotations are metadata, not additional rule-source enum values. Single-use approvals and explicit user interaction are handled by the approval lifecycle.

Internal drafts and published reports have separate paths. A permission decision does not guarantee tool success. Broker operations depend on the discovered MCP contract: instruction creation must not be described as automatic live-order submission.

## Scheduling and events

Temporary session-associated tasks and persistent schedules are distinct. Event reminders and post-release outcome checks depend on source availability and actual publication. They do not guarantee all data is available at an exact deadline. Missing data must not be represented as a successful empty result.
