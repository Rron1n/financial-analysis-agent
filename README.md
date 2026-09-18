# Financial Analysis Agent

A Java financial research agent for portfolio analysis, company research, social sentiment research, and recurring investment briefs. It combines a tool-driven agent loop with persistent memory, explicit tool approvals, and independent candidate-answer review.

## Features

- **Interactive research:** portfolio return/risk analysis, company fundamentals, and research grounded in retrieved financial and public-source data.
- **Agent execution:** streaming responses, tool dispatch, mid-run user amendments, cancellation, and persistent session history.
- **Reusable skills:** company research, X research, and portfolio news briefs with task-specific research instructions.
- **Memory:** background session-memory extraction, topic memory, keyword/vector retrieval through Chroma, and dynamic context compaction.
- **Tool permissions:** `DEFAULT` and `AUTO` approval modes, with `ALLOW`, `ASK`, and `DENY` decisions and user/session rules.
- **Answer review:** deterministic checks, parallel general validation and claim extraction, followed by financial audit and bounded revisions.
- **Scheduled research:** temporary and persistent schedules, portfolio briefs, financial-event reminders, and post-release outcome tracking.
- **Web interface:** SSE progress updates, session history, attachments, report previews, and notifications.

## Technology

Java 21 · Spring Boot 3.5 · Spring AI dependencies · MCP · Chroma · SSE

The application owns its agent loop, message protocol, provider gateway, approval logic, and review lifecycle; it does not depend on a fixed multi-agent workflow framework.

## Quick start

### Prerequisites

- JDK 21 and Maven.
- Credentials for the model and data providers you intend to use.
- A compatible Chroma service for vector-backed retrieval; its default URL is `http://localhost:8000`.
- Your own IBKR authorization if broker tools are enabled.

```bash
git clone https://github.com/Rron1n/financial-analysis-agent.git
cd financial-analysis-agent
mkdir -p .secrets
cp config/application-secrets.example.yml .secrets/application-secrets.yml
```

Fill in your own credentials in the copied file, or use environment variables. The template contains no credentials.

```bash
mvn spring-boot:run
```

Open **http://localhost:8081**. To start without broker integration or the default portfolio schedule:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--agent.ibkr.enabled=false --agent.scheduler.portfolio-news-enabled=false"
```

The application can start without external credentials, but dependent model/data operations will not work until configured. Model availability, API access, and costs depend on your provider account.

### Configuration

Configuration lives in `src/main/resources/application.yml`. Secrets can be supplied in:

- `.secrets/application-secrets.yml` (ignored by Git), or
- `~/.config/financial-analysis-agent/application-secrets.yml`, or
- environment variables listed in `.env.example`.

Spring does **not** automatically load `.env`. See [Configuration](docs/configuration.md).

## Example requests

- “Give me an overview of a company's core business and main risks.”
- “Analyze my current portfolio's returns and concentration risk.”
- “Use the X Research skill to summarize discussion of a stock over the past seven days.”
- “Create a company research report using the uploaded financial release.”
- “Generate today's Daily Portfolio Brief.”

Availability and freshness depend on connected tools. A chat-only request does not inherently require a saved report; internal drafts and published deliverables are handled separately.

## Development and testing

```bash
mvn test
node --check src/main/resources/static/session-ui.js
node --check src/main/resources/static/ui-polish.js
node --test src/test/answer-renderer.test.cjs
```

Tests cover execution, approvals, memory, review, scheduling, events, and rendering. Live integration tests are opt-in and may incur provider charges. Passing offline tests does not establish live data accuracy or provider availability.

## Source layout

```text
src/main/java/com/rronin/financialagent/
  agent/       Execution loop, budgets and approvals
  audit/       Candidate validation and financial review
  memory/      Extraction, retrieval and compaction
  model/       Model gateway and message protocol
  tools/       Tools and integrations
  skills/      Reusable research instructions
  session/     Session persistence and recovery
  schedulers/  Scheduled tasks and notifications
  events/      Financial calendar and outcome tracking
src/main/resources/static/  Browser interface
src/test/                   Automated tests
```

## Operational boundaries

This is a local-first application. Do not expose it directly to the public internet without adding authentication and reviewing deployment controls. LLM review reduces some error modes but cannot guarantee factual correctness. Inspect evidence and verify consequential financial decisions independently. Broker tools are discovered from the connected MCP server; their behavior and authorization requirements depend on that server.

Only source code, tests, and empty configuration templates are included. Credentials, account snapshots, conversations, uploaded documents, generated reports, and local runtime state are excluded. See [Security](SECURITY.md).
