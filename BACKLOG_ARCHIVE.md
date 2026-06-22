# BACKLOG — Archive

Completed, reverted, and deliberately-rejected items, moved out of `BACKLOG.md` to keep the planning
doc lean. A running record of *what shipped* (and what was decided against) with dates. Open work —
including the remaining follow-ons of partly-done items — lives in `BACKLOG.md`.

---

## Shipped

### 2026-06-22 — Options priced-in reframe + macro-alert removal
- **Options signal reframed to a priced-in gauge.** `OptionsSignals` now emits a `priced_in` verdict
  (`complacent` / `normal` / `braced`) + `near_atm_iv`, derived from IV level + DVOL trend; the deep
  prompt renders an `options: <verdict> (IV.., trend)` line in place of the old direction-led
  `options_signal: …` line, with **asymmetric** guidance: options may only **scale** a catalyst that
  already passed all three steps, never discount one — `braced` means the market expects a big move
  (fragile), NOT that the news is already priced. The direction fields
  (`positioning`/`signal_strength`/…) are retained for the econ-alert mechanical view; only the deep
  render changed. Still off by default (`optionsEnabled=false`). **Open follow-on:** true IV **skew**
  (per-strike put/call mark-IV) needs an `OptionsFetcher` change — deferred.
- **Standalone macro-only alert path removed** (net −989 lines). Deleted `MacroAlert`(+`Checker`), the
  `macro_analysis` prompt, and the notify/format/schema-config/storage/feedback plumbing + `FSAnalyser`
  wiring. **Why:** it fired on macro tape moves BTC had already made — a *lagging confirmation* with no
  leading edge, brittle (unofficial Yahoo feed; market-hours vs BTC's 24/7 clock), and redundant with
  the news lanes (a real macro shock arrives as a *news* headline first). Macro *collection* and the
  `macro_regime`/`macro_trend` deep-prompt **context** are kept (genuine conditioning); the econ-alert
  path is untouched. **Lesson:** a signal built from the market's own realized reaction can confirm but
  never *lead* — keep those as context, never as the trigger.

### 2026-06-16/17 — Monitor reframe + analysis overhaul
- **Monitor reframe** — `deep_analysis.txt` is a 3-step fact-vs-posture / new-vs-priced / channel test
  (event detection, not forecasting); output = materiality tier + directional *lean*; notifications
  reframed to "CRYPTO EVENT"; `NotifyGate` fires on materiality (no non-neutral requirement).
- **Screener → direction-free 0–3 relevance ordinal** (was a signed −10..+10 score), threshold 2, recall
  lean, light-bluff rule (major-if-true kept for the deep pass), few-shot calibration. The signed scale
  was false precision — the keep/drop decision was always binary.
- **`confidence` removed** end-to-end (added 2026-06-09, removed 2026-06-17). Why: LLM-verbalised
  self-confidence is poorly calibrated, co-moved with `impact_tier`, duplicated Step 1 (fact-vs-posture),
  was a no-op in the gate, and was already hidden from alerts. Gone from prompts, `NotifyGate`,
  records/logs, `Config` (`notifyMinConfidence`), the feedback report, and `Confidence.java` (deleted).
  **Lesson:** keep any future self-rated scalar as a *measured* feedback variable until the data shows it
  separates winners — don't gate on it.
- **Structured outputs** (`output_config.format`, schemas in `ClaudeSchemas`) on both passes — the API
  guarantees schema-valid JSON, removing the malformed-response → noise path. Request shapes verified live.
- **Adaptive thinking on the deep pass** + `temperature:0` on both passes + parse the first `text` block
  (a thinking block leads the response).
- **Funding + Open Interest fused positioning signal (#25)** — OI fetched into the funding snapshot;
  `FundingSignals` fuses funding crowding × ~1h OI Δ × ~1h price into a cascade/squeeze `setup`
  (`down_cascade_fuel` / `up_squeeze_fuel` / `exhausting`); prompt line + guidance to scale how violently
  a confirmed catalyst lands. Binance endpoint shape verified live.
- **Fast X (Twitter) source** via GetXAPI (`XSquawkSource`): one merged `from:a OR from:b` query on the
  urgent lane, core + situational accounts (`<XAccounts>`/`<XSituationalAccounts>`), a 25-clause cap
  guard, runtime `collect x on|off` + `-DfetchX`. Finding: GetXAPI returns originals + quote-tweets but
  **never native retweets** (any endpoint) — follow principals directly to cover that.
- **Hollow-signal fix** — `macro_regime` is omitted (not a fabricated "neutral") when no macro snapshot
  was read (a `has_data` flag).
- **`anal start/pause` → `anal on/off`** (start/pause kept as aliases); **`-DpauseAnalyser` →
  `-DrunAnalyser`** (positive sense); both startup flags (`runAnalyser`, `fetchX`) default OFF when absent.
- **Data layout** reorganised into per-day `data/<date>/` folders and **git-tracked**.
- **Econ Stage 3 module (#21)** — static `cfg/econ_definitions.json` (series/kind/unit/hot_direction/bands)
  joined by name to per-date `data/<date>/econ_schedule_<date>.json` (release time + consensus);
  `EconScheduler` arms a timer at release time, polls the **BLS public API v2** until the fresh print
  lands, computes the surprise vs consensus, and runs an article-less deep pass → `econ_alert`. Manual
  `collect econ` (fetch-only) / `anal econ` catch-ups. Off by default (`econEnabled`). Covers CPI/NFP;
  BEA (GDP/PCE) not yet added.

### 2026-06-08/10 — Earlier
- **#2 Order deep-analysis articles by recency** [2026-06-08] — `PromptBuilder.deepArticles` sorts the
  resonant set most-recent-first; per-article results key by id, so the reorder is safe.
- **#16 Conditional GET (ETag / If-Modified-Since)** [2026-06-08] — `Http.getConditional` (304 =
  non-error, null body) + per-feed validator cache in `RssSource`; makes high-frequency polling cheap +
  polite (prerequisite for faster polling, #17).
- **#13 Live end-to-end run** [2026-06-08] — `anal window` drove a real Haiku+Sonnet pass →
  `analysis_*.json` → Telegram + email delivered; fixed a Telegram Markdown `_` 400 (now plain text).
- **#11 Blank-key startup validation** [2026-06-08] — one explicit WARN when `ANTHROPIC_API_KEY` resolves
  blank (was ~300 lines of 401 noise); `Secrets` stays empty-on-missing.
- **#6 Feedback / scoring loop — window-level v1** [2026-06-09] — `OutcomeScorer` / `FeedbackReport` /
  `FeedbackRunner`, exposed as `anal feedback [--days N]` + standalone `feedback_report.pl`. Article-level
  scenario validation too. *(Open follow-ons remain in `BACKLOG.md` #6.)*
- **#36 Cross-window screener dedup — live path** [2026-06-10] — `ALREADY COVERED (recent)` block sourced
  from stored records; follow-ups/recaps/reactions scored 0 with a materially-new exception. *(Open
  follow-ons in `BACKLOG.md` #36.)*
- **#18 Government / regulatory feeds — partial** [2026-06-08/10] — added SEC_Press, Fed (urgent),
  White House (urgent), ECB/BoJ/BoE. *(Remaining SEC-litigation/Treasury in `BACKLOG.md` #18.)*
- **#30 Post-publication price delta — alert side** [2026-06-09] — the alert shows
  `BTC: $X now | $Y at news (−0.8% since, HH:MM)`. *(Prompt-side open in `BACKLOG.md` #30.)*

---

## Decided / rejected

- **#7 Urgent → alert fast path (screener bypass)** — **REJECTED 2026-06-09.** Implemented then reverted.
  The urgent keyword filter is a *detection/latency* signal ("look now"), not a *relevance* judgment
  ("has a BTC transmission channel") — they diverge constantly (a keyword-urgent "NATO jets … drone"
  headline correctly screened 0). Bypassing routes noise into the expensive Sonnet pass and feeds
  tier-inflation/alert-fatigue; the only upside (rescuing screener false-negatives) is rare and
  unmeasurable without the feedback loop (#6). If ever revisited: a *threshold tweak* for urgent items,
  never a full bypass, and only once #6 can prove it pays.
- **#24 Funding-rate trend (rate delta)** — **not pursued 2026-06-17.** The build/unwind intent is
  delivered by the OI trend (#25); the funding-*rate* delta itself is redundant. Revisit only if it
  proves to add signal over OI.
- **ETF flows as a real-time monitor signal** — **assessed, not built.** Daily/lagged cadence +
  multi-day horizon mismatch a 10-min event monitor, and there's no clean free API; the news pipeline
  already catches flow *headlines*. A flow *series* belongs to the future trading / daily-synthesis layer
  — tracked as an open future item in `BACKLOG.md`.
