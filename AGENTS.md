# AGENTS.md — working in the FinSent repo

Orientation for AI agents (and humans) making changes here. Read this first, then `README.md` for
the full architecture and financial rationale, and `BACKLOG.md` for open work. The coding standard
is `.claude/rules/coding-guidelines.md` — follow it.

## What this is (one paragraph)
FinSent is an event-driven backend that collects financial news + market data, runs a two-pass
Claude analysis (Haiku screener → Sonnet deep) per 10-minute window plus a ~30s urgent lane, and
fires Telegram/email alerts on high-impact, fresh, directional BTC signals. `FSCollector` collects
and never interprets; `FSAnalyser` subscribes via an in-process `EventBus` and does all
interpretation + notification. See `README.md`.

## Build / test / run

- **JDK 17 required** (records). Toolchain JDK here: `C:\tools\java\jdk-17.0.15_6_temurin_x64`.
- **Build (offline, multi-module):**
  ```
  $env:JAVA_HOME = "C:\tools\java\jdk-17.0.15_6_temurin_x64"
  .\gradlew.bat :infra:compileTestJava :monitor:compileTestJava deployRelease --offline
  ```
- **Tests:** the Gradle `test` task is **disabled**. Run JUnit4 `*_utest` classes directly with the
  **absolute** JDK-17 java (the PATH `javac`/`java` may be an older JDK that can't parse records):
  ```
  & "C:\tools\java\jdk-17.0.15_6_temurin_x64\bin\java" -cp "JavaClasses.sun\JavaClasses\infra;JavaClasses.sun\JavaClasses\monitor;JavaClasses.sun\JavaClasses\test\monitor;lib\*" org.junit.runner.JUnitCore <fqcn>
  ```
  Classes compile to `JavaClasses.sun/JavaClasses/{infra,monitor}`, tests to `.../test/monitor`.
- **Run:** launched by `release/bin/FSSatellite.pl`. The analyser **starts paused**; drive it with
  the `anal start|pause|status|window|windows|show` commands. Secrets go in `release/.env`.

## Environment gotchas (this workspace)
- **The Bash tool is broken here** (msys `fatal error: add_item ... failed`). Use **PowerShell** for
  shell commands, and the dedicated **Glob/Grep/Read/Edit** tools for files. (Bash occasionally works
  for simple `find`/`grep`; don't rely on it.)
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
- **Persistence is Unit-of-Work:** registries are pure in-memory, mutations return `WriteUnit`s,
  committed as one atomic batch. Don't make registries write to disk.
- **`-Djava.net.preferIPv4Stack=true` is required** in the launcher: IPv6 is unroutable on this host
  and the JDK HttpClient won't fall back, so without it cold connects time out.
- **`RssSource` first fetch is sequential, then parallel** (`warmed_` flag): the first concurrent
  burst of cold connections through the shared `HttpClient` storms its selector and mass-fails;
  the sequential warm-up prevents it. Don't "simplify" it back to always-parallel.

## Safety / outward-facing work
- **Never run a live end-to-end** (real Claude calls + real Telegram/email) **without explicit human
  go-ahead.** It spends Anthropic tokens and contacts third parties. The paused-at-start analyser is
  the guard; `anal start` is the trigger.
- **Secrets:** `release/.env` is the gitignored fallback (real env vars win). Never commit secrets;
  `git check-ignore release/.env` must pass.
- Config is `release/cfg/processes.xml`; prompts are `release/prompts/*.txt` — both take effect on
  next launch with **no recompile**. Source changes need build + restart.

## Where to change what
- New/changed **collection** behaviour → `com.finsent.collect.*` (+ a `DataStream`/registry if it's a
  new persisted snapshot — follow the macro/options/price-context pattern).
- New/changed **analysis** behaviour → `com.finsent.analyse.*`; prompt wording →
  `release/prompts/deep_analysis.txt` / `screener.txt`.
- New **config** param → `core/Config.java` getter + `release/cfg/processes.xml` attribute (use a
  measurement suffix, e.g. `...InSec` / `...InPct`).
- A new **command** → `com.finsent.analyse.cmd.AnalGroupCmdHandler` (built on the existing
  `CmdArgParser`).

## Conventions
Allman braces; `field_` trailing underscore; single structured return (avoid mid-method `return`
and `continue`); cyclomatic ≤ 5; no dead/speculative code; remove imports/vars that your change
orphans; keep edits minimal and localized. Full rules in `.claude/rules/coding-guidelines.md`.
