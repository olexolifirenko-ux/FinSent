# BACKLOG

Open work and design ideas, with enough context to pick any item up. **The canonical planning doc.**
Status tags: **[idea]** discussed/assessed, not started · **[ready]** scoped, ready to build ·
**[partly done]** some shipped, open remainder here · **[blocked]** waiting on something · **[note]**.
**Completed, reverted, and rejected items live in `BACKLOG_ARCHIVE.md`** (a dated changelog) — keep this
file to *open* work; move an item there when it lands. Item numbers are stable across both files.

---

## Strategic framing

**Current identity: a fast BTC news-event MONITOR** (reframed 2026-06). It catches real, materially
crypto-impactful events fast, filters out bluff/speculation/already-priced noise, and notifies — it is
NOT a price predictor. The deep pass is an event **detector** (a fact-vs-posture / new-vs-priced /
channel test) that emits a materiality tier + a secondary directional **lean**. A long/short **trading
module is deferred** — the future direction the positioning signals (funding + OI) are being built toward.

**The measured reality (from the feedback loop, #6):** short-horizon directional *prediction* is
~coin-flip on small samples; the system's real edge is **detection** (seeing the event before the
market fully reacts). So weight detection (faster/broader sourcing) over chasing oracle accuracy, and
treat every prediction-quality change as unprovable until the feedback loop has weeks of *live* data.

**Deprioritize:** more macro-indicator sophistication (VIX/DXY/SP500/Gold). The standalone **macro-only
alert path was removed** (2026-06-22, see archive) — it fired on macro moves BTC had already made
(lagging confirmation, no detection edge). Macro collection stays **off by default** and only as optional
`macro_regime`/`macro_trend` *context* for the deep pass; it doesn't drive the call. The **perp-positioning
signals (funding + OI)** are the higher-value context for both the monitor's materiality read and the
future trading module.

---

## Analyser effectiveness (the directional call)

### 1. Rolling-synthesis analysis — the second analysis lane *(the big one)* **[idea]**
**Problem.** The per-window analysis only ever sees one 10-minute slice of news, yet a real BTC move
over 1h/24h is usually the *cumulative* result of several events across the day (e.g. a morning
geopolitical headline + an afternoon escalation). Each window is analysed in isolation, so
cross-window narratives are never correlated, and the per-window lane is structurally mismatched to a
1h/24h horizon.
**Idea.** Keep the fast per-window lane for **detection/triage** (unchanged). Add a *separate*,
periodic (e.g. hourly) **synthesis** lane that answers a different question — *"what is the
cumulative story and net directional bias of today's news, and does it fit the broader move?"*
**Why it's cheap.** It does **not** re-screen the day's articles. The system already stores
per-window records with `resonant_article_ids` (the already-screened, already-resonant subset). The
synthesis reads the resonant articles from the last N window records, feeds that curated set + the
1h/24h price frame to a single deep "synthesis" prompt, and produces a net read. `macro_trend`
already does this rolling-lookback trick for macro; this extends it to news.
**Scope to design.** Cadence + trigger; the synthesis prompt; how it reads stored `ANALYSIS` records
across windows/days; its own output record + (optional) its own notification policy; how it relates
to the per-window alerts (digest vs alert). Honest caveat: improves input/output *horizon match*, not
oracle accuracy.

### 6. Feedback / scoring loop — *the measurement gate* **[partly done — v1 shipped]**
v1 is shipped (`anal feedback`; see the archive). It is the **gate** on every signal-quality change —
don't tune what you can't measure. **Open:** the full 11-metric report + scenario baselines; honest
validation needs **weeks of *live* matured predictions**, which only accrue while the instance runs.
**Standing rule:** every *new* signal must be written into the prediction record so the loop can later
measure its contribution.

### 31. Fetch article body for resonant articles (IP-4a) **[idea]**
Claude gets only the title + ≤500-char description — too thin to assess the transmission mechanism
("SEC Takes Action on Crypto Exchange" = a $50K fine or a full shutdown?). After the screener picks the
resonant set (typically 3–15), fetch body text for *those few*: **`content:encoded` from the RSS XML
first** (free, already fetched, zero latency), then an optional readability fetch (5s timeout, graceful
fallback to title+description). Pass the first ~1000 chars to the deep pass. **Effort:** moderate (HTTP
latency 2–5s on the resonant articles only; a config flag to enable).

### 32. Pass all window articles to the deep pass as context (IP-4c) **[idea]**
Deep analysis sees only the resonant articles, so it can't tell "one big event in an otherwise quiet
session" from "one event among many competing stories" — which affects impact. When ≥1 resonant exists,
also send the non-resonant ones as compact one-liners (title + screener score) under a `WINDOW CONTEXT
(N other articles, scored noise)` heading. **Effort:** small (~20–30 lines in `PromptBuilder`). Most
useful with #31.

### 33. Give the deep pass the full description (IP-4b) **[ready — verify first]**
IP-4b (Python) noted the *decisive* deep pass got a shorter description slice (`[:200]`) than the cheap
screener (`[:300]`) — an oversight. **Verify the Java `PromptBuilder` truncation**; if the deep block
truncates shorter than the screener, widen it to the full (or `[:500]`) description for the resonant-only
set — negligible token cost, the expensive pass should never see *less* context than the screen.

### 34. Extended trading-signal output: horizon / cascade / invalidation (IP-4d remainder) **[idea]**
The deep schema emits direction/impact_tier/reasoning/key_events. For a margin call, add: `time_horizon`
(e.g. "30min–2h"), `cascade_risk` (bool — true when liquidation clusters are within ~3% in the predicted
direction *and* funding/OI is crowded the wrong way — the funding+OI `setup` already half-computes this),
and `invalidation` (what would reverse the call). **Most valuable once liquidation clusters (#27) exist**
to inform cascade_risk; pairs with #6 ("do `cascade_risk=true` calls see bigger moves?"). Touches the
deep schema + `DeepResult` + record + notify/format.

### 36. Cross-window screener dedup — open follow-ons **[partly done — live path shipped]**
The "first + causative only" cross-window dedup shipped (see the archive: `ALREADY COVERED` block from
stored records; recaps/follow-ups scored 0 with a materially-new exception). **Open:** (a) tune the 6h
`screenerDedupLookback` via the feedback loop; (b) watch the make-or-break "materially-new vs rehash"
wording for over-suppression (missing a real new development) vs under (residual dups).

---

## Signal quality (positioning inputs)

> Replace the macro-analyst data mix with **derivatives-trader** signals that serve the long/short
> margin decision. **Done: funding + OI fusion** (see archive). Remaining order: liquidations (#27) →
> whale flow (#28), then unify the prompt block (#29). Each follows the established fetcher → snapshot
> registry → signal-class → deep-prompt pattern.

### 27. Liquidation clusters (IP-2c) **[idea — needs CoinGlass key]**
Funding/OI say leverage is loaded; liquidation clusters say **WHERE it cascades** — the nearest large
long cluster *below* and short cluster *above* price, with distance %. The most directly *tradeable*
signal: it gives the realistic **target** of a move, not just direction ("bearish + long cluster 2.8%
below ~$800M → cascade target"). **Source:** CoinGlass free tier (liquidation heatmap), needs a free API
key + rate-limit handling. Prompt line: `liquidations: long cluster $105,200 (2.8% below, ~$800M) | short
cluster $112,400 (3.9% above, ~$450M)`. **Effort:** moderate. Builds on funding+OI; enables #34's
`cascade_risk`.

### 28. Exchange net flow / whale alerts (IP-2d) **[idea — needs API key]**
Large BTC → exchange is a leading **sell** signal (coins moved to sell), observable before the selling and
before any article. **Source:** Whale Alert API (free tier ~10 req/min), poll 30–60s, filter BTC > ~$10M
to/from known exchange wallets, compute rolling 1–2h net flow. Prompt line: `whale_flow: $180M net
exchange inflow (last 2h) — sell pressure building`. **Effort:** moderate (API key, polling integration).

### 29. Unified `MARKET POSITIONING` prompt block (IP-2e) **[idea — after the signals exist]**
Once liquidations/whale-flow land, replace the separate signal lines with one coherent block
(`funding / perp_oi / options / liquidations / whale_flow / macro_regime`) + guidance on combining them.
**Effort:** small, incremental. (Partly anticipated: the funding+OI line already fuses crowding + OI
trend + price into one `setup`, and the alert groups positioning + 24h-range.)

---

## Urgent lane / latency

### 8. Tighter urgent cadence **[idea]**
`urgentPollInSec=30`. Urgent sources are free RSS, so 30→15s roughly halves detection latency. Trade-off:
more requests (politeness/rate-limit) and more frequent per-article OHLC fetches. Cheaper now that
conditional GET makes most polls a bodyless 304.

### 9. `Http.CLIENT` connect timeout **[idea]**
The request-level `.timeout()` does **not** bound the TCP connect phase (cold-connect failures hit ~11s,
governed by the client `connectTimeout(15s)` + the cold selector). Consider lowering
`Http.CLIENT.connectTimeout` to bound connects globally — but it affects every call (Claude, Telegram,
market data), so treat it as deliberate. (The cold-start storm is already fixed by the `RssSource`
warm-up; this is belt-and-suspenders.)

### 10. AlJazeera fallback (contingency) **[blocked: only if it re-blocks]**
AlJazeera is currently reachable (realistic browser headers + `preferIPv4Stack` + warm-up). If its WAF
blocks again at a layer headers can't beat (TLS fingerprint / IP), the fallback is a **direct alternate
Mideast wire** — *not* a Google-News RSS proxy (aggregator lag defeats the urgent lane).

### 30. Post-publication price delta into the prompt (IP-3c) — open **[partly done]**
The alert side shipped (it shows the realtime price + % move since the catalyst). **Open:** also pass a
per-article `since_publish` delta to **Claude** in the deep block, so the model itself can reason "event
partially absorbed, lean neutral" and avoid a too-late call. The realtime-price fetch already exists
(`collector.fetchClosePriceAt`); thread it per-article into `PromptBuilder.deepArticles`. **Effort:** small.

---

## Observability

### 12. Raw macro numbers + per-source breakdown in the context log **[idea]**
The collector `Context …` line shows presence + BTC price. Optionally add raw VIX/DXY/SP500 moves (echo,
not interpretation) and a per-source article breakdown (`27 new: rss 25, …`) to spot a silently-dead
source. (Related: the cycle-summary log now reports per-window article counts.)

### OPERATIONAL — let it bake + measure **[ongoing]**
The feedback loop needs matured *live* data. Keep the instance running; run `anal feedback --days N`
periodically. This is the **gate** on further signal-quality tuning — don't tune what you can't yet
measure.

---

## Detection — faster & broader coverage (Phase 1)

Pure speed + coverage; the highest-leverage area (you can't trade on what you haven't seen). Several
items framed elsewhere as "free/config-only" understate real costs, called out per item.

### 17. Poll all RSS feeds at high frequency (IP-1a) **[idea — now unblocked]**
Move the boundary-only feeds into the 30s urgent model — **biggest single latency win** (≤10min → ~30s),
unblocked by conditional GET. Two costs: (a) "doesn't add analysis cycles" is conditional — high-volume
feeds trip broad keywords far more than the niche ones, risking analysis-cost/alert-fatigue, so **audit
urgent-keyword hit-rate on those feeds first**; (b) rate-limit/WAF risk at 30s × ~14 feeds (mitigated by
conditional GET). Keep the regular lane's window-context assembly + bucketing intact. **Verdict: high
value; do after the keyword audit.**

### 18. Government / regulatory feeds — remaining **[partly done]**
Shipped: SEC_Press (regular), Fed (urgent), White House (urgent), ECB/BoJ/BoE (regular) — see archive.
**Could NOT add (verified):** OFAC RSS retired 31 Jan 2025; Treasury press has no RSS; SEC litigation
`/rss/*.xml` 403s the app UA. **Remaining:** SEC-litigation + Treasury via a non-RSS source or per-feed UA.

### 19. Add crypto-native feeds (IP-1c) — *selective* **[idea]**
Binance/Coinbase **listing-delisting-halt** announcements move price before media — high value. Caveat:
least RSS-friendly (Binance's endpoint is unofficial; Coinbase's blog is PR-heavy), so the work is a
**reliable feed**, not config. Ethereum Foundation = low short-term BTC relevance, **skip**.

### 20. Add Asian news sources (IP-1d) — *moderate* **[idea]**
Covers the Asian-session blind spot (00–07 UTC, thin liquidity amplifies moves). Asterisk: the most
market-moving Asian news breaks in *local-language* sources first; English editions (NHK/SCMP/Yonhap)
**lag**. Prioritize **SCMP (China-regulatory) + Yonhap (Korea)** over NHK.

### 21a. Auto-build the econ schedule via a scheduled Claude routine **[idea]**
The econ module (#21) is shipped (see archive); the remaining manual step is **authoring the schedule's
consensus** (~2 min/month). The Java `EconScheduler` *can't* automate this — it has no programmatic source
for **consensus** (no free API: TradingEconomics guest 410, Finnhub/FMP keyed+premium, Forex Factory
blocks scrapers) and BLS exposes no clean future-release-date endpoint. **The right tool is a scheduled
Claude routine** (cloud cron via the `/schedule` skill), not extending the Java scheduler: monthly, it
reads `cfg/econ_definitions.json`, finds upcoming releases lacking a schedule file, web-researches the BLS
release date + a free calendar's consensus, writes `data/<date>/econ_schedule_<date>.json` and commits
(now that `release/data/` is git-tracked). Clean producer/consumer split — **routine authors,
`EconScheduler` consumes**. Decisions: review the routine's first output before trusting; sync model (cloud
commit → `git pull` vs a local recurring run writing straight into `release/data`).

### 22. Cross-cutting caveats for the phase **[note]**
- **Detection without a faster alert path is half a win** — 17–21 make you *see* sooner; action still
  waits on the window cadence + notify gate.
- **Adding recall with no way to measure it** — more sources = more catch *and* more noise; #6 is how you
  prove added sources pay off. Not a blocker; go in knowing it's intuition-driven until measured.
- **Revisit `MAX_CONCURRENT_FETCHES`** (currently 4): if the high-freq pool grows to ~18 feeds, bump to
  ~6 (safe now that conditional GET makes most fetches a cheap 304).

### 35. Primary sources — go to the source, not the journalist **[idea — see consult notes]**
The strategic case: for macro/geopolitics the edge isn't "faster Bloomberg" — it's **central banks /
governments / data APIs that ARE the event**, before any journalist rewrites them. Remaining beyond what's
shipped (Fed/WH/ECB/BoJ/BoE/SEC_Press + the BLS econ module): **AP/AFP wires** (RSS still live, unlike
Reuters which retired public RSS in 2020); **BEA data API** (GDP/PCE, like the BLS module). **Honest
caveats:** the truly-first layer for geopolitics (gov/military X, OSINT Telegram, shipping intel) is **not
RSS** and carries a noise/verification cost; the X amplifier source partly covers the gov/military-X angle.
**Tiered verdict:** AP/AFP wires = incremental (verify feeds); BEA = high value but a real module (the
consensus feed is the catch); OSINT Telegram = skip (latency edge real, but cost/noise/verification/
curation mismatch isn't justified). Overlaps #18, #21a.

---

## Housekeeping / deferred decisions

### 23. Parallelize the backfill **[idea]**
`anal windows -run` analyses windows **sequentially** on the single `FS-Backfill` thread, each blocking on
its Claude calls (~1.5–2s Haiku/window + ~8–9s Sonnet/resonant window) → ~10 min for ~150 windows. Run K
windows concurrently (bounded pool, K~4–6) → ~K× faster. **Needs:** thread-safe `AnalysisStore`/
`AnalysisRegistry` writes (today serialized by the single live worker), a modest K (Anthropic rate limits;
live lane shares the JVM), pre-recovering days once. Keep the live analyser single-threaded (decided).

### 26. `price_context` incremental fetch **[idea]**
`storePriceContext` re-fetches all **24h of hourly bars** every window to recompute the derived numbers,
though only the latest hour differs between windows. One cheap call so it's been fine; if trimming fetches
matters, cache/incrementalize the hourly series the way the 1m OHLC series now is. Storage already lean.

### 15. Crypt package **[idea]**
A DES + PGP utility package was deliberately **not** imported. Add only if a concrete need appears.

### ETF-flow regime — future trading/synthesis layer **[idea — not for the fast monitor]**
Assessed (see archive) as **not worth a real-time fetcher for the monitor**: daily/lagged cadence +
multi-day horizon mismatch the 10-min lane, and there's no clean free API. The news pipeline already
catches flow *headlines* (optionally add a flows account like @FarsideUK to make that reliable). Build a
daily flow *series* (CoinGlass/Farside) only when the trading module or the rolling-synthesis lane (#1)
exists — as a slow **regime** line, like `macro_regime`.

---

## Recommended order

1. **Keyword-hit-rate audit → faster polling (#17)** — biggest detection win; audit broad-feed keyword
   hit-rate, then move boundary feeds to the 30s urgent model.
2. **Remaining primary feeds (#18, #35)** — SEC-litigation / Treasury (non-RSS/per-feed UA); AP/AFP wires.
3. **Liquidation clusters (#27)** — WHERE leverage cascades (builds on funding+OI; needs CoinGlass key);
   enables `cascade_risk` (#34).
4. **Prompt-input wins (#33 full description, #32 all-articles context, #31 article body, #30 since_publish)**
   — cheap-to-moderate inputs to the decisive pass.
5. **Whale flow (#28), unified positioning block (#29), rolling-synthesis (#1)** — later, larger builds.
6. **BEA (GDP/PCE) + auto-built econ schedule (#21a)**, then the **ETF-flow regime** — for the eventual
   trading/daily-synthesis layer, not the fast monitor.

Running continuously alongside all of it: **let it bake + `anal feedback`** (the measurement gate).
