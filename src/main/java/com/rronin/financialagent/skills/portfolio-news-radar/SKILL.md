---
name: portfolio-news-radar
description: Research current IBKR portfolio holdings and produce a cited Markdown Daily Portfolio Brief focused on material company news.
approval-write-paths: reports/portfolio-news-radar/daily-portfolio-brief-*.md
---

# Portfolio News Radar

Use this skill when the user asks for a daily portfolio brief, portfolio news radar, or material news review for current IBKR holdings.

## Objective

Create a Markdown **Daily Portfolio Brief** for the currently connected IBKR portfolio. The brief should identify news from the last 3 calendar days that may affect each holding's fundamentals, valuation, or investment thesis, and it must include source links.

The skill should reuse the general research and file tools. Do not use a dedicated daily-brief save tool.

This is not a trading signal generator. The goal is to separate material, evidence-backed developments from noise and give the user a concise research brief they can review quickly.

## Research standard

Act as a disciplined equity research analyst monitoring an existing portfolio. For each holding, judge whether new information changes or could change the investment thesis.

Prioritize information that affects:

- Revenue growth, demand, pricing, backlog, customer concentration, or product adoption.
- Gross margin, operating leverage, free cash flow, capital intensity, or working capital.
- Balance sheet strength, liquidity, debt maturity, refinancing risk, dilution, or capital allocation.
- Management credibility, guidance changes, insider actions, governance, or compensation concerns.
- Competitive position, technology roadmap, product launches, customer wins/losses, or market share.
- Legal, regulatory, political, geopolitical, supply chain, or macro exposure.
- Valuation expectations, estimate revisions, analyst actions, earnings revisions, or material price moves.

Treat each source by quality:

1. Highest quality: company filings, press releases, investor relations, earnings materials, regulator/government sources.
2. High quality: reputable financial media, exchange notices, established industry publications.
3. Context only: X posts, blogs, forums, newsletters, unattributed screenshots, and social-media summaries.

Do not overstate weak evidence. If a claim is only found in low-quality sources, label it as unverified or omit it.

## Earnings verification rule

Earnings, EPS, revenue, guidance, beat/miss language, and earnings-date claims require extra verification.

- Do not state that a company "reported earnings" unless the event is verified by at least one official or primary source dated within the 3-day window: company investor relations, company press release, SEC 8-K/10-Q/10-K, exchange release, or an earnings transcript from a reputable transcript provider.
- Do not write specific EPS, revenue, guidance, beat/miss, margin, or free-cash-flow figures unless they are verified from an official/primary source or a fetched high-quality article that clearly cites the company release.
- Do not rely on search snippets, generic market-data pages, MarketBeat, GuruFocus, Simply Wall St, Yahoo quote pages, TradingView summaries, or X posts as the sole source for earnings facts.
- If sources disagree, omit the disputed metric and write `source discrepancy; needs verification` in Watchlist.
- If an earnings event is upcoming rather than already reported, put it in `Watchlist for Next Update`; do not describe results as if they already happened.
- If a holding has no official earnings release in the 3-day window, say no verified earnings release was found instead of using stale or forecast numbers.

## Freshness rule

Only use information published or materially updated within the last 3 calendar days from the run date.

- Treat this as a hard constraint for scheduled Daily Portfolio Brief runs.
- The run date is the `Current date` shown in the system message. Use that exact date for the report title and `write_file` path.
- Do not infer the run date from web search results, upcoming events, market dates, next scheduler run time, or model assumptions.
- Search queries should include freshness terms such as `today`, `last 3 days`, or a date range when useful.
- If a search result is older than 3 days, do not use it as current news.
- Do not fill empty sections with months-old earnings, filings, or analyst notes.
- Older facts may be mentioned only as brief background when needed to explain why a fresh event matters, and they must not be presented as today's news.
- If no relevant 3-day news is found for a holding, put it under `No Recent Material News / Low-Signal Holdings`.

## Required tools

Use these tools when available:

- `mcp__ibkr__get_account_positions`: read current connected IBKR holdings.
- `web_search`: discover recent news and source URLs.
- `web_fetch`: open high-signal URLs from search results and return bounded text previews for verification.
- `get_market_data`: price context, company news, and market background when helpful.
- `x_search`: optional public X discussion and market sentiment; treat it as noisy context, not verified fact.
- `write_file`: save the final Markdown brief under the general reports directory.

## Required workflow

This skill is optimized for unattended scheduler runs. Prefer a concise daily scan over exhaustive research.

1. Read the current portfolio with `mcp__ibkr__get_account_positions`.
   - Use ticker, company name, currency, and market price as context.
   - Do not reveal account identifiers, quantities, cost basis, or private portfolio details unless the user explicitly asks.
   - Ignore cash or non-company rows.
   - For scheduled runs, scan at most the 8 largest company holdings returned by the portfolio tool. Put the rest in low-signal/no-material-news unless a major known event appears.
2. Search recent news with `web_search` using `topic: news`.
   - Use at most 2 `web_search` call per holding.
   - Restrict the search to the last 3 calendar days.
   - Query format should be simple: `TICKER Company Name today last 3 days material news earnings guidance lawsuit acquisition regulation`.
   - Do not repeat near-identical searches for the same ticker.
   - Use `get_market_data` only if web search is insufficient or price context is essential.
3. Open sources with `web_fetch` only when necessary.
   - Fetch at most 3 total pages in a scheduled daily brief.
   - Use `maxChars` around 3,000-5,000.
   - Prefer primary/company release, filing, regulator, or high-quality financial media.
   - Always use `web_fetch` before writing earnings numbers, beat/miss claims, guidance, or other precise financial metrics.
   - If you cannot verify earnings metrics with `web_fetch`, omit the metrics and classify the item as `Monitor` or move it to Watchlist.
4. Use `x_search` only for unusual moves or controversial news.
   - At most 2 total `x_search` calls.
   - Treat X as sentiment/context only unless it links to a verifiable primary source.
   - Prefer recent X posts from the last 3 days.
   - It is acceptable to include a short `X / Market Discussion` bullet when X adds useful context, but label it clearly as market discussion or unverified social commentary.
5. Stop searching once you have enough evidence for a useful brief.
   - Do not try to fully research every company.
   - Include only items likely to affect revenue growth, margins, cash flow, balance sheet, capital allocation, management quality, competitive position, regulatory/legal exposure, valuation, or long-term thesis.
   - If nothing material is found for a holding, state that briefly.
6. Analyze the information before writing.
   - Classify each item as `Bullish`, `Bearish`, `Mixed`, or `Monitor`.
   - Explain the mechanism: why the event matters economically.
   - Separate confirmed facts from interpretation.
   - For earnings-related items, explicitly check whether the event is verified, upcoming, or unverified.
   - Avoid generic phrases like "could impact the stock"; explain the specific channel.
7. Always save the brief before producing the final response.
   - Use `write_file` exactly once unless the write fails.
   - The path must be `reports/portfolio-news-radar/daily-portfolio-brief-YYYY-MM-DD.md`, where `YYYY-MM-DD` is the exact `Current date` from the system message.

## Analysis guidance

For each holding with material news, cover four things:

- **What happened:** the concrete event, announcement, filing, market move, or source-backed update.
- **Why it matters:** the likely business, financial, regulatory, or valuation implication.
- **Thesis impact:** whether the item supports, weakens, complicates, or does not change the existing investment thesis.
- **What to watch next:** the next confirming evidence, such as earnings call commentary, updated guidance, filings, contract details, regulatory decisions, or price/volume follow-through.

For holdings without material news:

- Do not force analysis.
- Use one short sentence saying no recent material news was found in the last 3 calendar days.
- If a known upcoming event exists, mention it in Watchlist rather than inventing a news item.

For portfolio-level synthesis:

- Identify cross-holding themes, such as AI capex, rates, semiconductors, small-cap liquidity, Europe exposure, China exposure, energy transition, IPO lockups, or regulatory pressure.
- Mention whether news is concentrated in one holding or broad across the portfolio.
- Explain if the day appears high-signal or mostly routine.

## Output format

Write the brief in Markdown with this exact structure:

```markdown
# Daily Portfolio Brief - YYYY-MM-DD

## Executive Summary
- 3-6 bullets covering the most important portfolio-level developments.
- State whether today's news flow is high-signal, mixed, or mostly routine.
- Mention the highest-priority holding to review first, if any.

## Material News by Holding

### TICKER - Company Name
- **Signal:** Bullish / Bearish / Mixed / Monitor
- **What happened:** concise source-backed event description.
- **Why it matters:** explain the likely business, financial, regulatory, or valuation impact.
- **Thesis impact:** explain whether this changes, supports, weakens, or does not materially change the investment thesis.
- **What to watch next:** next evidence to monitor.
- **Sources:** [Source name](URL), [Source name](URL)
- **X / Market discussion:** optional; include only if recent X discussion adds useful context, and label unverified claims clearly.

## No Recent Material News / Low-Signal Holdings
- TICKER - Company Name: no material news found in the last 3 calendar days, or only low-signal items.

## Watchlist for Next Update
- Specific items to monitor next: filings, earnings dates, management comments, product/regulatory milestones, estimate revisions, or price moves that need confirmation.

## Source Notes
- Briefly note source limitations, if any.
- Every material claim must have a URL.
- Mention that the brief used a 3-day news window.
```

## Report persistence

Save the finished Markdown using `write_file` under:

```text
reports/portfolio-news-radar/daily-portfolio-brief-YYYY-MM-DD.md
```

Replace `YYYY-MM-DD` with the exact `Current date` from the system message.

The file content should be the final brief only. Do not include hidden reasoning, tool logs, raw JSON, account identifiers, quantities, or cost basis.

After saving, tell the user the saved path.

## Quality bar

- Be concise but useful: prioritize signal over volume.
- Enforce the 3-day freshness rule; do not use stale news to make the brief look fuller.
- Keep conclusions neutral and evidence-backed.
- Clearly distinguish confirmed facts from interpretation.
- Do not fabricate or infer earnings results. If no official source is available, say so.
- Clearly label X-sourced information as `X / Market discussion` or `Unverified social commentary`; do not treat it as confirmed unless it links to a verifiable source.
- Use `web_fetch` to verify important claims rather than relying only on search snippets.
- Do not include raw portfolio quantities, account identifiers, cost basis, P/L, or private account details.
- Do not recommend buy/sell actions. Use research language such as `review`, `monitor`, `follow up`, or `thesis impact`.
- If source coverage is thin, say so directly.
- If IBKR is not connected or no positions are available, explain the missing prerequisite and save a Markdown record only if the user explicitly asks.

## Final response after saving

After `write_file` succeeds, keep the chat response short:

- Say the Daily Portfolio Brief was generated.
- Provide the saved path.
- Mention the top 1-2 issues to review, if any.

## Cost and completion budget

Aim for 6–8 tool rounds and finish within the runtime's 12-loop ceiling, including corrections. Read holdings once. Batch independent searches for all holdings, then fetch only the strongest 3–5 primary sources containing material fresh developments. Do not repeatedly search an unchanged ticker to fill space. If no fresh material event is found, briefly record that result for that holding. Optional X research and market prices are unnecessary unless they resolve a material question.

Keep the brief around 600–1000 Chinese characters (or 400–650 English words), excluding source links; use concise rows for low-signal holdings. Write the report once. Final chat response should only summarize the outcome and link the report, never reproduce its full text. On review feedback, edit only the disputed passage rather than regenerate the whole report. A failed core holdings lookup must stop report creation.

When ranking positions, normalize market values to one currency using a dated FX observation. Never rank USD and SEK raw numbers together. If no defensible FX conversion is available, present separate currency subtotals and omit an unsupported weight/rank. Report filenames use the scheduler local calendar date when supplied.


## Active holdings and bounded retrieval

IBKR may return closed positions with quantity/position equal to zero. Exclude these before collecting news or counting holdings; do not exclude negative (short) positions. Use the latest successful snapshot, never an older holding list from memory. If quantity is missing, verify rather than assume active.
Batch searches, read current holdings once, and avoid price/performance/allocation calls: this brief is a news summary. Do not fetch account history or calculate returns. Stop searching a holding after a relevant primary source or an explicit coverage limitation. A denied tool is not a reason to repeat the approval request.

Write the Daily Portfolio Brief and its completion summary in English. Keep the notification concise, with the main findings and as-of date.
