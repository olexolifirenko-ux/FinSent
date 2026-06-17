# AGENTS.md — working in the FinSent repo

Orientation for AI agents (and humans) making changes here. Read this first, then `README.md` for
the full architecture and financial rationale, and `BACKLOG.md` for open work. The coding standard
is `.claude/rules/coding-guidelines.md` — follow it.

## What this is (one paragraph)
FinSent is an event-driven backend that is, today, a **fast BTC news-event monitor**: it collects
financial news + market data, runs a two-pass Claude analysis (Haiku **screener** → Sonnet **deep**)
per 10-minute window plus a ~30s **urgent** lane, and fires Telegram/email alerts on **real, materially
crypto-impactful events** — filtering out bluff/speculation/already-priced noise. The deep pass is an
event detector (a fact-vs-posture / new-vs-priced / channel test), not a price forecaster; it emits a
materiality tier + a secondary directional *lean*. A long/short **trading module is deferred** (future
direction). `FSCollector` collects and never interprets; `FSAnalyser` subscribes via an in-process
`EventBus` and does all interpretation + notification. See `README.md`.

## Build / test / run

- **JDK 17 required** (records). Toolchain JDK here: `C:\tools\java\jdk-17.0.15_6_temurin_x64`.
- **Build (offline, multi-module) — run via PowerShell:**
  ```
  $env:JAVA_HOME = "C:\tools\java\jdk-17.0.15_6_temurin_x64"
  .\gradlew.bat :infra:compileTestJava :monitor:compileTestJava deployRelease --offline
  ```
- **Tests:** the Gradle `test` task is **disabled**. Run JUnit4 `*_utest` classes directly with the
  **absolute** JDK-17 java (the PATH `javac`/`java` may be an older JDK that can't parse records):
  ```
  & "C:\tools\java\jdk-17.0.15_6_temurin_x64\bin\java" -cp "JavaClasses.sun\JavaClasses\infra;JavaClasses.sun\JavaClasses\monitor;JavaClasses.sun\JavaClasses\test\monitor;lib\*" org.junit.runner.JUnitCore <fqcn>
  ```
  Classes compile to `JavaClasses.sun/JavaClasses/{infra,monitor}`, tests to `.../test/monitor`. The
  full suite is ~197 tests; pass several FQCNs to `JUnitCore`, or enumerate every `*_utest.class`.
- **Run:** launched by `release/bin/FSSatellite.pl`. The analyser and the X source both **start off**
  (see the `-D` flags below); drive them at runtime with the `anal` and `collect` command groups.
  Secrets go in `release/.env`.

### Launcher `-D` flags (both default OFF when absent; positively framed)
- **`-DrunAnalyser`** — `true` starts the analyser running; `false`/absent starts it **paused** (no
  Claude calls / alerts until `anal on`). Read by `FSApp` via `Boolean.getBoolean`.
- **`-DfetchX`** — `true` starts the X (Twitter) source polling; `false`/absent starts it **off**
  (no GetXAPI calls until `collect x on`). Needs `getxapiKey` + `<XAccounts>` to have any effect.

### Runtime commands
- **`anal on | off | status`** — turn analysis on/off (resume/pause), show state. `start`/`pause` are
  kept as aliases. Also: `anal window <YYYYMMDD_HHMM>`, `anal windows -start .. -end .. [-run] [-notify]`,
  `anal show <key>`, `anal econ [YYYYMMDD] <event> [-quiet]`, `anal feedback [--days N]`.
- **`collect x on | off | status`** — turn the X source's polling on/off at runtime (no restart).
  Also: `collect econ [YYYYMMDD] <event>` (fetch-only BLS catch-up).

## Environment gotchas (this workspace)
- **Run the Gradle build and JUnit runs via PowerShell.** The **Bash** tool works for `git` / `grep` /
  `find` / `curl` / `python` here; if it ever errors with `msys ... add_item ... failed`, fall back to
  PowerShell for that command. Prefer the dedicated **Glob/Grep/Read/Edit** tools for files.
- PowerShell: don't redirect native-exe stderr with `2>&1` (wraps as NativeCommandError noise); use
  `2>$null`. Quote `-D...` JVM args (`'-Djava.net.preferIPv4Stack=true'`) or PowerShell mangles them.

## Invariants — do not break these
- **`FSApp.main` parks the main thread** (`Thread.currentThread().join()`). All pipeline threads are
  daemon; remove the park and the JVM exits immediately after init. Keep it.
- **`ArticleRegistry` id counter must never regress** when an older day is lazily loaded for
  re-analysis (`ensureDayResident` ignores the loaded max-id). Test:
  `CoreRoundTrip_utest.ensureDayResidentLoadsForReadWithoutRegressingIds`.
- **Collector never interprets; analyser never collects.** Interpreted output lives in the
  analyser-owned `AnalysisStore`, not the collector registries.
- **Re-analysis must hydrate the analysis store first.** `reanalyse`/`reanalyseEcon` call
  `store_.recoverDay(day)` before `store_.record`, because a record rewrites the whole
  `analysis_<day>.json`; skipping it collapses an old day's file to the one re-analysed window
  (regression test: `AnalysisStore_utest.recordingOneWindowKeepsOtherWindowsOfAnUnrecoveredDay`).
- **Persistence is Unit-of-Work:** registries are pure in-memory, mutations return `WriteUnit`s,
  committed as one atomic batch. Don't make registries write to disk.
- **Claude response parsing reads the first `text` content block**, not `content[0]` — the deep pass
  runs with adaptive thinking, so a `thinking` block leads the array (`ClaudeClient.firstContentText`).
- **Both passes use structured outputs** (`output_config.format`, schemas in `ClaudeSchemas`); the
  deep pass also runs adaptive thinking; the screener runs `temperature:0`. The lenient `ClaudeJson`
  parser stays as a safety net. XML/JSON shapes were verified live before wiring.
- **`-Djava.net.preferIPv4Stack=true` is required** in the launcher: IPv6 is unroutable on this host
  and the JDK HttpClient won't fall back, so without it cold connects time out.
- **`RssSource` first fetch is sequential, then parallel** (`warmed_` flag): the first concurrent
  burst of cold connections through the shared `HttpClient` storms its selector and mass-fails;
  the sequential warm-up prevents it. Don't "simplify" it back to always-parallel.
- **XML comments must not contain `--`** (it's the comment delimiter) — `processes.xml` failed to
  parse once because a comment used `--` as an em-dash. Use `;` / `-` / `()` instead.

## Safety / outward-facing work
- **Never run a live end-to-end** (real Claude calls + real Telegram/email) **without explicit human
  go-ahead.** It spends Anthropic tokens and contacts third parties. The analyser starting off is the
  guard; `anal on` is the trigger. The X source (GetXAPI) is a **paid** API — `collect x on` / `-DfetchX`
  starts spending; keep it off unless intended.
- **Secrets:** `release/.env` is the gitignored fallback (real env vars win) — `ANTHROPIC_API_KEY`,
  `GETXAPI_KEY`, `BLS_API_KEY`, `TELEGRAM_TOKEN`, `SMTP_PASSWORD`. Never commit secrets;
  `git check-ignore release/.env` must pass.
- Config is `release/cfg/processes.xml`; prompts are `release/prompts/*.txt` — both take effect on
  next launch with **no recompile** (a re-analysis re-reads the prompt immediately). Source changes
  need build + restart.
- **`release/data/` is git-tracked** (per-day `data/<date>/` folders) so collected/analysed history is
  recoverable; `release/logs/` is ignored.

## Where to change what
- New/changed **collection** behaviour → `com.finsent.collect.*` (+ a `DataStream`/registry if it's a
  new persisted snapshot — follow the macro/options/funding/price-context/econ pattern). News sources
  live in `collect/source/` (`RssSource`, `XSquawkSource`, NewsAPI/Polygon/CryptoPanic).
- New/changed **analysis** behaviour → `com.finsent.analyse.*`; mechanical signals in
  `analyse/signal/` (`MacroSignals`, `OptionsSignals`, `FundingSignals` (funding+OI fusion),
  `MacroTrend`, `PreTrend`, `EconEventSignals`); prompt wording → `release/prompts/*.txt`
  (`screener.txt`, `deep_analysis.txt`, `econ_analysis.txt`, `macro_analysis.txt`); output schemas →
  `analyse/claude/ClaudeSchemas.java`.
- New **config** param → `core/Config.java` getter + `release/cfg/processes.xml` attribute (use a
  measurement suffix, e.g. `...InSec` / `...InPct`). A startup on/off knob → a `-D` flag read in `FSApp`.
- A new **command** → `com.finsent.analyse.cmd.AnalGroupCmdHandler` (analyser ops) or
  `com.finsent.collect.cmd.CollectGroupCmdHandler` (collector ops), both built on `CmdArgParser`.

## Conventions
Allman braces; `field_` trailing underscore; single structured return (avoid mid-method `return`
and `continue`); cyclomatic ≤ 5; no dead/speculative code; remove imports/vars that your change
orphans; keep edits minimal and localized. Full rules in `.claude/rules/coding-guidelines.md`.
