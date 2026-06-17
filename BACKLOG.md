# BACKLOG

Outstanding work and design ideas, with enough context to pick any item up. **This is the canonical
planning doc** — it absorbs the former `IMPROVEMENT_PLAN.md` (now deleted). Status tags:
**[idea]** discussed/assessed, not started · **[ready]** scoped, ready to build · **[partly done]**
some shipped · **[done]** · **[rejected]** · **[blocked]** waiting on something (usually human go-ahead).
Keep this file updated as items land or change.

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

**Deprioritize:** more macro-indicator sophistication (VIX/DXY/SP500/Gold → `macro_regime`/`macro_trend`/
macro-only alerts). Macro collection is **off by default** now; keep it as optional environment framing,
but it doesn't drive the call. The **perp-positioning signals (funding + OI)** are the higher-value
context for both the monitor's materiality read and the future trading module.

---

## Recently shipped (2026-06-16/17, this session)

- **Monitor reframe** — `deep_analysis.txt` rewritten to a 3-step **fact-vs-posture / new-vs-priced /
  channel** test (event detection, not forecasting); output is materiality tier + directional *lean*;
  notifications reframed to "CRYPTO EVENT" (materiality + lean); `NotifyGate` fires on materiality
  (no non-neutral requirement).
- **Screener rewritten** to a **direction-free 0–3 relevance ordinal** (was a signed −10..+10 score),
  threshold 2, with a recall lean, a light-bluff rule (major-if-true claims kept for the deep pass),
  and few-shot calibration. The signed scale was false precision (the decision was always binary).
- **`confidence` removed** end-to-end (see #5).
- **Structured outputs** (`output_config.format`, schemas in `ClaudeSchemas`) on both passes — the API
  guarantees schema-valid JSON, removing the malformed-response → noise path. Shapes verified live.
- **Adaptive thinking on the deep pass** (Sonnet reasons before answering) + `temperature:0` on both
  passes + parse the first `text` block (skip the thinking block).
- **Funding + Open Interest** fused positioning signal (#25 done; #24 partly).
- **Fast X (Twitter) source** via GetXAPI (`XSquawkSource`): one merged `from:a OR from:b` query on the
  urgent lane, core + situational accounts (`<XAccounts>`/`<XSituationalAccounts>`), a 25-clause cap
  guard, runtime `collect x on|off` toggle + `-DfetchX`. Finding: GetXAPI returns originals +
  quote-tweets but **never native retweets** (any endpoint) — follow principals directly to cover that.
- **Hollow-signal fix** — `macro_regime` is omitted (not a fabricated "neutral") when no macro snapshot
  was read.
- **`anal start/pause` → `anal on/off`** (start/pause kept as aliases); **`-DpauseAnalyser` →
  `-DrunAnalyser`** (positive sense); both startup flags default OFF when absent.
- **Data layout** reorganised into per-day `data/<date>/` folders and **git-tracked**.

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

### 2. Order deep-analysis articles by recency **[done 2026-06-08]**
For "nearest future," the freshest headline should dominate. `PromptBuilder.deepArticles` now sorts
the resonant articles most-recent-first (by `publishedAt`) before emitting the block, so Claude
weights the current catalyst over a 40-minute-old one. Per-article results still key by id, so the
reorder is safe (`deepArticlesOrderedMostRecentFirst` covers it).

### 5. `confidence` field on the deep output **[added 2026-06-09, REMOVED 2026-06-17]**
Added as `confidence: high|medium|low`, then **removed end-to-end**. Why it didn't earn its place:
LLM-verbalised self-confidence is poorly calibrated; it **co-moved with `impact_tier`** (little
independent signal); it duplicated Step 1 (fact-vs-posture) of the deep test; it was a **no-op in the
gate** (`minConfidence` defaulted to `low`) and was already hidden from alerts as "uncalibrated". Gone
from the deep/econ/macro prompts, `NotifyGate`, the records/logs, `Config` (`notifyMinConfidence`), the
feedback scorer/report, and `Confidence.java` (deleted). Lesson for any future self-rated scalar: keep
it only as a *measured* variable in the feedback loop until the data shows it separates winners — don't
gate on it.

### 6. Feedback / scoring loop — *the long-term ceiling* **[done 2026-06-09 (window-level v1)]**
**Done:** `OutcomeScorer` (pure) scores each stored prediction's `direction` vs the realized BTC move
at +1h/+24h (one Binance kline via injected `PriceSource`; bullish→up, bearish→down, neutral→|move|<0.05%).
`FeedbackReport` renders directional accuracy with **naive baselines** (always-up/down/random — essential
on a coin-flip target) + `impact_tier`/`source` breakdowns (the `confidence` breakdown was dropped with
#5). `FeedbackRunner` reads `analysis_*.json`,
writes `outcomes.jsonl`; exposed as **`anal feedback [--days N]`** (command thread; read-only, keyless) +
the standalone `ScorePastPredictions` (`feedback_report.pl`). Article-level scenario validation added
(`scoreArticles` → `article_outcomes.jsonl`): each resonant article's scenario checked vs its realized 1h
move (front_run→absorbed, fresh_*→sign, reversal→turns vs pre_trend, noise→always). **First backfill run
(n=12; 6 directional): 3/6 = 50%, +0pp vs baseline** — call at coin-flip on a tiny sample (expected).
**Still deferred:** full 11-metric report + scenario baselines. Real validation needs weeks of *live*
matured predictions. Every *new* signal must be recorded in the prediction record so the loop can measure
its contribution.

### 31. Fetch article body for resonant articles (IP-4a) **[idea]**
Claude gets only the title + ≤500-char description — too thin to assess the transmission mechanism
("SEC Takes Action on Crypto Exchange" = a $50K fine or a full shutdown?). After the screener picks the
resonant set (typically 3–15), fetch body text for *those few*: **`content:encoded` from the RSS XML
first** (free, already fetched, zero latency), then an optional readability fetch (5s timeout, graceful
fallback to title+description). Pass the first ~1000 chars to the deep pass. **Effort:** moderate (HTTP
latency 2–5s on the resonant articles only; a config flag to enable).

### 32. Pass all window articles to the deep pass as context (IP-4c) **[idea]**
Deep analysis sees only the resonant articles, so it can't tell "one big event in an otherwise quiet
session" from "one event among many competing stories" — which affects impact/conviction. When ≥1
resonant exists, also send the non-resonant ones as compact one-liners (title + screener score) under a
`WINDOW CONTEXT (N other articles, scored noise)` heading. **Effort:** small (~20–30 lines in
`PromptBuilder`). Most useful with #31.

### 33. Give the deep pass the full description (IP-4b) **[ready — verify first]**
IP-4b (Python) noted the *decisive* deep pass got a shorter description slice (`[:200]`) than the cheap
screener (`[:300]`) — an oversight. **Verify the Java `PromptBuilder` truncation**; if the deep block
truncates shorter than the screener, widen it to the full (or `[:500]`) description for the resonant-only
set — negligible token cost, the expensive pass should never see *less* context than the screen.

### 34. Extended trading-signal output: horizon / cascade / invalidation (IP-4d remainder) **[idea]**
The deep schema emits direction/impact_tier/`confidence`(=conviction, done #5)/reasoning/key_events. For
a margin call, add: `time_horizon` (e.g. "30min–2h"), `cascade_risk` (bool — true when liquidation
clusters are within ~3% in the predicted direction *and* funding is crowded the wrong way), and
`invalidation` (what would reverse the call). **Most valuable once liquidation clusters (#27) + funding
trend (#24) exist** to inform cascade_risk; pairs with #6 ("do `cascade_risk=true` calls see bigger
moves? are high-conviction calls more accurate?"). Touches deep schema + `DeepResult` + record +
notify/format.

### 36. Cross-window screener dedup — "first + causative only" **[done 2026-06-10 (live path); follow-ons open]**
**Problem (from a 12h log review):** one ongoing story (the US–Iran escalation) was re-flagged ~8×
`bearish/high` across adjacent windows while BTC sat flat — because the screener only saw *one window's*
articles, so it couldn't tell that 23:50/00:00/02:50 were the *same event* as 22:00. It also let through
the long tail of non-causative coverage (market reactions "oil rose after X", recaps "exchanged strikes
overnight", commentary "analysts warn") — and the lone `bullish` misfire was itself a recap. **Done:**
the screener now gets an `ALREADY COVERED (recent)` block of recently-resonant stories, and its
DUPLICATES rule was extended to score follow-ups/recaps/reactions/commentary of an already-covered event
as **0**, with an EXCEPTION for a **materially new development** (new strike/actor/escalation/concrete
fact) — so the "first, causative" report still fires but the wave of follow-ups doesn't.
**Mechanism (record-sourced, 2026-06-10):** `recentlyCovered(day, key)` reads the resonant articles
(title + `published_at`) from the **stored analysis records** (`store_.get`, `prediction_record.articles`)
of the preceding windows within `screenerDedupLookback` (config, default **6h**, stepped via
`Intervals.back`), and `PromptBuilder.coveredBlock` injects them into the screener prompt. Sourced from
records, **not** a volatile in-memory ring — so it **survives a restart** (no warm-up) and applies to
**live, on-demand re-analysis, and backfill alike**, keyed by the window's own time. The current window
is excluded (its own duplicates are caught in-batch). 168 tests green. **Open follow-ons:** (a) tune the
6h lookback via the feedback loop; (b) watch the make-or-break "materially-new vs rehash" wording for
over-suppression (missing a real new development) vs under (residual dups).

---

## Signal quality (positioning inputs)

> Replace the macro-analyst data mix with **derivatives-trader** signals that directly serve the
> long/short margin decision. **Done: funding + OI fusion (#24/#25).** Remaining order: liquidations
> (#27) → whale flow (#28), then unify the prompt block (#29). Each follows the established fetcher →
> snapshot registry → signal-class → deep-prompt pattern (mirror the macro/options/funding fetchers).

### 24. Funding-rate trend — building vs unwinding **[partly addressed 2026-06-17]**
The intent — distinguish leverage **building** vs **unwinding** — is now delivered via the **OI** trend
(#25): `FundingSignals` fuses funding crowding (level) with the ~1h **OI** change into a setup. The
funding-*rate* delta itself (is the crowding intensifying) is **not** added and is largely redundant now
that OI carries the build/unwind signal. Revisit only if the rate-delta proves to add signal over OI.

### 25. Perp open interest (IP-2b) **[done 2026-06-17]**
**Done:** `FundingFetcher` fetches OI from the keyless Binance `/openInterest` alongside funding and
stores the raw value **in the funding snapshot** (`{funding_rate, mark_price, open_interest}`; one
fetch, no new stream). `FundingSignals` now fuses **funding crowding × ~1h OI Δ × ~1h price move** into
a cascade/squeeze `setup` via the OI×price matrix: OI building into rising price = new longs
(`down_cascade_fuel`), into falling price = new shorts (`up_squeeze_fuel`), OI unwinding = `exhausting`.
`WindowContext` reads the funding snapshot ~1h back (OI delta) + price-context price then-vs-now.
Prompt line: `positioning: crowded_long (funding +0.038%) | OI +2.1%/1h building -> down_cascade_fuel`,
and the deep prompt is told to use it to scale how *violently* a confirmed catalyst lands. Endpoint shape
verified live. Liquidation clusters (#27) are the natural next step (WHERE it cascades).

### 27. Liquidation clusters (IP-2c) **[idea — needs CoinGlass key]**
Funding/OI say leverage is loaded; liquidation clusters say **WHERE it cascades** — the nearest large
long cluster *below* and short cluster *above* price, with distance %. The most directly *tradeable*
signal: it gives the realistic **target** of a move, not just direction ("bearish + long cluster 2.8%
below ~$800M → cascade target"). **Source:** CoinGlass free tier (liquidation heatmap), needs a free API
key + rate-limit handling. Prompt line: `liquidations: long cluster $105,200 (2.8% below, ~$800M) | short
cluster $112,400 (3.9% above, ~$450M)`. **Effort:** moderate. Most useful after #24/#25; enables #34's
`cascade_risk`.

### 28. Exchange net flow / whale alerts (IP-2d) **[idea — needs API key]**
Large BTC → exchange is a leading **sell** signal (coins moved to sell), observable before the selling and
before any article. **Source:** Whale Alert API (free tier ~10 req/min), poll 30–60s, filter BTC > ~$10M
to/from known exchange wallets, compute rolling 1–2h net flow. Prompt line: `whale_flow: $180M net
exchange inflow (last 2h) — sell pressure building`. **Effort:** moderate (API key, polling integration).

### 29. Unified `MARKET POSITIONING` prompt block (IP-2e) **[idea — after the signals exist]**
Once funding-trend/OI/liquidations/whale-flow land, replace the separate signal lines with one coherent
block (`funding / perp_oi / options / liquidations / whale_flow / macro_regime`) + guidance on combining
them for the long/short call. **Effort:** small, incremental as signals are added. (Partly anticipated:
the *alert* already groups funding-positioning + 24h-range on one line.)

---

## Urgent lane / latency

### 7. Urgent event → alert fast path (screener bypass, IP-3a/3b) **[rejected 2026-06-09 — do not revisit without BL#6]**
Implemented (screener bypass for `urgent_worthy` articles), then **reverted**. The premise — "urgent
articles are already keyword-screened, so the Haiku screener is redundant" — is a category error: the
urgent keyword filter is a **detection/latency** signal ("look now"), not a **relevance** judgment ("has a
BTC transmission mechanism"). They diverge constantly — the 2026-06-08 `NATO jets … drone` headline was
keyword-urgent yet correctly screened **0**. Bypassing routes **noise into the expensive Sonnet pass** (a
cost *increase* over the ~free batched Haiku score) and feeds tier-inflation/alert-fatigue. The only real
upside — rescuing screener false-negatives on genuinely impactful urgent news — is rare and **unmeasurable
without #6**. If revisited, do it as a *threshold tweak* for urgent items (e.g. 6→4), never a full bypass,
and only once #6 can prove it pays.

### 8. Tighter urgent cadence **[idea]**
`urgentPollInSec=30`. Urgent sources are free RSS, so 30→15s roughly halves detection latency. Trade-off:
more requests (politeness/rate-limit) and more frequent per-article OHLC fetches. Cheaper now that #16
makes most polls a bodyless 304.

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

### 30. Post-publication price delta into the prompt (IP-3c) **[partly done 2026-06-09]**
**Done (alert side):** the live alert now shows `BTC: $X now | $Y at news (−0.8% since, HH:MM)` — the
realtime price + % move since the catalyst, telling the *recipient* how much already played out.
**Open (prompt side):** also pass a per-article `since_publish` delta to **Claude** in the deep block, so
the model itself can reason "event partially absorbed, lean neutral" and avoid a too-late call. The
realtime-price fetch already exists (`collector.fetchClosePriceAt`); thread it per-article into
`PromptBuilder.deepArticles`. **Effort:** small.

---

## Observability (optional enrichments)

### 11. Blank-key startup validation **[done 2026-06-08]**
`FSAnalyser.buildClaudeClient` logs a single explicit startup WARN when `ANTHROPIC_API_KEY` resolves blank
(was ~300 lines of HTTP 401 noise); `Secrets` stays empty-on-missing.

### 12. Raw macro numbers + per-source breakdown in the context log **[idea]**
The collector `Context …` line shows presence + BTC price. Optionally add raw VIX/DXY/SP500 moves (echo,
not interpretation) and a per-source article breakdown (`27 new: rss 25, …`) to spot a silently-dead
source. (Related: the cycle-summary log now reports per-window article counts.)

---

## Verification / operational

### 13. Live end-to-end run **[done 2026-06-08]**
Verified live: `anal window 20260608_1320` drove a real Haiku screener + Sonnet deep pass →
`analysis_*.json` (bearish/high) → **both** Telegram and email delivered. Surfaced + fixed a real defect:
Telegram `parse_mode:"Markdown"` choked on the `_` in `risk_off` (HTTP 400) — now plain text.

### OPERATIONAL — let it bake + measure **[ongoing]**
Live analysis only started firing reliably after the 2026-06-09 bucketing fix, so the feedback loop has
~no matured *live* data yet. Keep the instance running; run `anal feedback --days N` periodically. This is
the **gate** on further signal-quality tuning — don't tune what you can't yet measure.

---

## Detection — faster & broader coverage (Phase 1)

Pure speed + coverage; the highest-leverage area (you can't trade on what you haven't seen). **Read the
two cross-cutting items (16, 22) first — they gate the rest.** Several items framed elsewhere as
"free/config-only" understate real costs, called out per item.

### 16. Conditional GET (ETag / If-Modified-Since) — *prerequisite for the phase* **[done 2026-06-08]**
An unchanged feed returns a bodyless `304 Not Modified`, making high-frequency polling cheap + polite.
**Done:** `Http.getConditional` (304 = non-error, null body) + a per-feed `ETag`/`Last-Modified` cache in
`RssSource` (replayed as `If-None-Match`/`If-Modified-Since`, kept across a 304). De-risks #17 and lets
#18's feeds be promoted to a faster lane politely.

### 17. Poll all RSS feeds at high frequency (IP-1a) **[idea — now unblocked]**
Move the ~10 boundary-only feeds (CoinDesk/Bloomberg/CNBC/…) into the 30s urgent model — **biggest single
latency win** (≤10min → ~30s). **Now unblocked by the 2026-06-09 bucketing fix** (before, live analysis
didn't fire at all, so faster polling was pointless; now it directly cuts time-to-see). Two costs: (a)
"doesn't add analysis cycles" is conditional — Bloomberg/CNBC are high-volume and trip broad keywords far
more than the 4 niche feeds, risking analysis-cost/alert-fatigue, so **audit urgent-keyword hit-rate on
those feeds first**; (b) rate-limit/WAF risk at 30s × ~14 feeds → needs #16 (done). Keep the regular
lane's window-context assembly + bucketing intact. **Verdict: high value; do after the keyword audit.**

### 18. Add government / regulatory feeds (IP-1b) — *best value/effort* **[partly done 2026-06-08]**
**Done:** `SEC_Press` (`https://www.sec.gov/news/pressreleases.rss`) on the regular lane (200 + valid RSS
with the app UA; ETF/enforcement/Wells notices surface here). Regular not urgent deliberately: SEC's
fair-access policy 403s the app UA on HTML/EDGAR, so 10-min polling is polite where 30s risks a block.
**Could NOT add (verified 2026-06-08):** OFAC RSS **retired 31 Jan 2025**; Treasury press has **no RSS**;
SEC litigation `/rss/*.xml` **403s the app UA**. **Update 2026-06-10:** Fed (`press_all.xml`) was **already** an urgent source (the earlier note was
wrong). **White House added** to the urgent lane via `https://www.whitehouse.gov/presidential-actions/feed/`
(RSS 2.0, RFC-1123 `pubDate`, app UA OK, ~few/week — the research's `/feed/` is a **404**; this is the
real path, carrying EOs/proclamations/NSPMs/tariffs/sanctions). **Central banks done (2026-06-10):** ECB was
already in `RssFeeds` (`rss/press.html`); added **BoJ** (`boj.or.jp/en/rss/whatsnew.xml`) + **BoE**
(`bankofengland.co.uk/rss/news`) to the **regular** lane — all RSS 2.0 / RFC-1123, app-UA OK. Rule: **US
Fed urgent**, **foreign CBs regular** (scheduled decisions caught in 10 min; feeds noisy with
speeches/stats/admin, screener filters). **Remaining:** SEC-litigation/Treasury via a non-RSS source or
per-feed UA. (WH `/news/feed/` also works but is
broader/noisier — left out of the urgent lane deliberately.)

### 19. Add crypto-native feeds (IP-1c) — *selective* **[idea]**
Binance/Coinbase **listing-delisting-halt** announcements move price before media — high value. Caveat:
least RSS-friendly (Binance's endpoint is unofficial; Coinbase's blog is PR-heavy), so the work is a
**reliable feed**, not config. Ethereum Foundation = low short-term BTC relevance, **skip**.

### 20. Add Asian news sources (IP-1d) — *moderate* **[idea]**
Covers the Asian-session blind spot (00–07 UTC, thin liquidity amplifies moves). Asterisk: the most
market-moving Asian news breaks in *local-language* sources first; English editions (NHK/SCMP/Yonhap)
**lag**. Prioritize **SCMP (China-regulatory) + Yonhap (Korea)** over NHK.

### 21. Economic-calendar integration (IP-1e) — *highest ceiling* **[done 2026-06 (CPI/NFP via BLS)]**
**Done as a proper module (Stage 3).** A static catalog `cfg/econ_definitions.json` (name/series/kind/
unit/hot_direction/bands) is joined by name to per-date `data/<date>/econ_schedule_<date>.json`
(release time + consensus) → `EconEvent`. `EconScheduler` arms a timer at each release time and polls the
**BLS public API v2** until the fresh print lands, computes the **surprise vs consensus**
(`EconEventSignals`), and — when it clears the band — runs an article-less deep pass (`econ_analysis.txt`)
→ `econ_alert` (a `scheduled_event`-style record), notifying via the news-free gate. Manual `collect econ`
(fetch-only) / `anal econ` (analyse) catch-ups for releases missed while down. Gated off by default
(`econEnabled`). The only manual step left is authoring the schedule's consensus — see #21a. Covers
CPI/NFP; BEA (GDP/PCE) not yet added.

### 21a. Auto-build the econ schedule via a scheduled Claude routine **[idea]**
The econ module (#21) is built (static `cfg/econ_definitions.json` + per-date dynamic
`data/<date>/econ_schedule_<date>.json` with release time + consensus; `EconScheduler` arms + resolves;
`collect econ`/`anal econ` catch-ups). The remaining manual step is **authoring the schedule** (~2 min/month).
The Java `EconScheduler` *can't* automate this — it has no programmatic source for **consensus** (no free
API: TradingEconomics guest 410, Finnhub/FMP keyed+premium, Forex Factory blocks scrapers) and BLS exposes
no clean future-release-date endpoint. **The right tool is a scheduled Claude routine** (cloud cron via the
`/schedule` skill), not extending the Java scheduler: monthly, it reads `cfg/econ_definitions.json`, finds
upcoming releases lacking a schedule file, web-researches the BLS release date + a free calendar's consensus,
writes `data/<date>/econ_schedule_<date>.json` and commits (now that `release/data/` is git-tracked). Clean
producer/consumer split — **routine authors, `EconScheduler` consumes**. Decisions: review the routine's
first output before trusting; sync model (cloud commit → `git pull` vs a local recurring run writing straight
into `release/data`).

### 22. Cross-cutting caveats for the phase **[note]**
- **Detection without a faster alert path is half a win** — 17–21 make you *see* sooner; action still
  waits on the window cadence + notify gate. (The 60s cooldown was removed 2026-06-09; the per-window
  notify-on-change dedup replaced it.)
- **Adding recall with no way to measure it** — more sources = more catch *and* more noise; #6 is how you
  prove added sources pay off. Not a blocker; go in knowing it's intuition-driven until measured.
- **Revisit `MAX_CONCURRENT_FETCHES`** (currently 4): if the high-freq pool grows to ~18 feeds, bump to
  ~6 (safe now that #16 makes most fetches a cheap 304).

### 35. Primary sources — go to the source, not the journalist **[idea — see consult notes]**
The strategic case (assessed 2026-06-10): for macro/geopolitics the edge isn't "faster Bloomberg" — it's
**central banks / governments / data APIs that ARE the event**, before any journalist rewrites them.
Concretely, in rough value order: **Fed `press_all.xml`** + **White House `/feed/`** (free RSS, near-zero
publish lag — these belong in the urgent lane, supersede #18's central-bank gap); **BLS/BEA data APIs**
(poll the raw CPI/NFP/GDP number at the known release time — the highest-edge piece of #21); **AP/AFP
wires** (RSS still live, unlike Reuters which retired public RSS in 2020); ECB/BoJ/BoE. **Honest caveats:**
the truly-first layer for geopolitics (gov/military X, OSINT Telegram, shipping intel) is **not RSS** and
carries a noise/verification cost; wire-RSS is no longer the leading indicator. Per-source assessment +
phasing thrashed out 2026-06-10 — **tiered verdict: central-bank/exec RSS = clear win (do next); data
APIs = high value but a real module (the consensus/forecast feed is the catch); AP/AFP wires =
incremental, verify feeds; OSINT Telegram/X = skip (latency edge real, but cost/noise/verification/
curation/architecture mismatch isn't justified).** Overlaps/extends #18, #21.

---

## Housekeeping / deferred decisions

### 23. Parallelize the backfill **[idea]**
`anal windows -run` analyses windows **sequentially** on the single `FS-Backfill` thread, each blocking on
its Claude calls (~1.5–2s Haiku/window + ~8–9s Sonnet/resonant window) → ~10 min for ~150 windows. Run K
windows concurrently (bounded pool, K~4–6) → ~K× faster. **Needs:** thread-safe `AnalysisStore`/
`AnalysisRegistry` writes (today serialized by the single live worker), a modest K (Anthropic rate limits;
live lane shares the JVM), pre-recovering days once. Keep the live analyser single-threaded (decided).

### 26. `price_context` incremental fetch **[idea]**
`storePriceContext` re-fetches all **24h of hourly bars** every window to recompute four derived numbers,
though only the latest hour differs between windows. One cheap call so it's been fine; if trimming fetches
matters, cache/incrementalize the hourly series the way the 1m OHLC series now is. Storage already lean.

### 15. Crypt package **[idea]**
A DES + PGP utility package was deliberately **not** imported. Add only if a concrete need appears.

---

## Recommended order

Done since this list was last drawn: funding+OI (#24/#25), econ module (#21), screener 0–3, structured
outputs, deep-pass thinking, the X source, the monitor reframe. Remaining, in rough value order:

1. **Keyword-hit-rate audit → faster polling (#17)** — biggest detection win; audit broad-feed keyword
   hit-rate, then move boundary feeds to the 30s urgent model.
2. **Primary central-bank/exec feeds (#35/#18 remainder)** — SEC-litigation / Treasury via a non-RSS
   source or per-feed UA; AP/AFP wires (verify feeds).
3. **Liquidation clusters (#27)** — WHERE leverage cascades (pairs with funding+OI; needs CoinGlass key);
   enables `cascade_risk` (#34).
4. **Prompt-input wins (#33 full description, #32 all-articles context, #31 article body, #30 since_publish)**
   — cheap-to-moderate inputs to the decisive pass.
5. **Whale flow (#28), unified positioning block (#29), rolling-synthesis (#1)** — later, larger builds.
6. **ETF-flow regime + BEA (GDP/PCE)** — for the future trading/daily-synthesis layer, not the fast
   monitor (daily cadence; assessed not worth a real-time fetcher now — catch flow *news* via a source).
7. **Auto-build the econ schedule (#21a)** — a scheduled Claude routine to author the consensus files.

Running continuously alongside all of it: **let it bake + `anal feedback`** (the measurement gate).
