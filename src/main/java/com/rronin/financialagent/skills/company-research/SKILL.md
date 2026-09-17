---
name: company-research
description: Use this skill when the user asks for a deep company research report, equity research write-up, institutional-style stock report, company analysis report, Markdown report, or Word/PDF-ready written report for a public company.
approval-write-paths: reports/*/company-research-*.md
---

# Company Research Report

## Purpose

Produce a deep, objective, data-backed Markdown company research report for professional equity analysis. The report should be suitable for later Word or PDF export and should focus on investment-relevant business quality, financials, competitive position, risks, market opportunity, valuation realism, and conclusion.

The output must be text-only Markdown. Do not create charts, graphs, or tables unless the user explicitly asks for them. If charts are supplied by the user, use them only as evidence for figures and narrative; do not recreate them.

## Research standard

Act as a highly skilled financial analyst and equity researcher specializing in the company’s industry.

Before writing, gather enough evidence to support the report. Prefer current, verifiable, credible sources:

- Company filings: latest 10-K, 10-Q, annual report, proxy statement, investor presentation, earnings release, earnings call transcript.
- Structured financial data from `get_financials` and `get_market_data`.
- Macro and sector context from `get_macro_data` and credible web/news sources.
- Company and market news from `web_search`, then source verification with `web_fetch` for high-value URLs.
- X sentiment only when relevant, using `x_search`; treat it as noisy sentiment evidence, not verified fact.
- Competitor information from filings, industry sources, financial data, and credible news.

Normally use 5–8 high-value sources, with more only when needed to support material claims. Source count is not a quota; one primary filing may support several sections. Every material claim must be supported by evidence. Use `web_fetch` to verify important source pages when search snippets are insufficient. If a source is unavailable, say so and avoid pretending certainty.

Evidence budget: after the main structured financial data, market data, search results, and a small number of high-value `web_fetch` verifications have been collected, stop gathering evidence and write the report. Do not keep calling search/fetch tools indefinitely. If a missing source or failed tool leaves a gap, disclose the limitation in the report instead of starting another broad research loop.

## Source reliability and financial-data verification rule

Use a source hierarchy and verify important financial claims before writing them into the report.

**Source hierarchy**

1. Highest authority: SEC filings, company annual/interim reports, company investor relations materials, earnings releases, proxy statements, regulator/government documents, exchange filings, and audited financial statements.
2. High authority: earnings call transcripts, reputable financial media that quote primary documents, established industry research, rating-agency reports, and recognized sell-side or independent analyst notes when available.
3. Supporting context only: market-data pages, stock screeners, valuation websites, blogs, newsletters, forums, social-media posts, X posts, unattributed screenshots, and generic search snippets.

**Financial-data verification**

- For revenue, EPS, margins, free cash flow, ROIC, ROE, debt, cash, share count, guidance, beat/miss claims, and valuation ratios, prefer `get_financials` plus company filings or earnings releases.
- Do not rely on a single third-party summary page for precise financial numbers. Cross-check important numbers against at least one primary source when possible.
- Do not write earnings, guidance, beat/miss, or management-commentary claims unless they are verified by company filings, company press releases, investor relations materials, earnings transcripts, or a fetched high-quality article that clearly cites those primary sources.
- If `get_financials` conflicts with company filings, explain the discrepancy and use the company filing or official release as the controlling source.
- If fiscal periods differ across sources, label the period clearly and do not mix quarterly, annual, fiscal-year, and calendar-year figures without explanation.

**Valuation and reverse DCF inputs**

- State the source and date for the latest stock price, shares outstanding or market cap, free cash flow, stock-based compensation, net debt/cash, and peer multiples.
- If a key valuation input is unavailable or uncertain, state the assumption explicitly instead of presenting it as fact.
- Treat fair-value estimates from GuruFocus, Simply Wall St, MarketBeat, screeners, or similar platforms as third-party model outputs, not authoritative valuation conclusions.

**News and social-media verification**

- Use `web_fetch` before relying on high-impact news, legal/regulatory claims, major customer wins, management changes, M&A, financing, dilution, or guidance changes.
- X/social-media data may be included only as sentiment or market-discussion context. Do not treat X claims as verified unless they link to a primary or high-quality source.
- If sources disagree or evidence is thin, say `source discrepancy; needs verification` and avoid overstating the conclusion.

## Tool selection

Select tools needed for the requested analysis; this list is not a mandatory checklist. Do not query macro data, insider transactions, institutional holdings or X sentiment unless they resolve a material question:

- `get_financials`: income statement, balance sheet, cash flow, metrics, ratios, earnings, historical KPIs, multi-company comparison.
- `get_market_data`: current and historical stock price, company news, insider transactions, institutional holdings, price context.
- `get_macro_data`: macro, sector, regulatory, Fed/FOMC, geopolitical, and industry context.
- `web_search`: discover filings, annual reports, investor relations pages, industry reports, regulatory/news context, and source URLs.
- `web_fetch`: open high-value URLs found through search and return a bounded text preview for source verification. Use it for filings, investor relations pages, news articles, regulatory pages, and industry references before relying on detailed claims.
- `x_search`: public X sentiment and high-signal market discussion when relevant.
- `write_file`: save the final Markdown report.

## Report persistence

When the user asks to generate a company research report, save the final Markdown report with `write_file` under:

```text
reports/<TICKER>/company-research-<TICKER>-<YYYY-MM-DD>.md
```

Use uppercase ticker symbols when known. If the ticker is unknown, use a safe company slug.

The file content should be the final report only. Do not include hidden reasoning, tool logs, or raw JSON in the report file.

After saving, tell the user the saved path only after the deliverable has passed general validation and financial audit.

### Updating an existing company report

When asked to update a company report, first locate and read that company's existing report. Preserve useful background but re-check dated financials, prices, valuation assumptions, catalysts and source links against current evidence. Add an as-of date and a short material-changes section; do not describe old evidence as newly verified.

By default save the updated report as a new dated revision under the same ticker directory, leaving the prior version intact. If today's filename already exists, use a timestamp suffix rather than silently overwriting it. If the user specifically asks to replace an existing file, use that exact path through the normal tool approval workflow. Existing-file writes may require approval; a Skill does not grant permission.

Use `# Company Name (TICKER)` as the first heading, keeping report type, dates and revision notes in the document body instead of the sidebar title. Both updated answer and updated file must pass the same general and financial review as a new report.

## Length and style

Default target: 3,000–4,500 Chinese characters or 2,000–3,000 English words, excluding bibliography. Retain all 13 main sections, with most space devoted to business model, financials, risks and valuation. Keep history and generic background brief. Explicit user length/depth requirements override this default, including requests for a 7,500-word report. Never claim a shorter report meets a longer requested length.

Tone:

- Formal, objective, analytical.
- Suitable for institutional investors and professional equity analysts.
- Concise but comprehensive.
- No marketing language.
- Bias-neutral unless evidence supports a clear view.

Citations:

- Use numbered citations in the body, e.g. `[1]`.
- End with a numbered bibliography.
- Prefer source names and URLs where available.
- Do not invent citations.

Formatting:

- Markdown only.
- Start with `# <Company Name> (<Ticker>)`.
- Start the body with `## Executive Summary`; do not number Executive Summary.
- Number the main section titles exactly as `## 1. Introduction to <Company Name>`.
- Do not number subtitles; make subtitles bold.
- Avoid tables and charts unless explicitly requested.

## Required report structure

Use this exact main structure:

```markdown
# <Company Name> (<Ticker>)

## Executive Summary

## 1. Introduction to <Company Name>

**Core business overview**

**High-level market positioning**

## 2. History

**Key milestones**

**IPO details**

**Evolution of product offerings**

## 3. Leadership

**CEO background and prior experience**

**Leadership style and strategic influence**

**Stock-based compensation**

## 4. Business Model

**Products and services**

**Target customers**

**Revenue streams**

**Unit economics and operating leverage**

## 5. Competitive Advantages

**Switching costs**

**Network effects**

**Proprietary technology and IP**

**Pricing power**

**R&D intensity**

## 6. Financials

**Revenue trend**

**Margins**

**Free cash flow**

**Net operating profit**

**ROIC and ROE**

## 7. Financial Health

**Liquidity ratios**

**Debt structure**

**Working capital efficiency**

**Credit ratings**

## 8. Competitor Analysis

**Key players**

**Comparative positioning**

**Competitor strengths and weaknesses**

## 9. Opportunities and Risks

**Opportunities from latest filings**

**Risks from latest filings**

**Macro and regulatory risks**

**Technological risks and opportunities**

## 10. Total Addressable Market

**TAM**

**SAM**

**Requirements to reach larger market opportunity**

## 11. Pricing Analysis

**P/E and forward P/E versus sector peers**

**Historical valuation trend**

**Reverse DCF**

## 12. Valuation Realism Check

**Implied growth realism**

**Comparison with historical growth**

**Market dynamics and competitive constraints**

## 13. Overall Conclusion

## Bibliography
```

## Section guidance

For Financials, explain historical trends and business reasons for movements. Cover revenue, margins, free cash flow, net operating profit, ROIC, and ROE.

For Financial Health, cover liquidity, debt, working capital efficiency, and credit ratings if available.

For Competitor Analysis, identify direct and adjacent competitors and discuss relative strengths and weaknesses.

For Opportunities and Risks, prioritize the latest filings and then add macro, regulatory, and technological context.

For Total Addressable Market, distinguish TAM from SAM. Explain what the company would need to do to reach those market levels.

For Pricing Analysis, compare P/E and Forward P/E against sector peers and the company’s historical trend. Perform a reverse DCF with:

- 10-year horizon.
- Latest stock price.
- Free cash flow excluding stock-based compensation when data is available.
- 3% perpetuity growth.
- 10% required return.

If free cash flow is negative, estimate the cash flow path required over the next 10 years to justify the current stock price. Clearly state the reverse DCF outcome.

For Valuation Realism Check, assess whether implied growth rates and the reverse DCF outcome are realistic compared with historical growth, market size, margins, competition, and industry dynamics.

## Final guardrails

- Do not recommend buying or selling solely from valuation multiples or social sentiment.
- Do not invent numbers, citations, filings, or management claims.
- If data conflicts, explain the conflict and use the most authoritative source.
- Keep the saved Markdown clean and export-ready.

## Execution plan and stopping rules

1. Resolve company/ticker and reporting currency once. Reuse relevant, still-current verified context. Establish the latest reporting period and as-of date before collecting numbers.
2. In one independent batch, obtain core financials, a dated price/market-cap snapshot, and discover the latest filing/earnings release. Do not fetch every endpoint or repeat the same data under different commands.
3. Read 2–4 primary documents/pages that cover business, financial statements, guidance and risks. Query 1–2 relevant peers only for comparable valuation/business metrics. Add a source only for a specific material gap.
4. Build a compact input ledger: metric, value, unit/currency, period, source URL and locator. Reuse it for every section and reverse DCF. Do not mix historical snapshots with current ones. Validate arithmetic with an available calculation tool; never invent a tool or result.
5. Aim for 6–9 tool rounds before drafting. This is a planning target, not a hard limit on necessary verification. Once core evidence is sufficient, stop broad searches. Unavailable optional metrics should be marked unavailable, not pursued through repeated low-quality searches.
6. Write the report once. Keep the final chat reply short with the report link and 2–3 key findings; do not duplicate the report body. Review feedback calls for targeted edits to disputed passages and recalculation only of affected inputs. Retrieve new evidence only if existing evidence cannot resolve the issue.

Preserve primary-source checks, fiscal-period consistency, dated valuation inputs, all required sections and the 10-year reverse DCF assumptions. For loss-making firms, label P/E as not meaningful instead of forcing a number. Unknown ROIC, credit ratings or TAM remain explicit gaps. Efficiency must come from avoiding repetition, not from weakening evidence standards.

Use the current user request’s language for the final response presenting the report, regardless of older conversation language. Do not reproduce the report in the chat; provide a concise summary and its link.
