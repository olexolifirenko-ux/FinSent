# FinSent — BTC News-Sentiment & Market-Context Analyser

FinSent is an always-on backend that watches financial news and market data, decides whether
anything happening *right now* is likely to move the price of **Bitcoin (BTC)** in the near term
(minutes to hours), and pushes an alert (Telegram / email) when a high-impact, fresh, directional
signal appears.

It is built for **speed of detection and triage of market-moving events**, not for long-horizon
forecasting. The durable edge is catching a market-relevant headline — a geopolitical shock, a
macro surprise, a crypto-native event — and forming a defensible directional read *before* the
market has fully repriced, then surfacing only the high-conviction subset to a human.

---

## 1. The financial idea

### What problem it solves
Crypto trades 24/7 and reacts to a very wide surface of catalysts: crypto-native events (ETF flows,
exchange failures, hacks, regulation, stablecoin risk), macro/monetary events (rate decisions,
inflation prints, central-bank signals), geopolitics (war/peace, sanctions, oil shocks), and trade
policy. A human can't watch all of it continuously. FinSent ingests a broad news + market feed,
filters it down to the few items with a plausible price-moving mechanism, and judges their net
directional impact on BTC.

### The core thesis
A BTC move over a given horizon is driven by **new information** interacting with **market
positioning and the prevailing macro regime**. So FinSent combines two kinds of input on every
analysis:

1. **News events** — the catalysts (what just happened).
2. **Mechanical market context** — computed, model-free signals that frame how a catalyst will land:
   - **Macro regime** (risk-on / risk-off / mixed / neutral) from traditional markets (VIX, DXY,
     S&P 500, gold, US 10y).
   - **Options positioning** (bullish / bearish / neutral) from BTC options (put/call skew, implied
     vol, open-interest shifts) — what "smart money" is positioned for *before* the news.
   - **Price backdrop** — BTC's current price, 1h and 24h change, and position in the 24h range —
     used to judge whether a catalyst is *already priced in*.
   - **Per-article pre-trend** — BTC's direction in the ~30 min before an article published.

The output per window is a directional call (`bullish` / `bearish` / `neutral`) with an impact tier
(`noise` < `low` < `high`), the causative events, and a one-line rationale.

### What it is — and is NOT
- **Is:** a fast detector + triage + directional-bias engine. Its strongest, most reliable output
  is on high-magnitude, unambiguous events. The alert gate deliberately fires only on `impact=high`.
- **Is NOT:** an oracle. Short-horizon BTC direction is intrinsically noisy (flows, liquidations,
  reflexivity dominate). Treat the directional call as **one probabilistic input**, strongest where
  it matters most (clear shocks) and near coin-flip on the ambiguous middle — which the gate filters
  out anyway.

### Two lanes, by urgency
- **Regular lane** — every 10-minute window, collect the full news + market context and (when the
  analyser is running) analyse the window.
- **Urgent lane** — poll a small set of breaking-news sources every ~30s so a fast-moving event
  (e.g. a geopolitical escalation) is detected within seconds, not at the next 10-minute boundary.

There is also a **standalone macro-alert path**: when a window has no resonant news but a macro
indicator breaches a threshold (e.g. VIX +10%), FinSent emits a mechanical macro alert independent
of the news pipeline.

---

## 2. How it works (at a glance)

```
            ┌──────────────────────────── FSCollector (subject) ────────────────────────────┐
 news ───▶  │  RSS / NewsAPI / Polygon / CryptoPanic   ─┐                                    │
 macro ──▶  │  Yahoo indicators  (MacroFetcher)         ├─▶ filter (age + watermark) ─▶ store│
 options ▶  │  Deribit options   (OptionsFetcher)       │   + ensure context snapshots       │
 price ──▶  │  Binance klines    (OhlcFetcher)          ─┘   (macro/options/ohlc/price)       │
            │                                                                                 │
            │            publishes CollectionResult per window  ──────────────┐              │
            └────────────────────────────────────────────────────────────────┼──────────────┘
                                                                              ▼  (EventBus)
            ┌──────────────────────────── FSAnalyser (observer) ──────────────────────────────┐
            │  dedup ─▶ Screener (Haiku)  ─▶ resonant? ─┬─ no ─▶ record screener-only          │
            │                                           └─ yes ─▶ read mechanical context      │
            │                                                     ─▶ Deep analysis (Sonnet)    │
            │                                                     ─▶ record + NotifyGate       │
            │                                                     ─▶ (regular only) MacroAlert │
            └─────────────────────────────────────────────────────────────────────────────────┘
                                                                              ▼
                                                       Telegram / email  (high-impact, fresh, directional)
```

- The **collector** is the single source of truth for raw + contextual data. It never interprets.
- The **analyser** subscribes to the collector and does all interpretation (Claude + notification).
- The two are decoupled by an in-process **EventBus**; the analyser runs on its own worker thread so
  Claude latency never blocks collection.

---

## 3. Architecture & design

### Event-driven, not batch
The system is event-driven. The collector owns per-type **in-memory registries**, persists to disk
**asynchronously**, and publishes a `CollectionResult` per cycle. The analyser is an
`IEventListener<CollectionResult>` that enqueues work to a dedicated daemon thread.

### Modules (Gradle multi-module)
- **`infra`** (`src/finsent/infra/src`, package `com.finsent.*`) — a trimmed application framework:
  `GlobalSystem` (process bootstrap, holds the command interpreter + mail sender + log facility),
  `DirectorySystem` (resolves paths against the release home), a command interpreter
  (`CmdInterpreter` / `CmdGroupHandler` / `ICmdHandler` / `CmdArgParser`), logging
  (`LogFacility` / `Logger`), `MailSender` (Jakarta Mail, STARTTLS), XML config (`XMLData`), and the
  app-initializer lifecycle (`AbstractAppInitializer`, `IUninitializer`).
- **`monitor`** (`src/finsent/monitor/src`, package `com.finsent.{core,collect,analyse}`) — the
  business pipeline. Depends on `:infra`.

Build output goes to `JavaClasses.sun/JavaClasses/<module>`; jars to `JavaClasses.sun/`.

### Entry point & lifecycle
- **`com.finsent.FSApp`** — `main()` constructs the app then parks the main thread
  (`Thread.currentThread().join()`). **This keep-alive is required:** every pipeline thread
  (collector, urgent poller, event bus, persistence, analyser worker) and the command interpreter is
  a daemon thread, so without parking `main` the JVM would exit immediately after init.
- `FSApp` wires four components: `FSCollector`, `FSAnalyser`, `CollectorRunner` (the 10-min boundary
  scheduler), `UrgentPoller` (the 30s poller). Teardown is a JVM shutdown hook running the
  uninitializers in order: schedulers → analyser (→ notifier → store) → collector.

### Threads
| Thread        | Role                                                          |
|---------------|---------------------------------------------------------------|
| `main`        | parks (keep-alive) + runs the command interpreter             |
| `FS-Collector`| the 10-minute boundary collection cycle                       |
| `FS-Urgent`   | the ~30s urgent poll                                          |
| `FS-EventBus` | delivers `CollectionResult` to subscribers                    |
| `FS-Analyser` | the analysis worker (drains a queue; never blocks the bus)    |
| `FS-Rss`      | parallel per-feed RSS fetch pool (per `RssSource` instance)   |
| `FS-Backfill` | dedicated thread for `anal windows -run` range backfill       |

---

## 4. Data model & persistence

### Unit-of-Work persistence
`com.finsent.core.io.PersistenceService` is the only disk boundary. Registries are **pure in-memory
holders**: a mutation updates memory and returns a `WriteUnit`; the cycle collects all write-units
and commits them as **one atomic batch** (staged temp-write → rename-all). Recovery loads the most
recent N days (`recoveryLookbackInDays`, default 3) and hydrates the registries.

### Data streams (`com.finsent.core.io.DataStream`)
One family of per-day files each, named `<prefix><YYYYMMDD><suffix>` under `release/data/`:

| Stream          | File prefix         | Shape                                                          | Owner    |
|-----------------|---------------------|---------------------------------------------------------------|----------|
| `ARTICLES`      | `articles_`         | JSONL, one article object per line                            | collector|
| `MACRO`         | `macro_context_`    | JSON object keyed by `HH:MM` — Yahoo indicator snapshot       | collector|
| `OPTIONS`       | `options_context_`  | JSON object keyed by `HH:MM` — Deribit options snapshot       | collector|
| `OHLC`          | `btc_price_`        | JSON object keyed by `HH:MM` — boundary OHLC price strip       | collector|
| `PRICE_CONTEXT` | `price_context_`    | JSON object keyed by `HH:MM` — `{btc_price, change_1h_pct, change_24h_pct, range_pos_24h}` | collector|
| `ANALYSIS`      | `analysis_`         | JSON object keyed by `HH:MM` — analysis record per window     | analyser |

### Registries (`com.finsent.core.registry`)
- `ArticleRegistry` — monotonic id counter + content-hash dedup + per-source watermarks.
  **Invariant:** the id counter must never regress when an older day is lazily loaded for re-analysis.
- `IntervalSnapshotRegistry` — `HH:MM`-keyed snapshots; used for macro, options, **and** price-context.
- `OhlcRegistry` — timestamp-merged OHLC bars (`{ts,o,h,l,c,v}`).
- `AnalysisRegistry` — analyser-owned analysis records (resonant ids, prediction, macro alerts).

The analyser owns a **separate** `PersistenceService` + `AnalysisRegistry` via `AnalysisStore`, so
interpreted output never leaks back into the collector.

---

## 5. The analysis pipeline (detail)

For each window the analyser runs:

1. **Dedup** the window's articles by title.
2. **Screener pass** (`ScreenerPass`, Claude **Haiku**) — score each article −10..+10 for *new,
   not-yet-priced-in* BTC impact. Articles scoring at/above `screenerThreshold` (default 6, by
   magnitude) are **resonant**. If none are resonant, record a screener-only result and stop.
3. **Read mechanical context** for the window (`WindowContext` + signal classes): macro regime,
   macro trend (rolling ~1h), options signal, per-article pre-trend/OHLC, and the price backdrop.
4. **Deep pass** (`DeepAnalysisPass`, Claude **Sonnet**) — one JSON object: aggregate `direction`,
   `impact_tier`, `key_events`, `reasoning`, plus a per-article direction/reasoning/scenario.
5. **Record** the analysis (`AnalysisStore`).
6. **Notify gate** (`NotifyGate`) — fire Telegram/email **iff** `direction != neutral` **and**
   `impact_tier >= notifyMinImpactTier` (default `high`) **and** a resonant article is fresher than
   `newsAgeToNotify` (default 1h).
7. **(Regular windows only)** run the standalone `MacroAlertChecker`.

### Mechanical signals reference (`com.finsent.analyse.signal`)
These are pure, model-free functions whose output is folded into the deep prompt **and** logged:

- **`MacroSignals.regime(...)`** → `risk_off | risk_on | mixed | neutral`, from how many of
  {VIX, DXY, S&P 500, gold, US 10y} breach and in which direction. Frames every catalyst.
- **`OptionsSignals.signal(...)`** → positioning `bullish | bearish | neutral` (+ IV-elevated,
  OI-surge, DVOL trend, put/call ratio), from the Deribit options snapshot.
- **`MacroTrend.of(...)`** → per-indicator direction/streak/cumulative-delta over the last ~1h, with
  a `sustained` flag (a trend ≥ 4 consecutive windows).
- **`PreTrend.of(bars)`** → `rising | falling | flat | volatile` from a regression over an article's
  pre-publication OHLC window.
- **Price backdrop** (collector-computed, stored in `PRICE_CONTEXT`) → current price, 1h/24h %
  change, 24h range position. **Used as backdrop only** (already-priced-in context), never as a
  causal attribution of a 24h move to a 10-minute news window.

### Output field meaning (`Analysed …` log line / analysis record)
- `direction`, `impact_tier` — **Claude's** judgment (deep pass).
- `macro_regime`, `options_signal` — **mechanical** signals (also shown to Claude → cross-check).
- `resonant_count / article_count` — the screener funnel.
- `btc_at_prediction` — price anchor at analysis time.

---

## 6. Workflows

### Regular collection cycle (`FSCollector.collect`, every 10 min)
ensure context (macro / options / OHLC strip / price-context, each stored once per window) → fetch
all configured sources → drop stale (`articleMaxAge`) and at/below-watermark articles → store +
commit atomically → publish `CollectionResult`.

### Urgent poll (`FSCollector.collectUrgent`, every ~30s)
fetch the urgent feeds → flag urgent-worthy articles (`UrgentKeywords`) → fetch per-article OHLC for
any urgent article in the current window → store + commit → publish **only** when an urgent-worthy
article landed in the window.

### Analysis (`FSAnalyser`, on each `CollectionResult`)
pause-gated; urgent events have a short cooldown; non-blocking enqueue to the `FS-Analyser` worker,
which runs the pipeline in §5.

### Backfill / re-analysis (commands)
- `anal window <YYYYMMDD_HHMM>` — re-analyse one window on demand (lazily recovers the day from disk
  if not resident); notifies.
- `anal windows -start <key> -end <key> [-missing|-force] [-run] [-notify]` — scan a range; default
  is a **dry run** listing candidates; `-missing` (default) = windows with news but no record,
  `-force` = every window with news; `-run` executes on the `FS-Backfill` thread; backfill does
  **not** notify unless `-notify` (so stale alerts aren't fired).
- `anal show <YYYYMMDD_HHMM>` — dump a stored analysis record.

### Runtime control
- `anal start` / `anal pause` / `anal status`. The analyser **starts paused** (launcher sets
  `-DpauseAnalyser=true`) so it never makes Claude calls or fires alerts until you start it.

---

## 7. Configuration

All runtime config lives in **`release/cfg/processes.xml`** (the bootstrap file), split by ownership
into `<FSCollector>` and `<FSAnalyser>` sections of the `<FSSatellite>` process node.
`release/cfg/system.xml` holds the log facility.

### Key collector params
`dataDir`, `analysisNewsWindow` (10m), `ohlcImpactWindow` (30m), `optionsEnabled`, `ohlcBarSize`
(1m), `articleMaxAge` (currently **72h**, temporarily widened to collect more; revert toward 12h for
live-signal freshness), `urgentPollInSec` (30), `recoveryLookbackInDays` (3), plus the source/feed
lists and the market-data base URLs. Sources: `rss` (regular + urgent feeds), `newsapi`, `polygon`,
`cryptopanic` (the keyed ones are skipped when no key is configured).

### Key analyser params
`claudeDeepAnalModel`, `claudeScreenerModel`, `anthropicMessagesUrl`, `screenerThreshold` (6),
`promptsDir` (prompts), `notifyMinImpactTier` (high), `newsAgeToNotify` (1h), Telegram + SMTP
delivery params, and `<MacroAlertThresholds>` (per-indicator % breach + `cooldownInMin`).

### Secrets (`com.finsent.core.Secrets`)
Config values of the form `ENV:VAR` (or `$VAR`) are resolved at read time: **a real environment
variable wins**, else a **`.env` file at the release home** (`release/.env`, gitignored), else the
empty string. Never commit secrets. Required for a full run: `ANTHROPIC_API_KEY` (+
`TELEGRAM_TOKEN` / `SMTP_PASSWORD` for alerts; `NEWSAPI_KEY` / `POLYGON_KEY` enable those sources).

### Prompts
`release/prompts/screener.txt` and `release/prompts/deep_analysis.txt` are the Claude prompt
templates (loaded by `PromptTemplates`, filled by `PromptBuilder`). Editing them changes analysis
behaviour with no recompile.

---

## 8. Observability

Every window reads as a consistent **inputs → judgment → alerts** trail in the log:

```
FSCollector:       Context  20260605 13:00 -- macro=yes options=yes OHLC=12bars BTC=$67432.10
FSCollector:       URGENT [AlJazeera] Israel strikes ...
FSAnalyser:        Analysed 20260605 13:00 -- direction=bullish impact=high regime=risk_off options=bearish resonant=3/12 BTC=$67432.10
MacroAlertChecker: Macro alert 20260605 13:00 -- direction=bearish impact=high regime=risk_off triggers=2 -- VIX +12% ...
```

- **Context** (info, once per window) — which context snapshots landed + BTC price; `MISSING` /
  `0bars` / `n/a` flag a degraded window.
- **URGENT** (info) — a flagged breaking headline (outlet + title).
- **Analysed** (info, when the analyser is running) — the per-window judgment.
- **Macro alert** (info) / suppressed-on-cooldown (debug) — the standalone macro path.

Logs are written to `release/logs/`, rolled daily: the unnamed process writes to
`FSSatellite.<yyyy-MM-dd>.log`, switching to a new dated file at local midnight so a
non-stop run yields one file per day.

---

## 9. Build, run, test

**JDK 17** is required (the code uses records). The toolchain JDK used here is
`C:\tools\java\jdk-17.0.15_6_temurin_x64`.

### Build (multi-module, offline)
```
$env:JAVA_HOME = "<jdk17>"
.\gradlew.bat :infra:compileTestJava :monitor:compileTestJava deployRelease --offline
```
`deployRelease` builds both jars and copies them + runtime deps into `release/common/lib`.

### Run
Launched by the Perl launcher `release/bin/FSSatellite.pl` (with `FinSent.pm`), which boots
`com.finsent.FSApp` with `-type FSSatellite -bootstrapDataFile cfg/processes.xml` and JVM flags
including `-Djava.net.preferIPv4Stack=true` and `-DpauseAnalyser=true`. Put secrets in `release/.env`
first. After startup, use the `anal` commands to drive the analyser.

### Test
The Gradle `test` task is **disabled**; run JUnit4 `*_utest` classes directly with the absolute JDK-17
java against the built classpath:
```
& "<jdk17>\bin\java" -cp "JavaClasses.sun\JavaClasses\infra;JavaClasses.sun\JavaClasses\monitor;JavaClasses.sun\JavaClasses\test\monitor;lib\*" org.junit.runner.JUnitCore <fqcn>
```

> **Outward-facing operations** (a live end-to-end run that makes real Claude calls and fires real
> Telegram/email) cost tokens and contact third parties. Run them **only with explicit human
> go-ahead**. The analyser starting paused is the guard.

---

## 10. Repository layout

```
src/finsent/infra/src/com/finsent/...     # application framework (GlobalSystem, cmd, log, mail, xml, app)
src/finsent/monitor/src/com/finsent/
  core/        # Config, Http, Json, Num, Times, Secrets; io/ (persistence, streams); registry/; event/
  collect/     # FSCollector, fetchers (Macro/Options/Ohlc), source/ (RSS/NewsAPI/Polygon/CryptoPanic),
               # CollectorRunner, UrgentPoller, UrgentKeywords, OhlcWindows
  analyse/     # FSAnalyser, claude/ (client, prompts, json), pass/ (screener, deep), signal/ (regime,
               # options, macro-trend, pre-trend, scenario), notify/ (gate, telegram, email, messages),
               # cmd/ (anal group), MacroAlert(+Checker), AnalysisStore, WindowContext, Intervals
  FSApp.java   # entry point
src/finsent/monitor/test/...              # JUnit4 *_utest suites
release/
  bin/         # FSSatellite.pl + FinSent.pm (launcher)
  cfg/         # processes.xml (config) + system.xml (log facility)
  prompts/     # screener.txt + deep_analysis.txt
  data/        # persisted per-day files (gitignored)
  logs/        # FSSatellite.<yyyy-MM-dd>.log (rolled daily)
  .env         # secrets fallback (gitignored)
```

---

## 11. For contributors & AI agents

- **`AGENTS.md`** — build/test/run commands, non-obvious invariants, and gotchas. Read it first.
- **`BACKLOG.md`** — outstanding work and design ideas (including the rolling-synthesis lane).
- **`.claude/rules/coding-guidelines.md`** — the coding standard for all source changes.
