# FinSent — BTC News-Event Monitor

FinSent is an always-on backend that watches financial news and market data and, when something
happening *right now* is a **real, materially crypto-impactful event**, pushes an alert
(Telegram / email). It is built for **fast detection and triage of market-moving events** for
**Bitcoin (BTC)** — catching a genuine catalyst (an executed policy, an enforcement action, a
confirmed geopolitical shift, a macro surprise) and filtering out the bluff, speculation, recaps and
already-priced noise that dominate the feed.

It is a **monitor, not a price oracle.** The analysis decides whether an item is a *new, concrete
reason to act* and how *material* it is; it adds a secondary directional **lean** (bullish / bearish /
unclear), but it does **not** forecast prices, percentages, or timing. A long/short **paper trading
module** (`FSTrader`) now runs off the analyser's signals — opening a simulated long on bullish /
short on bearish high-impact news and managing the exit with a trailing stop — but it is **paper only**
(no real orders, no exchange keys) and **off by default**. The positioning signals a live version would
lean on (perpetual funding + open interest) are already collected and fused; a live broker (WhiteBIT
USD-M perpetual futures) is a future drop-in behind the same `IBroker` interface.

---

## 1. The idea

### What problem it solves
Crypto trades 24/7 and reacts to a wide surface of catalysts: crypto-native (ETF flows, exchange
failures, hacks, regulation, stablecoin risk), macro/monetary (rate decisions, inflation prints,
central-bank signals), geopolitics (war/peace, sanctions, oil shocks), and trade policy. A human can't
watch all of it. FinSent ingests a fast news + market feed, filters it to the few items with a genuine
price-moving mechanism, and surfaces only the high-materiality subset to a human — quickly.

### What actually moves the market (the deep test)
The core judgement is a **three-step test** the deep pass runs on every screened item, in order — fail
any step and it's noise:

1. **FACT, or posture?** Did the causative action actually happen / get officially decided? A threat,
   intention, plan, demand or prediction is posturing about a future that hasn't happened (serial
   bluffers' threats especially) — not a fact.
2. **NEW, or a description of the known?** Does it report something the market did *not* already expect?
   The **surprise** is the signal; an anticipated outcome or a description of the existing situation is
   already priced.
3. **CHANNEL to crypto?** Is there a concrete transmission to BTC — monetary/rates/liquidity, the USD,
   regulation, crypto structure (exchanges/ETFs/flows), or a genuine broad risk shock?

Only a FACT that is NEW and has a CHANNEL is a material, directional call. The **mechanical market
context** (below) then scales *how violently* it lands — it never turns a non-event into a catalyst.

### What it is — and is NOT
- **Is:** a fast detector + triage + materiality/lean engine. Its strongest output is on high-magnitude,
  unambiguous, confirmed events; the alert gate deliberately fires only on `impact = high`.
- **Is NOT:** an oracle. Short-horizon BTC direction is intrinsically noisy (flows, liquidations,
  reflexivity dominate), and the feedback loop measures the directional *call* at roughly coin-flip. The
  edge is **detection**, not prediction — treat the lean as one probabilistic input.

### Two lanes, by urgency
- **Regular lane** — every 10-minute window: collect the full news + market context and (when running)
  analyse the window.
- **Urgent lane** — poll a small set of breaking-news sources every ~30s (fast RSS + the X/Twitter
  amplifier source) so a fast-moving event is detected within seconds, not at the next boundary.

There is also a **standalone macro-alert path** (optional, off by default): when a window has no
resonant news but the macro tape breaches a threshold, a mechanical alert is escalated to a Claude
judgement that can confirm or downgrade it.

---

## 2. How it works (at a glance)

```
            ┌──────────────────────────── FSCollector (subject) ────────────────────────────┐
 news ───▶  │  RSS / X-squawk(GetXAPI) / NewsAPI / Polygon / CryptoPanic ─┐                  │
 macro ──▶  │  Yahoo indicators (MacroFetcher, opt)        ┌──────────────┤                  │
 options ▶  │  Deribit options  (OptionsFetcher, opt)      │  filter (age + watermark) ─▶ store
 funding ▶  │  Binance funding + OI (FundingFetcher)       │  + ensure context snapshots    │
 price ──▶  │  Binance klines + 24h ticker (OhlcFetcher)  ─┘  (macro/options/funding/ohlc/price/econ)
 econ ───▶  │  BLS actuals (EconScheduler → EconActuals)                                     │
            │            publishes CollectionResult per window  ──────────────┐             │
            └────────────────────────────────────────────────────────────────┼─────────────┘
                                                                              ▼  (EventBus)
            ┌──────────────────────────── FSAnalyser (observer) ──────────────────────────────┐
            │  dedup ─▶ Screener (Haiku, 0–3 relevance) ─▶ resonant? ─┬─ no ─▶ record screener-only
            │                                                         └─ yes ─▶ read mechanical context
            │                                                                  ─▶ Deep analysis (Sonnet,
            │                                                                     thinking + structured)
            │                                                                  ─▶ record + NotifyGate
            │                                                                  ─▶ (regular only) MacroAlert
            └─────────────────────────────────────────────────────────────────────────────────┘
                                                                              ▼
                                                  Telegram / email  (high materiality, fresh)
```

- The **collector** is the single source of truth for raw + contextual data. It never interprets.
- The **analyser** subscribes to the collector and does all interpretation (Claude + notification).
- They're decoupled by an in-process **EventBus**; the analyser runs on its own worker thread so Claude
  latency never blocks collection.

---

## 3. Architecture & design

### Event-driven, not batch
The collector owns per-type **in-memory registries**, persists to disk **asynchronously**, and
publishes a `CollectionResult` per cycle. The analyser is an `IEventListener<CollectionResult>` that
enqueues work to a dedicated daemon thread.

### Modules (Gradle multi-module)
- **`infra`** (`src/finsent/infra/src`, package `com.finsent.*`) — a trimmed application framework:
  `GlobalSystem` (process bootstrap, command interpreter + mail sender + log facility),
  `DirectorySystem`, the command interpreter (`CmdInterpreter` / `CmdGroupHandler` / `ICmdHandler` /
  `CmdArgParser`), logging, `MailSender` (Jakarta Mail, STARTTLS), XML config (`XMLData`), and the
  app-initializer lifecycle.
- **`monitor`** (`src/finsent/monitor/src`, package `com.finsent.{core,collect,analyse,feedback}`) — the
  business pipeline. Depends on `:infra`.

Build output goes to `JavaClasses.sun/JavaClasses/<module>`.

### Entry point & lifecycle
- **`com.finsent.FSApp`** — `main()` constructs the app then parks the main thread
  (`Thread.currentThread().join()`). **Required:** every pipeline thread is a daemon, so without parking
  `main` the JVM exits immediately after init.
- `FSApp` wires: `FSCollector`, `FSAnalyser`, the `anal` command group, `CollectorRunner` (10-min
  boundary scheduler), `UrgentPoller` (30s poller), `EconScheduler`, and the `collect` command group.
  It seeds the analyser's run state from `-DrunAnalyser` and the X source's from `-DfetchX` (both
  default **off**). Teardown is a JVM shutdown hook running uninitializers in order.

### Threads
| Thread          | Role                                                          |
|-----------------|---------------------------------------------------------------|
| `main`          | parks (keep-alive) + runs the command interpreter             |
| `FS-Collector`  | the 10-minute boundary collection cycle                       |
| `FS-Urgent`     | the ~30s urgent poll                                          |
| `FS-EventBus`   | delivers `CollectionResult` to subscribers                    |
| `FS-Analyser`   | the analysis worker (drains a queue; never blocks the bus)    |
| `FS-Econ`       | the econ scheduler (arms/polls scheduled releases)            |
| `FS-Rss`        | parallel per-feed RSS fetch pool (per `RssSource` instance)   |
| `FS-Backfill`   | dedicated thread for `anal windows -run` range backfill       |
| `FS-Persistence`| drains the atomic write queue to disk                         |
| `FS-Notifier`   | dispatches Telegram/email sends off the analysis thread       |

---

## 4. Data model & persistence

### Unit-of-Work persistence
`com.finsent.core.io.PersistenceService` is the only disk boundary. Registries are **pure in-memory
holders**: a mutation updates memory and returns a `WriteUnit`; the cycle collects all write-units and
commits them as **one atomic batch** (staged temp-write → rename-all). Recovery loads the most recent N
days (`recoveryLookbackInDays`, default 3) and hydrates the registries.

### Data layout
Files live in per-day folders, **git-tracked** for recoverability:
`release/data/<YYYYMMDD>/<prefix><YYYYMMDD><suffix>`.

| Stream          | File prefix         | Shape                                                                  | Owner    |
|-----------------|---------------------|------------------------------------------------------------------------|----------|
| `ARTICLES`      | `articles_`         | JSONL, one article object per line                                     | collector|
| `MACRO`         | `macro_context_`    | JSON keyed by `HH:MM` — Yahoo indicator snapshot (opt)                 | collector|
| `OPTIONS`       | `options_context_`  | JSON keyed by `HH:MM` — Deribit options snapshot (opt)                 | collector|
| `OHLC`          | `btc_price_`        | JSONL — `{ts,o,h,l,c,v}` 1-minute bars                                 | collector|
| `PRICE_CONTEXT` | `price_context_`    | JSON keyed by `HH:MM` — `{btc_price, change_24h_pct, range_pos_24h}`   | collector|
| `FUNDING`       | `funding_`          | JSON keyed by `HH:MM` — `{funding_rate, mark_price, open_interest}`    | collector|
| `ECON`          | `econ_actuals_`     | JSON keyed by event name — resolved scheduled release (consensus/actual)| collector|
| `ANALYSIS`      | `analysis_`         | JSON keyed by `HH:MM` — analysis record per window                     | analyser |

Feedback outcomes are also written per-day: `outcomes_<day>.jsonl`, `article_outcomes_<day>.jsonl`.

### Registries (`com.finsent.core.registry`)
- `ArticleRegistry` — monotonic id counter + content-hash dedup + per-source watermarks. **Invariant:**
  the id counter must never regress when an older day is lazily loaded for re-analysis.
- `IntervalSnapshotRegistry` — `HH:MM`-keyed snapshots; used for macro, options, funding, **and**
  price-context.
- `OhlcRegistry` — timestamp-merged OHLC bars.
- `EconEventRegistry` — resolved scheduled-release actuals, keyed by event name per day.
- `AnalysisRegistry` — analyser-owned analysis records.

The analyser owns a **separate** `PersistenceService` + `AnalysisRegistry` via `AnalysisStore`, so
interpreted output never leaks back into the collector.

---

## 5. The analysis pipeline (detail)

For each window the analyser runs:

1. **Dedup** the window's articles by title.
2. **Screener pass** (`ScreenerPass`, Claude **Haiku**, `temperature:0`, structured output) — score each
   article **0–3 for *relevance*** as a NEW, material BTC catalyst worth a closer look (**direction-free**
   — direction is the deep pass's job): `3` urgent, `2` material, `1` weak, `0` skip (recap/opinion/dup).
   Articles at/above `screenerThreshold` (default **2**) are **resonant**. A recall lean ("when unsure,
   lean up") and a light-touch credibility rule (a major-if-true claim is kept for the deep pass to
   adjudicate) bias toward not dropping real catalysts. If none are resonant, record a screener-only
   result and stop.
3. **Read mechanical context** for the window (`WindowContext` + signal classes): macro regime (opt),
   options positioning (opt), **funding + OI positioning**, per-article pre-trend/OHLC, and the price
   backdrop.
4. **Deep pass** (`DeepAnalysisPass`, Claude **Sonnet**, **adaptive thinking**, structured output) — runs
   the three-step FACT/NEW/CHANNEL test and returns one JSON object: `direction`, `impact_tier`,
   `key_events`, `reasoning`, plus a per-article `articles[]` (each `{i, direction, reasoning}`). The
   econ/macro variants are article-less (no `articles[]`).
5. **Record** the analysis (`AnalysisStore`).
6. **Notify gate** (`NotifyGate`) — fire Telegram/email **iff** `impact_tier >= notifyMinImpactTier`
   (default `high`) **and** a resonant article is fresher than `newsAgeToNotify` (default 1h). It fires
   on **materiality**, not on a directional bet — a high-materiality event with an *unclear* lean still
   alerts.
7. **(Regular windows only)** run the standalone `MacroAlertChecker` (when `macroEnabled`).

### Working with Claude (how the calls are made)
- **Structured outputs** (`output_config.format` with JSON Schemas in `ClaudeSchemas`) on both passes —
  the API returns schema-valid JSON, so a malformed reply can't slip through; `additionalProperties:false`
  also stops the model adding stray fields. A lenient parser remains as a safety net.
- **Adaptive thinking** on the deep pass (the decisive call), so the model reasons through the three-step
  test before answering; the response's first **text** block is parsed (a thinking block leads it).
- **`temperature:0`** for reproducible classification/extraction; omitted when thinking is on.

### Mechanical signals reference (`com.finsent.analyse.signal`)
Pure, model-free functions folded into the deep prompt **and** logged:
- **`MacroSignals.regime(...)`** → `risk_off | risk_on | mixed | neutral` (+ a `has_data` flag so a
  fabricated "neutral" is never shown when macro collection is off). Optional (macro off by default).
- **`OptionsSignals.signal(...)`** → positioning `bullish | bearish | neutral` (+ IV/OI/DVOL/skew) from
  the Deribit snapshot. Optional (off by default).
- **`FundingSignals.signal(...)`** → **perp positioning**: funding crowding (`crowded_long/short`,
  `extreme_*`) fused with the ~1h **open-interest** change (building/unwinding) and the ~1h price move
  into a cascade/squeeze `setup` (`down_cascade_fuel` / `up_squeeze_fuel` / `exhausting`). The higher-
  value positioning context for BTC (a leverage-cascade asset); on by default.
- **`MacroTrend.of(...)`** → per-indicator direction/streak over ~1h (when macro data present).
- **`PreTrend.of(bars)`** → `rising | falling | flat | volatile` from the article's pre-publication OHLC.
- **`EconEventSignals.signal(...)`** → the mechanical surprise-vs-consensus read for a resolved release.
- **Price backdrop** (`PRICE_CONTEXT`) → current price, 24h % change, 24h range position — used as
  backdrop (already-priced-in context), never as a causal attribution of a 24h move to a 10-min window.

### Output / notifications
The stored record + the `Analysed …` log carry `direction`, `impact_tier`, `macro_regime`,
`resonant_count/article_count`, `btc_at_prediction`. Alerts read **event-first**: `CRYPTO EVENT (N
articles)` → the key events + reasoning → `Materiality <TIER> | Lean <LEAN>` → a BTC price line (catalyst
price, live price, % since). There is no model-self-rated `confidence` field — it was removed as
uncalibrated and redundant.

---

## 6. Workflows

### Regular collection (`FSCollector.collect`, every 10 min)
ensure context (macro/options/funding/OHLC strip/price-context, each stored once per window) → fetch all
configured sources → drop stale (`articleMaxAge`) and at/below-watermark articles → store + commit
atomically → publish per-window `CollectionResult`s.

### Urgent poll (`FSCollector.collectUrgent`, every ~30s)
fetch the urgent sources (fast RSS + the X-squawk source) → flag urgent-worthy articles (`UrgentKeywords`)
→ fetch per-article OHLC for any urgent article in the window → store + commit → publish.

### Scheduled econ releases (`EconScheduler`, optional)
arm a timer at each release time (from the static catalog joined to the per-date schedule) → poll BLS v2
until the fresh print lands → compute the surprise → article-less deep pass → `econ_alert` + notify.

### Runtime commands
- **`anal on | off | status`** — turn analysis on/off (resume/pause), show state (`start`/`pause` are
  aliases). Plus `anal window <YYYYMMDD_HHMM>`, `anal windows <N>` (last N days through now) or
  `anal windows -start .. -end .. [-missing|-force] [-run]
  [-notify]` (dry-run scan/backfill), `anal show <key>`, `anal econ [YYYYMMDD] <event> [-quiet]`,
  `anal feedback [--days N]`.
- **`collect x on | off | status`** — turn the X (Twitter) source's polling on/off live (no restart).
  `collect econ [YYYYMMDD] <event>` — fetch a release's BLS actual on demand (fetch-only).
- **`trade on | off | status | flatten`** — turn the trader on/off (resume/pause acting on signals),
  show state + open position + today's realized P&L, or close the open position now.
- **`fastmove on | off | status`** — turn the FastMove momentum poller's detection on/off live (no
  restart), or show its state.

### Feedback / scoring loop (`anal feedback`)
`OutcomeScorer` scores each stored prediction's direction vs the realized BTC move at +1h/+24h (one
Binance kline); `FeedbackReport` prints accuracy with naive baselines (always-up/down/random) and
`impact_tier`/`source` breakdowns + a Claude-vs-mechanical comparison. Read-only, keyless. The
measurement gate for any signal-quality change.

---

## 7. Configuration

All runtime config lives in **`release/cfg/processes.xml`** (`<FSCollector>` + `<FSAnalyser>` sections of
the `<FSSatellite>` node); `release/cfg/system.xml` holds the log facility.

### Sources & feature flags (collector)
- News sources (`<Sources>`): `rss`, `newsapi`, `polygon`, `cryptopanic` (the keyed ones skip when no key).
- `<RssFeeds>` (regular lane, crypto-native + monetary/regulatory primaries): CoinDesk, TheBlock,
  BloombergCrypto, ECB, BoJ, BoE, SEC_Press. `<UrgentSources>` (30s lane): TruthSocial_Trump, FedReserve,
  WhiteHouse.
- **X (Twitter) amplifier source** (GetXAPI): `getxapiKey="ENV:GETXAPI_KEY"`, plus `<XAccounts>` (core,
  permanent) and `<XSituationalAccounts>` (curate/prune as events shift) — merged into one
  `from:a OR from:b …` query (≤ 25 handles). Built whenever a key + accounts exist; *polling* is gated by
  the `-DfetchX` flag / `collect x on`.
- **Feature flags:** `fundingEnabled` (default **true**), `macroEnabled` / `optionsEnabled` / `econEnabled`
  (default **false**). `articleMaxAge`, `urgentPollInSec` (30), `ohlcBarSize`, market-data base URLs, etc.

### Analyser params
`claudeDeepAnalModel` (sonnet-4-6), `claudeScreenerModel` (haiku-4-5), `anthropicMessagesUrl`,
`screenerThreshold` (2), `promptsDir`, `notifyMinImpactTier` (high), `newsAgeToNotify` (1h), Telegram +
SMTP delivery, `<MacroAlertThresholds>`.

### Startup flags (launcher `-D`, default OFF when absent)
- **`-DrunAnalyser`** — `true` runs the analyser at start; `false`/absent starts it **paused** (no Claude
  calls / alerts until `anal on`).
- **`-DfetchX`** — `true` starts X polling; `false`/absent starts it **off** (no GetXAPI calls until
  `collect x on`).
- **`-DrunTrader`** — `true` starts the trader acting on signals; `false`/absent starts it **paused**
  (no positions opened until `trade on`). Live vs paper is the separate `<FSTrader broker>` config.
- **`-DrunFastMove`** — `true` starts the FastMove momentum poller detecting; `false`/absent starts it
  **paused** (no fires until `fastmove on`).

### Secrets (`com.finsent.core.Secrets`)
`ENV:VAR` (or `$VAR`) values resolve to a real env var first, else the gitignored `release/.env`, else
empty. Required for a full run: `ANTHROPIC_API_KEY` (+ `TELEGRAM_TOKEN` / `SMTP_PASSWORD` for alerts);
optional: `GETXAPI_KEY` (X source), `BLS_API_KEY` (econ), `NEWSAPI_KEY` / `POLYGON_KEY` /
`CRYPTOPANIC_KEY`. **Never commit secrets.**

### Prompts
`release/prompts/{screener,deep_analysis,econ_analysis,macro_analysis}.txt` are the Claude templates
(loaded by `PromptTemplates`, filled by `PromptBuilder`). Editing them changes behaviour with no
recompile (a re-analysis re-reads them immediately). **XML comments in config must not contain `--`.**

---

## 8. Observability

Each window reads as a consistent **inputs → judgment → alerts** trail:

```
FSCollector:       Context  20260605 13:00 -- macro=MISSING options=MISSING funding=yes OHLC=12bars BTC=$67432.10
FSCollector:       Urgent: collected 1 new article(s) in 13:00(1)
FSAnalyser:        Analysed 20260605 13:00 -- direction=bullish impact=high regime=n/a options=n/a resonant=3/12 BTC=$67432.10
MacroAlertChecker: Macro alert 20260605 13:00 -- direction=bearish impact=high regime=risk_off triggers=2 -- VIX +12% ...
```

- **Context** (info, once per window) — which context snapshots landed + BTC price; `MISSING` / `0bars` /
  `n/a` flag a degraded or disabled input.
- **Urgent** / **Analysed** / **Macro alert** — the per-window detection, judgment, and macro path.
- Logs roll daily under `release/logs/` (`FSSatellite.<yyyy-MM-dd>.log`); `release/logs/` is gitignored.

---

## 9. Build, run, test

**JDK 17** required (records). Toolchain JDK here: `C:\tools\java\jdk-17.0.15_6_temurin_x64`.

### Build (multi-module, offline — via PowerShell)
```
$env:JAVA_HOME = "C:\tools\java\jdk-17.0.15_6_temurin_x64"
.\gradlew.bat :infra:compileTestJava :monitor:compileTestJava deployRelease --offline
```

### Run
Launched by `release/bin/FSSatellite.pl` (with `FinSent.pm`), which boots `com.finsent.FSApp` with
`-type FSSatellite -bootstrapDataFile cfg/processes.xml` and JVM flags including
`-Djava.net.preferIPv4Stack=true`, `-DrunAnalyser=false`, `-DfetchX=false`. Put secrets in
`release/.env` first. After startup, `anal on` to start analysing and `collect x on` to start the X
source.

### Test
The Gradle `test` task is **disabled**; run JUnit4 `*_utest` classes directly with the absolute JDK-17
java (~197 tests):
```
& "<jdk17>\bin\java" -cp "JavaClasses.sun\JavaClasses\infra;JavaClasses.sun\JavaClasses\monitor;JavaClasses.sun\JavaClasses\test\monitor;lib\*" org.junit.runner.JUnitCore <fqcn ...>
```

> **Outward-facing operations** (a live run that makes real Claude calls and fires real Telegram/email;
> the **paid** GetXAPI X source) cost money and contact third parties. Run them **only with explicit
> human go-ahead**. Both the analyser and the X source starting **off** is the guard.

---

## 10. Repository layout

```
src/finsent/infra/src/com/finsent/...     # application framework (GlobalSystem, cmd, log, mail, xml, app)
src/finsent/monitor/src/com/finsent/
  core/        # Config, Http, Json, Num, Times, Secrets; io/ (persistence, streams); registry/; event/
  collect/     # FSCollector, fetchers (Macro/Options/Ohlc/Funding/EconActuals), Econ* (scheduler/defs/
               # schedule/event/registry), CollectorRunner, UrgentPoller, UrgentKeywords, OhlcWindows,
               # source/ (RssSource, XSquawkSource, NewsAPI/Polygon/CryptoPanic, RssParser), cmd/ (collect)
  analyse/     # FSAnalyser, claude/ (client, prompts, json, ClaudeSchemas), pass/ (screener, deep),
               # signal/ (macro/options/funding+OI/macro-trend/pre-trend/econ/scenario), notify/ (gate,
               # telegram, email, messages, ImpactTier), cmd/ (anal), MacroAlert(+Checker),
               # AnalysisStore, WindowContext, Intervals
  feedback/    # OutcomeScorer, FeedbackRunner, FeedbackReport, ScorePastPredictions
  FSApp.java   # entry point
src/finsent/monitor/test/...              # JUnit4 *_utest suites
release/
  bin/         # FSSatellite.pl + FinSent.pm (launcher) + feedback_report.pl
  cfg/         # processes.xml (config) + system.xml (log facility) + econ_definitions.json
  prompts/     # screener / deep_analysis / econ_analysis / macro_analysis .txt
  data/        # per-day data/<date>/ files (git-tracked)
  logs/        # FSSatellite.<yyyy-MM-dd>.log (gitignored)
  .env         # secrets fallback (gitignored)
```

---

## 11. For contributors & AI agents

- **`AGENTS.md`** — build/test/run commands, runtime flags/commands, non-obvious invariants, gotchas.
  Read it first.
- **`BACKLOG.md`** — the canonical planning doc: strategic framing + open work; **`BACKLOG_ARCHIVE.md`**
  is the dated changelog of shipped/decided items.
- **`.claude/rules/coding-guidelines.md`** — the coding standard for all source changes.
