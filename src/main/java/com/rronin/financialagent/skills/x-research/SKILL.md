---
name: x-research
description: Use this skill when the user asks for X research, market sentiment, investor chatter, expert voices, rumors, catalysts, or public discussion around a stock, company, sector, macro event, earnings report, or portfolio holding.
---

# X Research

## Priority accounts and time boundaries

Prioritize relevant posts by **Serenity, Babyfolio, Black Panther Capital, KaizenInvestor,
The Kobeissi Letter, Gavin Baker, RonnieV, and JP Insights**. These are display names,
not verified handles: resolve their identities using profile evidence before using `from:` filters.
Never invent handles or attribute posts to an account based only on a similar name.
Search broadly as well so the priority list does not hide meaningful contrary views.
If an account has no relevant posts in the requested window, say so or record the coverage gap.

When the user specifies the last seven days, use the current date/time to set explicit bounds.
Do not silently broaden that time window. Older posts may only appear as clearly labelled background,
not in the seven-day sentiment sample. Retain each post's URL, author, timestamp, and supporting excerpt.
Relevancy sorting is not a guarantee of ordering by likes; report engagement only when returned by the source.

## Purpose

Run a focused X research workflow for stock and financial analysis. Use this skill to understand what investors, analysts, traders, journalists, and company watchers are saying about a ticker, company, sector, market event, macro topic, or portfolio holding.

This skill is for research and sentiment extraction, not for trade execution. Treat X as noisy evidence: useful for surfacing narratives, catalysts, crowd positioning, and links, but never as a standalone source of truth.


## When to use

Use this skill for requests such as:

- "What are people saying about AAPL on X?"
- "Check X sentiment for NVDA after earnings."
- "What is finX saying about regional banks?"
- "Find bullish and bearish views on TSLA."
- "Any major X discussion about my portfolio today?"


## Research loop

Start with 2–3 complementary searches for the whole requested topic: core/source-backed discussion, contrary views, and a batched priority-account query where identities are verified. The query angles below are alternatives, not mandatory separate calls.

### Cost-aware stopping

For a normal seven-day sentiment brief, aim for 4–6 distinct X queries by default (up to 8 only for a material unresolved question), including
priority-account coverage. Batch compatible account/name searches when supported. Reuse discovered
profile identities and post URLs; do not repeatedly query spelling variants that returned the same posts.
Stop once the main positive and negative themes, representative dated posts, and material coverage
gaps are clear. A missing priority account is a disclosed gap, not a reason for an unbounded search.
Only add a query for a specific unresolved claim that could change the answer. Do not fetch a full
financial statement or build a valuation model when the user only asked about X opinions; verify only
the material factual claims that the brief actually uses. Target a concise brief, not an exhaustive report.

Prefer these query angles:

1. Core topic
   - Company name, ticker, product, event, or macro theme.
   - Examples: `$AAPL`, `Apple earnings`, `NVDA Blackwell`, `regional banks`.
2. Bullish narrative
   - Add terms such as `bullish`, `upside`, `beat`, `raise`, `catalyst`, `growth`, `margin`.
3. Bearish narrative
   - Add terms such as `bearish`, `risk`, `concern`, `miss`, `slowdown`, `overvalued`, `downgrade`.
4. Expert or high-signal voices
   - Use account filters when known, such as `from:<username>`.
   - Prefer analysts, journalists, industry specialists, company watchers, fund managers, and credible data accounts.
5. News and source-backed posts
   - Add `has:links`, article/source terms, or event-specific keywords.

If the search space is noisy, narrow with:

- `-is:reply` to reduce reply spam.
- `min_likes` or similar engagement filters if the X search tool supports them.
- Negative terms for spam-heavy topics, such as `-giveaway`, `-airdrop`, `-promo`, `-referral`.

If the search space is sparse, broaden with:

- Company name plus ticker.
- Sector or peer tickers.
- Event terms, such as `earnings`, `guidance`, `FOMC`, `CPI`, `FDA`, `antitrust`.
- A longer time window only if the user did not specify a fixed window; otherwise retain the requested bounds.


## Tool guidance

Use the best available X search capability first. If a native `x_search` tool is available, prefer it.

Recommended native search shape:

```json
{
  "command": "search",
  "query": "$TICKER OR CompanyName",
  "sort": "likes",
  "limit": 15,
  "since": "7d"
}
```

When appropriate:

- Use `sort: "likes"` or equivalent to surface high-signal posts.
- Use `limit: 10-15` per query.
- Use `since: "1d"` for breaking news or daily portfolio radar.
- Use `since: "7d"` for normal sentiment research.
- Use `since: "30d"` for slower-moving topics or pre/post earnings narrative shifts.

If no native X tool is available, or it fails due to authentication, quota or service errors, fall back to web search with X-specific queries, for example:

- `site:x.com $AAPL earnings bullish`
- `site:x.com NVDA guidance risk`
- `site:x.com "regional banks" "commercial real estate"`

Clearly label this as a fallback because public web search may miss posts, rank poorly, or omit logged-in content.


## Analysis rules

When reading results, extract signal rather than repeating posts one by one.

Track:

- Main bullish themes.
- Main bearish themes.
- News or catalyst links being shared.
- Notable expert voices and why they matter.
- Repeated claims that need verification from primary or financial data sources.
- Whether the discussion looks consensus, polarized, speculative, or low-signal.

Be cautious with:

- Viral posts without source links.
- Anonymous accounts claiming insider information.
- Engagement bait.
- Screenshots without links.
- Options-flow or short-interest claims without data verification.
- Crypto-style spam patterns leaking into stock discussions.

For investment analysis, cross-check important factual claims with finance, market data, macro data, company filings, or reputable news before using them in the final thesis.


## Output format

Return a concise research brief:

1. Query Summary
   - List the query angles used and the approximate time window.
2. Sentiment Snapshot
   - Overall tone: bullish, bearish, mixed, neutral, or low-signal.
   - Confidence: high, medium, or low.
3. Bullish Themes
   - 2 to 3 themes with representative evidence.
4. Bearish Themes
   - 2 to 3 themes with representative evidence.
5. Catalysts and News Links
   - Important events, articles, filings, product updates, earnings items, macro drivers, or policy headlines mentioned.
6. High-Signal Voices
   - Notable accounts or source types and what they contributed.
7. Caveats
   - Noise, missing data, unverified claims, bias, or sample limitations.
8. Investment Relevance
   - Explain how this X sentiment should or should not affect the stock analysis.

## Portfolio mode

When used for daily portfolio news radar:

1. Read current portfolio holdings first if an IBKR portfolio tool is available and enabled.
2. Exclude zero-quantity positions, retaining nonzero shorts. Batch core queries across holdings where supported; expand only holdings with material discussion or an unresolved contrary view. Do not multiply four query angles by every holding.
3. Prioritize holdings with recent price moves, earnings, guidance, regulatory news, product news, analyst actions, or unusually active discussion.
4. Do not store raw positions or account identifiers in long-term memory.
5. Summarize portfolio-level themes separately from company-specific items.

## Final guardrails

- Do not recommend trades based only on X sentiment.
- Do not treat X posts as verified facts unless corroborated.
- Do not expose private portfolio details beyond what is needed for the user-facing answer.
- When quoting posts, keep quotes short and attribute them to the source account/link when available.

The delivered answer must contain Markdown links to at least 3 representative original posts when available (including a contrary view), beside the supported claims. A list of usernames without clickable post URLs is insufficient. If fewer posts exist, link all available and state the coverage gap.

## Efficient evidence and delivery

- Obey the actual tool schema; the example above is illustrative, not permission to send unsupported parameters.
- Reuse verified account identities, already retrieved posts and dated evidence from this run. Resolve priority-account identities in a batch when possible; unresolved identities are coverage gaps, never guessed handles.
- Deduplicate by original post URL before reading or fetching links. Reposts and repeated quotations do not count as independent evidence.
- Fetch primary sources for material factual claims used in the conclusion, not for every opinion. Batch independent calls in the same tool turn. Reuse one verified source across themes.
- Maintain a compact evidence list: author, timestamp, original post URL, short excerpt, and verified/unverified status. Avoid rereading full tool-result files; read only the missing evidence slice.
- Target 4–6 tool rounds and approximately 600–1000 Chinese characters or 400–650 English words for an ordinary brief. These are planning targets, not reasons to omit user-requested coverage or invent balance.
- Preserve all output headings, requested accounts, fixed time bounds, and at least three original post links when available. A shorter sample must disclose gaps and lower confidence.
- Answer in chat unless a saved file is explicitly requested. After review, correct only disputed claims and reuse still-valid evidence; do not restart searches or rewrite unaffected sections.

### Chat delivery boundary
Invoking this skill does not request a saved report. Unless the user explicitly asks to save/export/download a file, return the research directly in chat with original post links. Do not write a Markdown report just to prepare the answer. If scratch storage is necessary use `.financial-agent/scratch/`; never link that scratch file in the answer.
