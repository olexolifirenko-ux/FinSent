package com.finsent.analyse;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.claude.ClaudeClient;
import com.finsent.analyse.claude.IClaudeClient;
import com.finsent.analyse.claude.PromptBuilder;
import com.finsent.analyse.claude.PromptTemplates;
import com.finsent.analyse.notify.EmailNotifier;
import com.finsent.analyse.notify.Notifier;
import com.finsent.analyse.notify.TelegramNotifier;
import com.finsent.analyse.pass.DeepAnalysisPass;
import com.finsent.analyse.pass.DeepResult;
import com.finsent.analyse.pass.ScreenerPass;
import com.finsent.analyse.pass.ScreenerResult;
import com.finsent.analyse.signal.PreTrend;
import com.finsent.analyse.signal.Scenario;
import com.finsent.collect.CollectionResult;
import com.finsent.collect.EconResolved;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.core.event.IEventListener;
import com.finsent.directory.DirectorySystem;
import com.finsent.feedback.FeedbackRunner;
import com.finsent.util.GlobalSystem;
import com.finsent.util.IMailSender;
import com.finsent.util.IUninitializer;
import com.finsent.util.MailSender;

/**
 * The analyser: it subscribes to the collector and, for each window, runs the two-pass Claude
 * pipeline (de-duplicate &rarr; screen &rarr; threshold &rarr; read mechanical context &rarr; deep
 * analysis &rarr; build record &rarr; persist &rarr; notify) and then asks the
 * {@link MacroAlertChecker} whether a standalone macro alert is due. It both <i>does</i> the
 * analysis and owns the runtime concerns &mdash; mirroring {@link FSCollector}, which fetches and
 * stores rather than delegating to a separate cycle object.
 *
 * <p>Because the collector's event-bus dispatch is single-threaded, {@link #onEvent} must never
 * block: it applies the pause gate and the urgent-poll cooldown, then hands the window to a
 * dedicated {@code FS-Analyser} worker thread that owns the slow Claude calls. The worker calls the
 * synchronous {@link #analyse(CollectionResult, Instant)} with {@code Instant.now()}; that method
 * (with {@code now} injected) is the unit-testable seam, just as {@code FSCollector.collect(now)} is
 * for collection. Analysis is paused/resumed at runtime via the {@code anal} command group and
 * seeded paused/running from the {@code -DpauseAnalyser} launcher property.
 *
 * <p>Like {@link FSCollector} (which builds its sources/fetchers from config), the production
 * constructor builds everything the analyser drives &mdash; its own {@link AnalysisStore}, the
 * Claude client/passes, the notification channels and the {@link MacroAlertChecker} &mdash; from
 * {@link Config}; the package-private injecting constructor takes the store, a {@link IClaudeClient}
 * and the {@link Notifier} directly for tests. It owns those resources, so {@link #uninitialize}
 * stops the worker and then shuts the notifier and store down.
 */
public final class FSAnalyser implements IEventListener<CollectionResult>, IUninitializer
{
    private static final String NAME = "FSAnalyser";
    private static final int DEEP_ANALYSIS_MAX = 100;
    private static final long SHUTDOWN_JOIN_MILLIS = 10_000;
    private static final int MAX_BACKFILL_SCAN = 100_000; // safety net against a runaway scan range

    private final FSCollector collector_;
    private final AnalysisStore store_;
    private final ScreenerPass screener_;
    private final DeepAnalysisPass deep_;
    private final Notifier notifier_;
    private final MacroAlertChecker macroAlertChecker_;
    private final Config config_;
    private final File promptsDir_;

    private final BlockingQueue<CollectionResult> queue_ = new LinkedBlockingQueue<>();
    // Last alerted (direction/impact_tier) per window, so re-analysing a developing window as new
    // articles arrive does not re-send an identical alert -- only an escalation/flip re-fires.
    private final ConcurrentHashMap<String, String> lastNotifiedByWindow_ = new ConcurrentHashMap<>();
    private final AtomicBoolean paused_;
    private final Thread worker_;
    // Econ analyses run off the event-bus thread on their own daemon executor (the Claude call is slow),
    // separate from the news worker so a rare scheduled-release analysis never queues behind window work.
    private final ExecutorService econDispatch_ = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "FS-Econ");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running_ = true;

    /** Production wiring: build the store, Claude client/passes, notifier and macro-alert check from config. */
    public FSAnalyser(FSCollector collector, Config config, boolean startPaused)
    {
        this(collector, buildStore(config), buildClaudeClient(config),
                buildNotifier(config), config, DirectorySystem.resolveToFile(config.promptsDir()), startPaused);
    }

    /** Injecting constructor: store, Claude client and notifier supplied directly (used by tests). */
    FSAnalyser(FSCollector collector, AnalysisStore store, IClaudeClient claudeClient, Notifier notifier,
               Config config, File promptsDir, boolean startPaused)
    {
        collector_ = collector;
        store_ = store;
        screener_ = new ScreenerPass(claudeClient, config.claudeScreenerModel(), config.screenerThreshold());
        deep_ = new DeepAnalysisPass(claudeClient, config.claudeDeepAnalModel());
        notifier_ = notifier;
        macroAlertChecker_ = new MacroAlertChecker(collector, store, notifier, config, deep_, promptsDir);
        config_ = config;
        promptsDir_ = promptsDir;
        paused_ = new AtomicBoolean(startPaused);
        worker_ = new Thread(this::drainLoop, "FS-Analyser");
        worker_.setDaemon(true);
        worker_.start();
        GlobalSystem.info().writes(NAME, "Analyser started (" + status() + ").");
    }

    private static AnalysisStore buildStore(Config config)
    {
        AnalysisStore store = new AnalysisStore(DirectorySystem.resolveToFile(config.dataDir()).toPath());
        store.recover(config.recoveryLookbackInDays());
        return store;
    }

    /**
     * Build the production Claude client, warning once at startup when the API key resolves blank --
     * otherwise the first call 401s with a flood of HTTP-error noise. {@code Secrets} stays
     * empty-on-missing; this is the single explicit heads-up that the key is unset.
     */
    private static ClaudeClient buildClaudeClient(Config config)
    {
        String apiKey = config.anthropicApiKey();
        if (apiKey.isEmpty())
        {
            GlobalSystem.warning().writes(NAME, "ANTHROPIC_API_KEY resolved blank -- Claude calls will fail "
                    + "(HTTP 401); set it as an environment variable or in release/.env.");
        }
        return new ClaudeClient(apiKey, config.anthropicMessagesUrl());
    }

    /** Build the notification coordinator from config; register the SMTP transport when configured. */
    private static Notifier buildNotifier(Config config)
    {
        IMailSender mailSender = buildMailSender(config);
        if (mailSender != null)
        {
            GlobalSystem.setMailSender(mailSender);
        }
        TelegramNotifier telegram = new TelegramNotifier(config.telegramToken(), config.telegramChatId(),
                config.telegramApiBaseUrl());
        EmailNotifier email = new EmailNotifier(mailSender, config.smtpUser(), config.emailTo());
        return new Notifier(telegram, email, config.notifyMinImpactTier(), config.notifyMinConfidence(),
                config.newsAgeToNotifyMinutes());
    }

    /** A jakarta {@link MailSender} when an SMTP host is configured, else null (email disabled). */
    private static IMailSender buildMailSender(Config config)
    {
        IMailSender mailSender = null;
        if (!config.smtpHost().isEmpty())
        {
            mailSender = new MailSender("FinSent", config.smtpHost(), config.smtpPort(),
                    config.smtpUser(), config.smtpPassword(), 0);
        }
        return mailSender;
    }

    // == Event intake + worker (runtime concerns) ==============================

    @Override
    public void onEvent(CollectionResult result)
    {
        if (paused_.get())
        {
            GlobalSystem.debug().writes(NAME, "Paused -- skipping " + result.day() + " " + result.intervalKey());
        }
        else
        {
            queue_.offer(result); // non-blocking: never stall the single-threaded event bus
        }
    }

    /**
     * Handle a freshly-resolved scheduled release (#21): unless paused, run the econ analysis off the
     * event-bus thread (the Claude call is slow) on the dedicated econ executor, mirroring how
     * {@link #onEvent} hands news windows to the worker. Registered by {@code FSApp} via
     * {@code collector.addEconListener}.
     */
    public void onEconResolved(EconResolved resolved)
    {
        if (paused_.get())
        {
            GlobalSystem.debug().writes(NAME, "Paused -- skipping econ " + resolved.eventName());
        }
        else
        {
            econDispatch_.submit(() -> processEcon(resolved));
        }
    }

    private void processEcon(EconResolved resolved)
    {
        try
        {
            analyseEcon(resolved.day(), resolved.intervalKey(), resolved.eventName(), Instant.now(), true);
        }
        catch (Exception econFailed)
        {
            // Abort just this event; the econ executor stays alive for the next release.
            GlobalSystem.error().writes(NAME, "Econ analysis failed for " + resolved.eventName()
                    + " -- aborted, analyser continues", econFailed);
        }
    }

    /** Resume analysis (the {@code anal start} command). */
    public void resume()
    {
        if (paused_.compareAndSet(true, false))
        {
            GlobalSystem.info().writes(NAME, "Analysis started.");
        }
    }

    /** Pause analysis (the {@code anal pause} command); windows already queued still run. */
    public void pause()
    {
        if (paused_.compareAndSet(false, true))
        {
            GlobalSystem.info().writes(NAME, "Analysis paused.");
        }
    }

    public boolean isPaused()
    {
        return paused_.get();
    }

    /** One-word state for the {@code anal status} command. */
    public String status()
    {
        return paused_.get() ? "paused" : "running";
    }

    public int queueDepth()
    {
        return queue_.size();
    }

    @Override
    public void uninitialize()
    {
        running_ = false;
        worker_.interrupt();
        joinWorker(); // let an in-flight window finish writing before the store closes
        econDispatch_.shutdown();
        notifier_.shutdown();
        store_.shutdown();
    }

    private void joinWorker()
    {
        try
        {
            worker_.join(SHUTDOWN_JOIN_MILLIS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void drainLoop()
    {
        while (running_)
        {
            CollectionResult result = take();
            if (result != null)
            {
                process(result);
            }
        }
    }

    private CollectionResult take()
    {
        CollectionResult result = null;
        try
        {
            result = queue_.take();
        }
        catch (InterruptedException shuttingDown)
        {
            Thread.currentThread().interrupt();
            running_ = false;
        }
        return result;
    }

    private void process(CollectionResult result)
    {
        try
        {
            analyse(result, Instant.now());
        }
        catch (Exception analysisFailed)
        {
            // Abort this window's cycle only; the worker thread stays alive for the next event.
            GlobalSystem.error().writes(NAME, "Analysis failed for " + result.day() + " "
                    + result.intervalKey() + " -- aborted, worker continues", analysisFailed);
        }
    }

    // == Analysis (the per-window pipeline) ====================================

    /**
     * Analyse the window carried by {@code result} (live path): run the window, then &mdash; for a
     * regular boundary cycle only &mdash; the macro-alert check. The macro tape refreshes at window
     * cadence (the snapshot is stored once per window), so re-checking it on every urgent poll would
     * be redundant; macro alert is a per-window signal, news urgency is a separate clock (this also
     * matches Python, which runs the macro check only in the scheduled analysis loop). {@code now}
     * stamps the record and gates notifications. The synchronous, deterministic entry point the
     * worker drives and tests call.
     */
    void analyse(CollectionResult result, Instant now) throws IOException {
        runWindow(result, now, false, true);
        if (!result.urgent() && isCurrentWindow(result, now))
        {
            macroAlertChecker_.check(result.day(), result.intervalKey(), now);
        }
    }

    /**
     * True when {@code result} is the window containing {@code now} (the regular boundary heartbeat),
     * not a re-analysis of a past window. The macro-alert check fires only there &mdash; running it on
     * a late-article re-analysis of an old window could otherwise emit a stale macro alert.
     */
    private boolean isCurrentWindow(CollectionResult result, Instant now)
    {
        return result.intervalKey().equals(Times.intervalKey(now, config_.windowMinutes()))
                && result.day().equals(Times.dayOf(Times.formatUtcIso(now)));
    }

    /**
     * Manually re-analyse a stored window on demand (the {@code anal window} command); notifies
     * (subject to the gate, with the age check bypassed). Runs on the caller's thread.
     */
    public String reanalyse(String day, String key) throws IOException {
        return reanalyse(day, key, true);
    }

    /**
     * Run the BL#6 feedback loop on the command thread: score the stored predictions against realized
     * BTC moves (one Binance kline per matured horizon, via the collector), persist outcomes.jsonl, and
     * return the accuracy report. Read-only and keyless &mdash; no Claude, no alerts.
     */
    public String runFeedback(int days)
    {
        File dataDir = DirectorySystem.resolveToFile(config_.dataDir());
        return FeedbackRunner.run(dataDir, Instant.now(), collector_::fetchClosePriceAt, days);
    }

    /**
     * Re-analyse one stored window. Lazily recovers the day's data from disk if it is no longer
     * resident, optionally notifies ({@code notify}; the news-age check is bypassed since the window
     * may be old), and &mdash; unlike the live path &mdash; does not run the macro-alert check (a
     * live-loop concern). Returns a one-line summary. Backfill passes {@code notify=false} so it
     * records history without firing stale alerts.
     */
    String reanalyse(String day, String key, boolean notify) throws IOException
    {
        collector_.recoverDay(day);
        List<ObjectNode> window = collector_.articles().forInterval(day, key, false, config_.windowMinutes());
        runWindow(new CollectionResult(day, key, window.size(), window, false), Instant.now(), true, notify);
        return summary(day, key, window.size());
    }

    private void runWindow(CollectionResult result, Instant now, boolean skipAgeCheck, boolean notify) throws IOException
    {
        analyseWindow(result.day(), result.intervalKey(), now, skipAgeCheck, notify, dedupByTitle(result.windowArticles()));
    }

    /**
     * Analyse a resolved scheduled data release (#21, Stage 3) at {@code (day, key)}: derive the
     * mechanical surprise signal; when it clears the in-line band, run the article-less deep pass with
     * the window's market context and record the call under the interval's {@code econ_alert}, notifying
     * via the news-free path when it clears the gate. An in-line print (or a failed Claude call) records
     * the mechanical read only and does not alert. {@code now} stamps the record. The synchronous,
     * deterministic seam the (future) scheduler and tests drive, mirroring {@link #analyse}.
     */
    void analyseEcon(String day, String key, String eventName, Instant now, boolean notify) throws IOException
    {
        ObjectNode signal = WindowContext.econSignal(collector_, day, eventName);
        if (signal != null)
        {
            WindowContext.MarketContext market = WindowContext.marketContext(collector_, day, key, config_.windowMinutes());
            boolean inline = "neutral".equals(signal.path("direction").asText("neutral"));
            ObjectNode deepPrediction = inline ? null : runEconDeep(signal, market.block());
            ObjectNode econAlert = buildEconAlert(Times.formatUtcIso(now), signal, deepPrediction, market.regime().path("regime").asText());
            store_.recordEconAlert(day, key, econAlert);
            logEcon(day, key, econAlert);
            if (notify && deepPrediction != null)
            {
                notifier_.notifyEconAlert(econAlert, key);
            }
        }
    }

    /** Run the article-less deep pass for an econ surprise against the window's market block; null if it fails. */
    private ObjectNode runEconDeep(ObjectNode signal, String marketBlock) throws IOException
    {
        String prompt = PromptTemplates.fillContext(loadTemplate("econ_analysis"), PromptBuilder.econEvent(signal), marketBlock);
        return deep_.analyse(prompt).prediction();
    }

    /**
     * The stored econ-alert record: the mechanical surprise inputs (event/consensus/actual/surprise/
     * label) and prior (mechanical_direction/tier), then the final call (direction/impact_tier/
     * confidence/key_events/reasoning) from Claude -- falling back to the mechanical read when the deep
     * call was skipped (in-line) or failed. {@code macro_regime} is computed mechanically, as elsewhere.
     */
    private static ObjectNode buildEconAlert(String analyzedAt, ObjectNode signal, ObjectNode deepPrediction, String macroRegime)
    {
        boolean claudeAvailable = deepPrediction != null;
        ObjectNode alert = Json.newObject();
        alert.put("analyzed_at", analyzedAt);
        alert.put("event", signal.path("event").asText(""));
        alert.put("label", signal.path("label").asText(""));
        alert.set("consensus", signal.get("consensus"));
        alert.set("actual", signal.get("actual"));
        alert.set("surprise", signal.get("surprise"));
        alert.put("mechanical_direction", signal.path("direction").asText("neutral"));
        alert.put("mechanical_tier", signal.path("impact_tier").asText("noise"));
        alert.put("claude_available", claudeAvailable);
        alert.put("direction", claudeAvailable ? deepPrediction.path("direction").asText("neutral")
                : signal.path("direction").asText("neutral"));
        alert.put("impact_tier", claudeAvailable ? deepPrediction.path("impact_tier").asText("noise")
                : signal.path("impact_tier").asText("noise"));
        alert.put("confidence", claudeAvailable ? deepPrediction.path("confidence").asText("low") : "low");
        alert.put("macro_regime", macroRegime);
        alert.set("key_events", claudeAvailable ? keyEvents(deepPrediction) : Json.newArray());
        alert.put("reasoning", claudeAvailable ? deepPrediction.path("reasoning").asText("")
                : "Mechanical surprise read (no deep analysis).");
        return alert;
    }

    private void logEcon(String day, String key, ObjectNode econAlert)
    {
        GlobalSystem.info().writes(NAME, "Econ " + day + " " + key + " -- " + econAlert.path("label").asText("?")
                + " | call=" + econAlert.path("direction").asText("?") + "/" + econAlert.path("impact_tier").asText("?")
                + " conf=" + econAlert.path("confidence").asText("?")
                + " regime=" + econAlert.path("macro_regime").asText("?")
                + (econAlert.path("claude_available").asBoolean() ? "" : " (mechanical-only)"));
    }

    private String summary(String day, String key, int windowSize)
    {
        ObjectNode interval = store_.get(day, key);
        JsonNode prediction = interval.path("prediction_record");
        String summary;
        if (prediction.isMissingNode() || prediction.isNull())
        {
            summary = windowSize + " article(s) in window, none resonant (screener-only).";
        }
        else
        {
            summary = prediction.path("direction").asText() + " / " + prediction.path("impact_tier").asText()
                    + ", " + interval.path("resonant_article_ids").size() + " resonant.";
        }
        return summary;
    }

    /**
     * Scan windows in {@code [start, end]} (inclusive, aligned to the window grid) and return those
     * that need analysis: with {@code force}, every window that has news; otherwise only windows that
     * have news but no stored analysis record ("missing"). Lazily recovers each day's collected data
     * and analysis records so the checks are accurate for older days. Read-only &mdash; no analysis
     * is run (this backs the dry-run of {@code anal windows}).
     */
    public BackfillPlan planBackfill(String startDay, String startKey, String endDay, String endKey, boolean force)
    {
        int windowMinutes = config_.windowMinutes();
        List<Intervals.DayKey> windows = new ArrayList<>();
        Intervals.DayKey cur = new Intervals.DayKey(startDay, Intervals.floorToWindow(startKey, windowMinutes));
        int scanned = 0;
        boolean truncated = false;
        while (Intervals.notAfter(cur.day(), cur.key(), endDay, endKey))
        {
            if (scanned++ >= MAX_BACKFILL_SCAN)
            {
                truncated = true;
                break;
            }
            if (needsAnalysis(cur.day(), cur.key(), windowMinutes, force))
            {
                windows.add(cur);
            }
            cur = Intervals.advance(cur.day(), cur.key(), windowMinutes);
        }
        return new BackfillPlan(windows, truncated);
    }

    private boolean needsAnalysis(String day, String key, int windowMinutes, boolean force)
    {
        collector_.recoverDay(day);
        store_.recoverDay(day);
        boolean hasNews = !collector_.articles().forInterval(day, key, false, windowMinutes).isEmpty();
        boolean analysed = !store_.get(day, key).isEmpty();
        return hasNews && (force || !analysed);
    }

    /**
     * Re-analyse the planned {@code windows} on a dedicated daemon thread (so neither the command
     * interpreter nor the live worker is blocked), one window at a time, logging progress.
     * {@code notify} controls whether stale alerts are fired (off for a record-only backfill).
     */
    public void runBackfill(List<Intervals.DayKey> windows, boolean notify)
    {
        Thread thread = new Thread(() -> backfillLoop(windows, notify), "FS-Backfill");
        thread.setDaemon(true);
        thread.start();
    }

    private void backfillLoop(List<Intervals.DayKey> windows, boolean notify)
    {
        int done = 0;
        for (Intervals.DayKey window : windows)
        {
            try
            {
                String summary = reanalyse(window.day(), window.key(), notify);
                done++;
                GlobalSystem.info().writes(NAME, "Backfill " + done + "/" + windows.size() + " "
                        + window.day() + " " + window.key() + ": " + summary);
            }
            catch (Exception windowFailed)
            {
                // Skip this window; the backfill thread continues with the rest of the range.
                GlobalSystem.error().writes(NAME, "Backfill failed for " + window.day() + " " + window.key()
                        + " -- skipped, backfill breaks", windowFailed);
                break;
            }
        }
        GlobalSystem.info().writes(NAME, "Backfill complete: " + done + "/" + windows.size() + " window(s).");
    }

    /** A read-only multi-line dump of the stored analysis record for a window (the {@code anal show} command). */
    public String describe(String day, String key)
    {
        store_.recoverDay(day);
        ObjectNode interval = store_.get(day, key);
        return interval.isEmpty() ? "No analysis record for " + day + " " + key + "."
                : describeInterval(day, key, interval);
    }

    private static String describeInterval(String day, String key, ObjectNode interval)
    {
        StringBuilder out = new StringBuilder();
        out.append(day).append(' ').append(key).append("  analyzed_at=").append(interval.path("analyzed_at").asText());
        out.append("\n  articles=").append(interval.path("article_ids").size())
                .append(", resonant=").append(interval.path("resonant_article_ids").size());
        JsonNode prediction = interval.path("prediction_record");
        if (prediction.isObject())
        {
            out.append("\n  prediction: ").append(prediction.path("direction").asText()).append(" / ")
                    .append(prediction.path("impact_tier").asText()).append("  macro=")
                    .append(prediction.path("macro_regime").asText());
            out.append("\n  reasoning: ").append(prediction.path("reasoning").asText());
        }
        else
        {
            out.append("\n  prediction: (screener-only)");
        }
        JsonNode macroAlert = interval.path("macro_alert");
        if (macroAlert.isObject())
        {
            out.append("\n  macro_alert: ").append(macroAlert.path("direction").asText()).append(" / ")
                    .append(macroAlert.path("impact_tier").asText()).append(" -- ")
                    .append(macroAlert.path("reasoning").asText());
        }
        return out.toString();
    }

    private void analyseWindow(String day, String key, Instant now, boolean skipAgeCheck, boolean notify,
                               List<ObjectNode> unique) throws IOException
    {
        if (!unique.isEmpty())
        {
            // screen() throws IllegalStateException if it cannot score the window; runWindow's catch
            // aborts the cycle, so the deep pass never runs on fabricated scores. The screener is fed the
            // recent windows' resonant stories (from the stored records) so it dedups follow-ups/recaps of
            // an already-covered event across windows -- on every path, live or re-analysis/backfill.
            String coveredBlock = PromptBuilder.coveredBlock(recentlyCovered(day, key));
            ScreenerResult screened = screener_.screen(unique, coveredBlock, loadTemplate("screener"));
            List<ObjectNode> resonant = capResonant(screened.resonant());
            String analyzedAt = Times.formatUtcIso(now);
            if (resonant.isEmpty())
            {
                store_.record(day, key, stubInterval(analyzedAt, unique, screened.screenerOut()));
                GlobalSystem.info().writes(NAME, "Analysed " + day + " " + key
                        + " -- screener-only, 0/" + unique.size() + " resonant (no deep analysis)");
            }
            else
            {
                runDeepAnalysis(day, key, now, skipAgeCheck, notify, analyzedAt, unique, resonant, screened.screenerOut());
            }
        }
    }

    /**
     * Recently-covered resonant articles for the screener's cross-window dedup: the resonant set (title +
     * publish time) recorded in the windows within {@code screenerDedupLookback} before {@code (day, key)},
     * read from the stored analysis records. Record-sourced (not a volatile in-memory ring), so it survives
     * a restart and applies to live, on-demand re-analysis and backfill alike &mdash; keyed by the window's
     * own time, not wall-clock. The current window is excluded (its own duplicates are caught in-batch).
     */
    private List<ObjectNode> recentlyCovered(String day, String key)
    {
        int windowMinutes = config_.windowMinutes();
        int lookbackWindows = Math.max(0, config_.screenerDedupLookbackMinutes() / windowMinutes);
        List<ObjectNode> covered = new ArrayList<>();
        for (int back = 1; back <= lookbackWindows; back++)
        {
            Intervals.Shift prev = Intervals.back(key, back, windowMinutes);
            String prevDay = prev.dayOffset() == 0 ? day : Intervals.minusDays(day, prev.dayOffset());
            for (JsonNode article : store_.get(prevDay, prev.key()).path("prediction_record").path("articles"))
            {
                ObjectNode note = Json.newObject();
                note.put("publishedAt", article.path("published_at").asText(""));
                note.put("title", article.path("title").asText(""));
                covered.add(note);
            }
        }
        return covered;
    }

    private void runDeepAnalysis(String day, String key, Instant now, boolean skipAgeCheck, boolean notify,
                                 String analyzedAt, List<ObjectNode> unique, List<ObjectNode> resonant,
                                 ArrayNode screenerOut) throws IOException
    {
        WindowContext.MarketContext market = WindowContext.marketContext(collector_, day, key, config_.windowMinutes());
        Map<Integer, ArrayNode> ohlc = WindowContext.articleOhlc(collector_, resonant, config_.ohlcImpactWindowMinutes());

        Map<Integer, Integer> deepIdMap = new HashMap<>();
        String articlesBlock = PromptBuilder.deepArticles(resonant, ohlc, deepIdMap);
        String prompt = PromptTemplates.fillDeep(loadTemplate("deep_analysis"), resonant.size(), market.block(), articlesBlock);

        DeepResult deep = deep_.analyse(prompt);
        ArrayNode articlePredictions = buildArticlePredictions(resonant, ohlc, deep.articles(), deepIdMap);
        ObjectNode prediction = buildPredictionRecord(WindowContext.btcPrice(ohlc), unique.size(), resonant.size(),
                deep.prediction(), market.regime().path("regime").asText(), market.options(), market.funding(),
                market.priceContext(), articlePredictions);

        store_.record(day, key, interval(analyzedAt, config_.claudeDeepAnalModel(), unique, screenerOut, prediction, resonant));
        logAnalysis(day, key, prediction);
        logResonant(resonant);
        if (notify)
        {
            maybeNotifyOnChange(day, key, prediction, asList(articlePredictions), resonant, now, skipAgeCheck);
        }
    }

    /**
     * Print the resonant articles that drove this window's deep analysis &mdash; the screener-selected,
     * market-relevant subset &mdash; each with its {@code publishedAt} and description. Replaces the old
     * collection-stage per-keyword dump, which fired on every {@code urgent_worthy} match (mostly noise)
     * before any relevance was established.
     */
    private void logResonant(List<ObjectNode> resonant)
    {
        for (ObjectNode article : resonant)
        {
            String text = article.path("description").asText("");
            if (text.isEmpty())
            {
                text = article.path("title").asText("");
            }
            GlobalSystem.info().writes(NAME, "  resonant [" + outlet(article) + "] "
                    + article.path("publishedAt").asText("") + " -- " + text);
        }
    }

    /** The article's outlet name (e.g. AlJazeera / TruthSocial_Trump), falling back to the source tag. */
    private static String outlet(ObjectNode article)
    {
        String name = article.path("source").path("name").asText("");
        return name.isEmpty() ? article.path("_source").asText("?") : name;
    }

    /**
     * Fire the window's alert only when its {@code (direction, impact_tier)} differs from what was last
     * alerted for that window &mdash; so re-analysing a developing window as new articles arrive does
     * not re-send an identical alert, but a genuine escalation (e.g. low &rarr; high) or a direction
     * flip still fires. The gate (freshness + tier) still applies inside {@code maybeNotify}; only a
     * call it actually delivered updates the per-window record.
     */
    private void maybeNotifyOnChange(String day, String key, ObjectNode prediction, List<ObjectNode> articlePreds,
                                     List<ObjectNode> resonant, Instant now, boolean skipAgeCheck)
    {
        String windowId = day + " " + key;
        String call = prediction.path("direction").asText("?") + "/" + prediction.path("impact_tier").asText("?");
        // Live regular/urgent path only (skipAgeCheck == false): attach the realtime BTC price so the
        // alert shows the move since the catalyst. Manual re-analysis of an old window has no useful "now".
        Supplier<Double> realtimePrice = skipAgeCheck ? null : () -> collector_.fetchClosePriceAt(Instant.now());
        if (!call.equals(lastNotifiedByWindow_.get(windowId))
                && notifier_.maybeNotify(prediction, articlePreds, resonant, key, now, skipAgeCheck, realtimePrice))
        {
            lastNotifiedByWindow_.put(windowId, call);
        }
    }

    /**
     * One info line with the window's interpretation &mdash; the analyser's judgment (direction,
     * impact tier, macro regime, options positioning, resonant ratio, BTC at prediction). Complements
     * the collector's per-window input line so the log shows both what went in and what came out.
     */
    private void logAnalysis(String day, String key, ObjectNode prediction)
    {
        String options = prediction.path("options_signal").path("positioning").asText("");
        GlobalSystem.info().writes(NAME, "Analysed " + day + " " + key
                + " -- direction=" + prediction.path("direction").asText("?")
                + " impact=" + prediction.path("impact_tier").asText("?")
                + " conf=" + prediction.path("confidence").asText("?")
                + " regime=" + prediction.path("macro_regime").asText("?")
                + " options=" + (options.isEmpty() ? "n/a" : options)
                + " resonant=" + prediction.path("resonant_count").asInt()
                + "/" + prediction.path("article_count").asInt()
                + " BTC=" + btcText(prediction.path("btc_at_prediction")));
    }

    private static String btcText(JsonNode btc)
    {
        return btc.isNumber() ? String.format(Locale.ROOT, "$%.2f", btc.asDouble()) : "n/a";
    }

    private ObjectNode buildPredictionRecord(Double btcPrice, int articleCount, int resonantCount,
                                             ObjectNode deepPrediction, String macroRegime, ObjectNode optionsSignal,
                                             ObjectNode fundingSignal, ObjectNode priceContext, ArrayNode articlePredictions)
    {
        boolean claudeAvailable = deepPrediction != null;
        ObjectNode pred = Json.newObject();
        if (btcPrice == null)
        {
            pred.putNull("btc_at_prediction");
        }
        else
        {
            pred.put("btc_at_prediction", btcPrice.doubleValue());
        }
        pred.put("article_count", articleCount);
        pred.put("resonant_count", resonantCount);
        pred.put("claude_available", claudeAvailable);
        pred.put("direction", claudeAvailable ? deepPrediction.path("direction").asText("neutral") : "neutral");
        pred.put("impact_tier", claudeAvailable ? deepPrediction.path("impact_tier").asText("noise") : "noise");
        pred.put("confidence", claudeAvailable ? deepPrediction.path("confidence").asText("low") : "low");
        pred.put("macro_regime", macroRegime);
        pred.set("key_events", claudeAvailable ? keyEvents(deepPrediction) : Json.newArray());
        pred.put("reasoning", claudeAvailable ? deepPrediction.path("reasoning").asText("")
                : "Screener-only: no deep analysis available");
        pred.set("options_signal", optionsSignal == null ? Json.newObject() : optionsSignal);
        pred.set("funding_signal", fundingSignal == null ? Json.newObject() : fundingSignal);
        pred.set("price_context", priceContext == null ? Json.newObject() : priceContext);
        pred.set("articles", articlePredictions);
        return pred;
    }

    private static ArrayNode buildArticlePredictions(List<ObjectNode> resonant, Map<Integer, ArrayNode> ohlc,
                                                     ArrayNode perArticle, Map<Integer, Integer> deepIdMap)
    {
        Map<Integer, ObjectNode> byArticleId = indexPerArticle(perArticle, deepIdMap);
        ArrayNode predictions = Json.newArray();
        for (ObjectNode article : resonant)
        {
            int articleId = article.path("id").asInt();
            String preTrendLabel = preTrendLabel(ohlc.get(articleId));
            ObjectNode artPrediction = byArticleId.get(articleId);
            String direction = artPrediction == null ? null : optText(artPrediction, "direction");

            ObjectNode record = Json.newObject();
            record.put("id", articleId);
            record.put("title", article.path("title").asText(""));
            if (direction != null)
            {
                record.put("direction", direction);
            }
            record.put("reasoning", artPrediction == null ? "" : artPrediction.path("reasoning").asText(""));
            record.put("scenario", Scenario.of(preTrendLabel, direction));
            // Article-level feedback inputs (BL#6): publish time, BTC price at publication (newest
            // pre-pub OHLC bar), and the pre-trend label the scenario was derived from.
            record.put("pre_trend", preTrendLabel == null ? "" : preTrendLabel);
            record.put("published_at", article.path("publishedAt").asText(""));
            record.put("url", article.path("url").asText(""));
            putPriceAtPublish(record, ohlc.get(articleId));
            predictions.add(record);
        }
        return predictions;
    }

    /** BTC close at the article's publication (newest bar of its pre-pub OHLC window), or null. */
    private static void putPriceAtPublish(ObjectNode record, ArrayNode bars)
    {
        Double price = newestClose(bars);
        if (price == null)
        {
            record.putNull("price_at_publish");
        }
        else
        {
            record.put("price_at_publish", price.doubleValue());
        }
    }

    private static Double newestClose(ArrayNode bars)
    {
        Double close = null;
        String latestTs = null;
        if (bars != null)
        {
            for (JsonNode bar : bars)
            {
                String ts = bar.path("ts").asText("");
                if (latestTs == null || ts.compareTo(latestTs) > 0)
                {
                    latestTs = ts;
                    close = bar.path("c").asDouble();
                }
            }
        }
        return close;
    }

    private static Map<Integer, ObjectNode> indexPerArticle(ArrayNode perArticle, Map<Integer, Integer> deepIdMap)
    {
        Map<Integer, ObjectNode> byArticleId = new HashMap<>();
        for (JsonNode entry : perArticle)
        {
            Integer promptIndex = coerceInt(entry.path("i"));
            if (promptIndex != null && deepIdMap.containsKey(promptIndex) && entry instanceof ObjectNode)
            {
                byArticleId.put(deepIdMap.get(promptIndex), (ObjectNode) entry);
            }
        }
        return byArticleId;
    }

    private ObjectNode interval(String analyzedAt, String model, List<ObjectNode> unique, ArrayNode screenerOut,
                                ObjectNode prediction, List<ObjectNode> resonant)
    {
        ObjectNode interval = Json.newObject();
        interval.put("analyzed_at", analyzedAt);
        interval.put("model", model);
        interval.set("article_ids", ids(unique));
        interval.set("screener", screenerOut);
        interval.set("prediction_record", prediction);
        interval.set("resonant_article_ids", ids(resonant));
        return interval;
    }

    private ObjectNode stubInterval(String analyzedAt, List<ObjectNode> unique, ArrayNode screenerOut)
    {
        ObjectNode interval = Json.newObject();
        interval.put("analyzed_at", analyzedAt);
        interval.put("model", config_.claudeScreenerModel());
        interval.set("article_ids", ids(unique));
        interval.set("screener", screenerOut);
        interval.putNull("prediction_record");
        return interval;
    }

    private String loadTemplate(String name) throws IOException
    {
        return PromptTemplates.load(promptsDir_, name);
    }

    private static List<ObjectNode> dedupByTitle(List<ObjectNode> articles)
    {
        Set<String> seenTitles = new HashSet<>();
        List<ObjectNode> unique = new ArrayList<>();
        for (ObjectNode article : articles)
        {
            String titleKey = article.path("title").asText("").trim().toLowerCase(Locale.ROOT);
            if (seenTitles.add(titleKey))
            {
                unique.add(article);
            }
        }
        return unique;
    }

    private static List<ObjectNode> capResonant(List<ObjectNode> resonant)
    {
        List<ObjectNode> capped = resonant;
        if (resonant.size() > DEEP_ANALYSIS_MAX)
        {
            GlobalSystem.warning().writes(NAME, resonant.size() + " resonant exceeds cap " + DEEP_ANALYSIS_MAX
                    + " -- keeping top " + DEEP_ANALYSIS_MAX + " by |score|.");
            List<ObjectNode> sorted = new ArrayList<>(resonant);
            sorted.sort((a, b) -> Integer.compare(absScore(b), absScore(a)));
            capped = new ArrayList<>(sorted.subList(0, DEEP_ANALYSIS_MAX));
        }
        return capped;
    }

    private static int absScore(ObjectNode article)
    {
        return Math.abs(article.path("screener_score").asInt(0));
    }

    private static String preTrendLabel(ArrayNode bars)
    {
        String label = null;
        if (bars != null && bars.size() > 0)
        {
            JsonNode node = PreTrend.of(bars).path("label");
            label = node.isNull() ? null : node.asText();
        }
        return label;
    }

    private static ArrayNode keyEvents(ObjectNode prediction)
    {
        JsonNode events = prediction.get("key_events");
        return events instanceof ArrayNode ? (ArrayNode) events : Json.newArray();
    }

    private static ArrayNode ids(List<ObjectNode> articles)
    {
        ArrayNode ids = Json.newArray();
        for (ObjectNode article : articles)
        {
            ids.add(article.path("id").asInt());
        }
        return ids;
    }

    private static List<ObjectNode> asList(ArrayNode array)
    {
        List<ObjectNode> list = new ArrayList<>();
        for (JsonNode element : array)
        {
            if (element instanceof ObjectNode)
            {
                list.add((ObjectNode) element);
            }
        }
        return list;
    }

    private static String optText(ObjectNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer coerceInt(JsonNode node)
    {
        Integer result = null;
        if (node != null && node.isInt())
        {
            result = node.asInt();
        }
        else if (node != null && node.isTextual())
        {
            try
            {
                result = Integer.valueOf(node.asText().trim());
            }
            catch (NumberFormatException notInt)
            {
                result = null;
            }
        }
        return result;
    }

    /** The outcome of {@link #planBackfill}: the windows needing analysis and whether the scan hit its cap. */
    public record BackfillPlan(List<Intervals.DayKey> windows, boolean truncated)
    {
    }
}
