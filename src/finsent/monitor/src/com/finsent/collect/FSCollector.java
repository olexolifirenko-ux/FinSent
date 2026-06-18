package com.finsent.collect;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.collect.source.ArticleSources;
import com.finsent.collect.source.IArticleSource;
import com.finsent.collect.source.XSquawkSource;
import com.finsent.core.Config;
import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.core.event.EventBus;
import com.finsent.core.event.IEventListener;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.PersistenceService;
import com.finsent.core.registry.ArticleRegistry;
import com.finsent.core.registry.EconEventRegistry;
import com.finsent.core.registry.IRegistry;
import com.finsent.core.registry.IntervalSnapshotRegistry;
import com.finsent.core.registry.OhlcRegistry;
import com.finsent.util.GlobalSystem;

/**
 * Singleton collector component: it fetches articles + market context, stores them, and is the
 * <b>subject</b> the analyser observes. It owns the in-memory registries (articles, macro, options,
 * OHLC), the persistence boundary ({@link PersistenceService}, created for the given data dir), the
 * article sources + market-data fetchers, and a private {@link EventBus} used only to deliver each
 * cycle's {@link CollectionResult} to subscribers. {@link #collect(Instant)} runs one boundary
 * cycle &mdash; ensure the interval's context snapshots, fetch + filter + store articles, commit
 * the whole cycle as one atomic batch, then publish the result (ports Python {@code
 * collect.run_collect} + {@code ensure_context_stored}). The scheduling thread lives in
 * {@code CollectorRunner}; {@code now} is injected so a cycle is deterministic and testable.
 */
public final class FSCollector
{
    private static final String NAME = "FSCollector";

    private final Config config_;
    private final PersistenceService persistence_;
    private final EventBus eventBus_;
    private final ArticleRegistry articles_;
    private final IntervalSnapshotRegistry macro_;
    private final IntervalSnapshotRegistry options_;
    private final IntervalSnapshotRegistry price_;
    private final IntervalSnapshotRegistry funding_;
    private final OhlcRegistry ohlc_;
    private final EconEventRegistry econ_;
    private final List<IRegistry> registries_;
    private final List<IArticleSource> sources_;
    private final List<IArticleSource> urgentSources_;
    // The X (Twitter) squawk source if configured (key + accounts), else null -- held so its polling
    // can be toggled at runtime via the `collect x on|off` command.
    private final XSquawkSource xSource_;
    private final MacroFetcher macroFetcher_;
    private final OptionsFetcher optionsFetcher_;
    private final OhlcFetcher ohlcFetcher_;
    private final FundingFetcher fundingFetcher_;
    private final EconActuals econActuals_;
    // Serializes per-window context provisioning so the regular and urgent lanes, which can poll the
    // same window concurrently, do not both pass the "absent" guard and fetch over HTTP in parallel:
    // the first lane fetches and stores (registries update in memory eagerly), the second then finds
    // the snapshot present and reads it rather than re-fetching.
    private final Object contextLock_ = new Object();

    /** Production wiring: build the sources and fetchers from config. */
    public FSCollector(Config config, Path dataDir)
    {
        this(config, dataDir, ArticleSources.fromConfig(config),
                ArticleSources.urgentSourcesFromConfig(config),
                new MacroFetcher(config.yahooChartBaseUrl()),
                new OptionsFetcher(config.deribitBaseUrl()),
                new OhlcFetcher(config.binanceBaseUrl()),
                new FundingFetcher(config.binanceFuturesBaseUrl()),
                new EconActuals(config.blsBaseUrl(), config.blsApiKey()));
    }

    /** Injecting constructor: collaborators supplied directly (used by tests). */
    FSCollector(Config config, Path dataDir, List<IArticleSource> sources, List<IArticleSource> urgentSources,
            MacroFetcher macroFetcher, OptionsFetcher optionsFetcher, OhlcFetcher ohlcFetcher,
            FundingFetcher fundingFetcher, EconActuals econActuals)
    {
        config_ = config;
        persistence_ = new PersistenceService(dataDir);
        eventBus_ = new EventBus();
        articles_ = new ArticleRegistry();
        macro_ = new IntervalSnapshotRegistry(DataStream.MACRO);
        options_ = new IntervalSnapshotRegistry(DataStream.OPTIONS);
        price_ = new IntervalSnapshotRegistry(DataStream.PRICE_CONTEXT);
        funding_ = new IntervalSnapshotRegistry(DataStream.FUNDING);
        ohlc_ = new OhlcRegistry();
        econ_ = new EconEventRegistry();
        registries_ = List.of(articles_, macro_, options_, ohlc_, price_, funding_, econ_);
        sources_ = sources;
        urgentSources_ = urgentSources;
        xSource_ = findXSource(urgentSources);
        macroFetcher_ = macroFetcher;
        optionsFetcher_ = optionsFetcher;
        ohlcFetcher_ = ohlcFetcher;
        fundingFetcher_ = fundingFetcher;
        econActuals_ = econActuals;
    }

    /** Subscribe a listener to be notified (asynchronously) with each cycle's {@link CollectionResult}. */
    public void addListener(IEventListener<CollectionResult> listener)
    {
        eventBus_.subscribe(CollectionResult.class, listener);
    }

    /** Subscribe a listener for {@link EconResolved} events (a freshly-resolved scheduled release, #21). */
    public void addEconListener(IEventListener<EconResolved> listener)
    {
        eventBus_.subscribe(EconResolved.class, listener);
    }

    /**
     * Subscribe a listener for events of {@code eventType} on the collector-owned bus. Generic so a
     * downstream consumer (e.g. the analyser's {@code AnalysisReady}, which the trading module
     * observes) can subscribe without the collector having to know the event class &mdash; keeping
     * the collect layer free of any analyse/trade import.
     */
    public <E> void subscribe(Class<E> eventType, IEventListener<E> listener)
    {
        eventBus_.subscribe(eventType, listener);
    }

    /** Publish an event on the collector-owned bus (used by the analyser to emit downstream signals). */
    public void publish(Object event)
    {
        eventBus_.publish(event);
    }

    /** Turn the X (Twitter) source's polling on/off at runtime; no-op when X is not configured. */
    public void setXEnabled(boolean enabled)
    {
        if (xSource_ != null)
        {
            xSource_.setEnabled(enabled);
        }
    }

    /** Whether the X source is configured at all (a key + accounts) -- i.e. whether it can be toggled. */
    public boolean xConfigured()
    {
        return xSource_ != null;
    }

    /** Whether the X source is currently polling. */
    public boolean xEnabled()
    {
        return xSource_ != null && xSource_.isEnabled();
    }

    /** The X squawk source among the urgent sources, or null when it is not configured. */
    private static XSquawkSource findXSource(List<IArticleSource> urgentSources)
    {
        XSquawkSource found = null;
        for (IArticleSource source : urgentSources)
        {
            if (source instanceof XSquawkSource)
            {
                found = (XSquawkSource) source;
            }
        }
        return found;
    }

    /** Rebuild every registry's runtime state from the most recent {@code lookbackDays}. */
    public void recover(int lookbackDays)
    {
        for (IRegistry registry : registries_)
        {
            registry.hydrate(persistence_.load(registry.stream(), lookbackDays));
        }
    }

    /**
     * Lazily load one day's files into the registries for on-demand re-analysis of an older window.
     * For each stream whose {@code day} is not already resident, the day-file is read and added for
     * read access (a no-op when the file is absent); article id/watermark recovery state is left
     * untouched. Idempotent &mdash; resident days are skipped.
     */
    public void recoverDay(String day)
    {
        for (IRegistry registry : registries_)
        {
            if (!registry.isResident(day))
            {
                LoadedDay loaded = persistence_.loadDay(registry.stream(), day);
                if (loaded != null)
                {
                    registry.ensureDayResident(loaded);
                }
            }
        }
    }

    /**
     * Run one boundary collection cycle for the interval containing {@code now}: ensure context
     * snapshots, fetch from the configured sources, drop stale and at-or-below-watermark articles,
     * store + commit atomically, and publish the whole-window result to subscribers.
     */
    public CollectionResult collect(Instant now)
    {
        return runAndPublish(now, sources_, null, false);
    }

    /**
     * Run one urgent poll: fetch the urgent feeds, flag urgent-worthy articles, fetch per-article
     * OHLC for any urgent article in the current window, store + commit atomically, and publish the
     * result only when an urgent-worthy article landed in the window. Ports the Phase-3 subset of
     * Python {@code urgent.run_urgent_loop} (the analysis trigger + cooldown are Phase 4).
     */
    public CollectionResult collectUrgent(Instant now)
    {
        return runAndPublish(now, urgentSources_, UrgentKeywords::isUrgentWorthy, true);
    }

    private CollectionResult runAndPublish(Instant now, List<IArticleSource> sources,
            BiPredicate<String, String> urgentFilter, boolean urgent)
    {
        int window = config_.windowMinutes();
        String day = Times.dayOf(Times.formatUtcIso(now));
        String key = Times.intervalKey(now, window);

        CollectionBatch batch = new CollectionBatch();
        ensureContext(batch, now, window, day, key, urgent);

        Map<String, String> watermarks = articles_.watermarksSnapshot();
        Instant cutoff = now.minus(Duration.ofMinutes(config_.articleMaxAgeMinutes()));
        List<ObjectNode> fresh = new ArrayList<>();
        for (ObjectNode article : fetchArticles(sources, watermarks))
        {
            if (!isStale(article, cutoff) && !belowWatermark(article, watermarks))
            {
                fresh.add(article);
            }
        }
        if (urgentFilter != null)
        {
            flagUrgent(fresh, urgentFilter);
        }
        batch.add(articles_.store(fresh));
        // store() assigns an id only to genuinely-new articles; a re-delivered duplicate (e.g. the same
        // story via RSS and NewsAPI, whose other-source watermark never advances) is skipped here, so it
        // must not re-publish/re-analyse its window every cycle. Publish + count the actually-stored set.
        List<ObjectNode> stored = newlyStored(fresh);

        List<ObjectNode> urgentInWindow = urgent ? articles_.forInterval(day, key, true, window) : List.of();
        if (urgent)
        {
            addUrgentOhlc(batch, urgentInWindow);
        }
        persistence_.commit(batch.writes());

        publishWindows(day, key, stored, window, urgent);
        logCycle(urgent, day, key, stored, window);
        return new CollectionResult(day, key, stored.size(), articles_.forInterval(day, key, false, window), urgent);
    }

    /**
     * One cycle-summary line reflecting the whole multi-window publish: the windows that gained new
     * articles this cycle and how many each, rather than just the boundary key. No new articles is a
     * quiet {@code debug} heartbeat. The date is omitted (the logger stamps it); a window on a different
     * calendar day than the cycle keeps its day prefix so a late article is still unambiguous.
     */
    private void logCycle(boolean urgent, String cycleDay, String currentKey, List<ObjectNode> stored, int windowMinutes)
    {
        if (stored.isEmpty())
        {
            GlobalSystem.debug().writes(NAME, (urgent ? "Urgent poll " : "Collection cycle ")
                    + " -- no new articles");
        }
        else
        {
            GlobalSystem.info().writes(NAME, (urgent ? "Urgent: collected " : "Collected ") + stored.size()
                    + " new article(s) in " + windowBreakdown(stored, cycleDay, windowMinutes));
        }
    }

    /** {@code "16:10(1), 16:20(2)"} for the windows the stored articles fall into (day prefix only when != cycleDay). */
    private static String windowBreakdown(List<ObjectNode> stored, String cycleDay, int windowMinutes)
    {
        Map<String, Integer> byWindow = new TreeMap<>();
        for (ObjectNode article : stored)
        {
            String published = article.path("publishedAt").asText("");
            if (!published.isEmpty())
            {
                String pubDay = Times.dayOf(published);
                String label = pubDay.equals(cycleDay) ? Times.intervalKey(published, windowMinutes)
                        : pubDay + " " + Times.intervalKey(published, windowMinutes);
                byWindow.merge(label, 1, Integer::sum);
            }
        }
        StringBuilder breakdown = new StringBuilder();
        for (Map.Entry<String, Integer> entry : byWindow.entrySet())
        {
            breakdown.append(breakdown.length() == 0 ? "" : ", ").append(entry.getKey())
                    .append('(').append(entry.getValue()).append(')');
        }
        return breakdown.toString();
    }

    /**
     * Publish the windows the analyser should (re)analyse this cycle: every distinct window that
     * received a fresh article (keyed by the article's {@code publishedAt}) &mdash; so a late-arriving
     * article re-analyses its own past window regardless of the window's age &mdash; plus, on the
     * regular boundary lane, the current window as a heartbeat (it drives the macro-alert check and
     * catches zero-lag feeds). The analyser analyses each, so no throttle is needed: an article is
     * fresh exactly once, so a window only re-publishes when it genuinely gains new content.
     */
    private void publishWindows(String day, String key, List<ObjectNode> fresh, int windowMinutes, boolean urgent)
    {
        Set<String> seen = new LinkedHashSet<>();
        if (!urgent)
        {
            publishWindow(day, key, windowMinutes, urgent, seen);
        }
        for (ObjectNode article : fresh)
        {
            String published = article.path("publishedAt").asText("");
            if (!published.isEmpty())
            {
                publishWindow(Times.dayOf(published), Times.intervalKey(published, windowMinutes), windowMinutes, urgent, seen);
            }
        }
    }

    private void publishWindow(String day, String key, int windowMinutes, boolean urgent, Set<String> seen)
    {
        if (seen.add(day + " " + key))
        {
            List<ObjectNode> windowArticles = articles_.forInterval(day, key, false, windowMinutes);
            eventBus_.publish(new CollectionResult(day, key, windowArticles.size(), windowArticles, urgent));
        }
    }

    /** The genuinely-new articles among {@code fresh}: {@code store()} assigns an {@code id} only to
     *  non-duplicates, so a re-delivered article (same content hash) is excluded here. */
    private static List<ObjectNode> newlyStored(List<ObjectNode> fresh)
    {
        List<ObjectNode> stored = new ArrayList<>();
        for (ObjectNode article : fresh)
        {
            if (article.has("id"))
            {
                stored.add(article);
            }
        }
        return stored;
    }

    /** Fetch and merge per-article OHLC covering the urgent-worthy articles in the current window. */
    private void addUrgentOhlc(CollectionBatch batch, List<ObjectNode> urgentInWindow)
    {
        if (ohlcFetcher_ != null && !urgentInWindow.isEmpty())
        {
            Instant earliest = null;
            Instant latest = null;
            for (ObjectNode article : urgentInWindow)
            {
                Instant published = publishedInstant(article);
                if (published != null)
                {
                    earliest = (earliest == null || published.isBefore(earliest)) ? published : earliest;
                    latest = (latest == null || published.isAfter(latest)) ? published : latest;
                }
            }
            if (earliest != null)
            {
                long[] range = OhlcWindows.articleWindow(earliest, latest, config_.ohlcImpactWindowMinutes());
                ArrayNode bars = ohlcFetcher_.fetchCandles(range[0], range[1], config_.ohlcBarSize());
                if (!bars.isEmpty())
                {
                    batch.add(ohlc_.merge(bars));
                }
            }
        }
    }

    private static Instant publishedInstant(ObjectNode article)
    {
        String pub = article.path("publishedAt").asText("");
        Instant instant = null;
        if (!pub.isEmpty())
        {
            try
            {
                instant = Times.parseIso(pub);
            }
            catch (RuntimeException badDate)
            {
                instant = null;
            }
        }
        return instant;
    }

    private List<ObjectNode> fetchArticles(List<IArticleSource> sources, Map<String, String> watermarks)
    {
        List<ObjectNode> all = new ArrayList<>();
        for (IArticleSource source : sources)
        {
            for (ObjectNode article : source.fetch(watermarks))
            {
                article.put("_source", source.name());
                all.add(article);
            }
        }
        return all;
    }

    private void ensureContext(CollectionBatch batch, Instant now, int window, String day, String key, boolean urgent)
    {
        synchronized (contextLock_)
        {
            boolean macroStored = storeMacro(batch, now, day, key);
            boolean optionsStored = storeOptions(batch, day, key);
            // The OHLC series is fetched incrementally on the regular boundary lane only; the urgent
            // lane covers its own articles via addUrgentOhlc, so it skips the boundary strip fetch.
            boolean ohlcStored = !urgent && storeOhlcStrip(batch, now, window);
            boolean priceStored = storePriceContext(batch, day, key);
            boolean fundingStored = storeFunding(batch, day, key);
            logContextWhenCaptured(day, key,
                    macroStored || optionsStored || ohlcStored || priceStored || fundingStored);
        }
    }

    /**
     * One info line the first time a window's context is captured (macro/options/OHLC are stored
     * once per window, so this fires once, not on every poll). Reports each component's presence and
     * the window's BTC price so the analyser's inputs are visible; failures are already logged by the
     * fetchers, so a quiet line here means nothing newly stored (the common already-present case).
     */
    private void logContextWhenCaptured(String day, String key, boolean stored)
    {
        if (stored)
        {
            ArrayNode strip = boundaryBars(day, key);
            GlobalSystem.info().writes(NAME, "Context " + day + " " + key
                    + " -- macro=" + present(!macro_.get(day, key).isEmpty())
                    + " options=" + present(!options_.get(day, key).isEmpty())
                    + " funding=" + present(!funding_.get(day, key).isEmpty())
                    + " OHLC=" + strip.size() + "bars"
                    + " BTC=" + latestClose(strip));
        }
    }

    /** Bars in this window's boundary-strip range, read from the flat series for the Context log line. */
    private ArrayNode boundaryBars(String day, String key)
    {
        ArrayNode bars = Json.newArray();
        Instant boundary = windowInstant(day, key);
        if (boundary != null)
        {
            bars = ohlc_.barsInRange(
                    Times.formatUtcIso(boundary.minus(Duration.ofMinutes(config_.ohlcImpactWindowMinutes()))),
                    Times.formatUtcIso(boundary.plus(Duration.ofMinutes(config_.windowMinutes()))));
        }
        return bars;
    }

    /** {@code YYYYMMDD} + {@code HH:MM} &rarr; the window's UTC instant, or null when malformed. */
    private static Instant windowInstant(String day, String key)
    {
        Instant result = null;
        if (day.length() == 8 && key.length() == 5)
        {
            try
            {
                result = Times.parseIso(day.substring(0, 4) + "-" + day.substring(4, 6) + "-"
                        + day.substring(6, 8) + "T" + key + ":00Z");
            }
            catch (RuntimeException malformed)
            {
                result = null;
            }
        }
        return result;
    }

    /**
     * Resolve a scheduled release (#21) if its <b>fresh</b> BLS print has landed: fetch the actual, and
     * once its period clears the prior-month value still on the wire, store the raw
     * {@code {consensus, actual, ...}} once (keyed by event name on the release day) and, when
     * {@code publish}, publish an {@link EconResolved} so the analyser runs the econ analysis. The
     * {@link EconScheduler} passes {@code publish=true} at the release time (live &rarr; auto-analyse +
     * notify); the manual {@code collect econ} command passes {@code false} (fetch-only, leaving analysis
     * to {@code anal econ} so a back-dated catch-up does not fire a stale alert). Returns whether the event
     * is resolved (so the scheduler stops polling). Idempotent via the stored-once guard; the
     * surprise/direction is computed at analysis time by {@code EconEventSignals}, so the collector stays
     * free of the analysis layer. No-op (returns false) when econ is not wired (tests).
     */
    public boolean tryResolveEcon(EconEvent event, boolean publish)
    {
        String day = Times.dayOf(Times.formatUtcIso(event.release()));
        boolean resolved = econ_.resolved(day, event.name());
        if (!resolved && econActuals_ != null)
        {
            EconActuals.Reading reading = econActuals_.fetch(event.series(), event.kind());
            if (reading != null && reading.period() >= EconActuals.reportedPeriod(event.release()))
            {
                persistence_.commit(econ_.store(day, event.name(), resolvedEvent(event, reading.actual())));
                GlobalSystem.info().writes(NAME, "Scheduled event resolved: " + event.name()
                        + " actual=" + Num.round(reading.actual(), 4) + " vs consensus=" + event.consensus()
                        + (publish ? " (auto-analysing)" : " (fetch-only)"));
                if (publish)
                {
                    eventBus_.publish(new EconResolved(day,
                            Times.intervalKey(Times.formatUtcIso(event.release()), config_.windowMinutes()), event.name()));
                }
                resolved = true;
            }
        }
        return resolved;
    }

    /** The raw resolved event (signal inputs only; the surprise/direction is derived at analysis time). */
    private static ObjectNode resolvedEvent(EconEvent event, double actual)
    {
        ObjectNode resolved = Json.newObject();
        resolved.put("event", event.name());
        resolved.put("release", Times.formatUtcIso(event.release()));
        resolved.put("consensus", event.consensus());
        resolved.put("actual", Num.round(actual, 4));
        resolved.put("unit", event.unit());
        resolved.put("hot_direction", event.hotDirection());
        resolved.put("inline_band", event.inlineBand());
        resolved.put("high_band", event.highBand());
        return resolved;
    }

    /** Macro snapshot for the interval, unless it is a weekend (stale Yahoo data) or already stored. */
    private boolean storeMacro(CollectionBatch batch, Instant now, String day, String key)
    {
        boolean stored = macroFetcher_ != null && config_.macroEnabled() && !isWeekend(now) && macro_.get(day, key).isEmpty();
        if (stored)
        {
            batch.add(macro_.putIfAbsent(day, key, macroFetcher_.fetchSnapshot()));
        }
        return stored;
    }

    private boolean storeOptions(CollectionBatch batch, String day, String key)
    {
        boolean stored = false;
        if (optionsFetcher_ != null && config_.optionsEnabled() && options_.get(day, key).isEmpty())
        {
            ObjectNode snapshot = optionsFetcher_.fetchSnapshot();
            if (snapshot != null)
            {
                batch.add(options_.putIfAbsent(day, key, snapshot));
                stored = true;
            }
        }
        return stored;
    }

    private boolean storeFunding(CollectionBatch batch, String day, String key)
    {
        boolean stored = false;
        if (fundingFetcher_ != null && config_.fundingEnabled() && funding_.get(day, key).isEmpty())
        {
            ObjectNode snapshot = fundingFetcher_.fetchSnapshot();
            if (snapshot != null)
            {
                batch.add(funding_.putIfAbsent(day, key, snapshot));
                stored = true;
            }
        }
        return stored;
    }

    private boolean storeOhlcStrip(CollectionBatch batch, Instant now, int window)
    {
        boolean stored = false;
        if (ohlcFetcher_ != null)
        {
            long[] range = OhlcWindows.boundaryStrip(now, window, config_.ohlcImpactWindowMinutes());
            // Incremental: fetch only bars newer than the series already holds, but never starting before
            // the strip start -- so a long gap still costs at most one strip width, not the whole gap.
            long fromMs = Math.max(range[0], ohlc_.nextBarStartMs());
            if (fromMs <= range[1])
            {
                ArrayNode bars = ohlcFetcher_.fetchCandles(fromMs, range[1], config_.ohlcBarSize());
                if (!bars.isEmpty())
                {
                    batch.add(ohlc_.merge(bars));
                    stored = true;
                }
            }
        }
        return stored;
    }

    /**
     * Backdrop price context for the interval: one Binance call for {@value #PRICE_CONTEXT_LOOKBACK}h
     * of hourly bars, reduced to {@code {btc_price, change_1h_pct, change_24h_pct, range_pos_24h}}.
     * Stored per window (like macro/options) so re-analysis of an older window reads its own
     * historical backdrop rather than today's. Skipped when already present or the fetch is empty.
     */
    private boolean storePriceContext(CollectionBatch batch, String day, String key)
    {
        boolean stored = false;
        if (ohlcFetcher_ != null && price_.get(day, key).isEmpty())
        {
            JsonNode ticker = ohlcFetcher_.fetch24hTicker();
            if (ticker != null)
            {
                batch.add(price_.putIfAbsent(day, key, buildPriceSnapshot(ticker)));
                stored = true;
            }
        }
        return stored;
    }

    /** Reduce a Binance 24h ticker to the current price, 24h percent change, and 24h range position. */
    private static ObjectNode buildPriceSnapshot(JsonNode ticker)
    {
        double current = ticker.path("lastPrice").asDouble();
        double high = ticker.path("highPrice").asDouble();
        double low = ticker.path("lowPrice").asDouble();
        ObjectNode snapshot = Json.newObject();
        snapshot.put("btc_price", Num.round(current, 2));
        snapshot.put("change_24h_pct", Num.round(ticker.path("priceChangePercent").asDouble(), 4));
        if (high > low)
        {
            snapshot.put("range_pos_24h", Num.round((current - low) / (high - low), 4));
        }
        return snapshot;
    }

    private static String present(boolean yes)
    {
        return yes ? "yes" : "MISSING";
    }

    /** The close of the newest strip bar, formatted as a price, or {@code n/a} when there are none. */
    private static String latestClose(ArrayNode strip)
    {
        String latestTs = null;
        double close = 0.0;
        boolean found = false;
        for (JsonNode bar : strip)
        {
            String ts = bar.path("ts").asText("");
            if (latestTs == null || ts.compareTo(latestTs) > 0)
            {
                latestTs = ts;
                close = bar.path("c").asDouble();
                found = true;
            }
        }
        return found ? String.format(Locale.ROOT, "$%.2f", close) : "n/a";
    }

    private static void flagUrgent(List<ObjectNode> articles, BiPredicate<String, String> urgentFilter)
    {
        for (ObjectNode article : articles)
        {
            if (urgentFilter.test(article.path("title").asText(""), article.path("description").asText("")))
            {
                article.put("urgent_worthy", true);
            }
        }
    }

    private static boolean isStale(ObjectNode article, Instant cutoff)
    {
        String pub = article.path("publishedAt").asText("");
        boolean stale = false;
        if (!pub.isEmpty())
        {
            try
            {
                stale = Times.parseIso(pub).isBefore(cutoff);
            }
            catch (RuntimeException badDate)
            {
                stale = false;
            }
        }
        return stale;
    }

    private static boolean belowWatermark(ObjectNode article, Map<String, String> watermarks)
    {
        String pub = article.path("publishedAt").asText("");
        String sourceKey = sourceKey(article);
        return !pub.isEmpty() && !sourceKey.isEmpty()
                && pub.compareTo(watermarks.getOrDefault(sourceKey, "")) <= 0;
    }

    private static String sourceKey(ObjectNode article)
    {
        String feed = article.path("_rss_feed").asText("");
        return feed.isEmpty() ? article.path("_source").asText("") : feed;
    }

    private static boolean isWeekend(Instant now)
    {
        DayOfWeek day = now.atZone(ZoneOffset.UTC).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /** Block until all committed batches have been written (used by tests and forced flushes). */
    public void flush()
    {
        persistence_.flush();
    }

    /** Flush outstanding writes, stop the persistence writer, and stop the event bus (shutdown hook). */
    public void shutdown()
    {
        persistence_.shutdown();
        eventBus_.shutdown();
    }

    public Config config()
    {
        return config_;
    }

    public ArticleRegistry articles()
    {
        return articles_;
    }

    public IntervalSnapshotRegistry macro()
    {
        return macro_;
    }

    public IntervalSnapshotRegistry options()
    {
        return options_;
    }

    public IntervalSnapshotRegistry price()
    {
        return price_;
    }

    public IntervalSnapshotRegistry funding()
    {
        return funding_;
    }

    public OhlcRegistry ohlc()
    {
        return ohlc_;
    }

    public EconEventRegistry econ()
    {
        return econ_;
    }

    /**
     * Realized BTC close at {@code target} (one 1-minute Binance kline), or null when unavailable.
     * Used by the feedback scorer (BL#6) to look up the price at a past prediction's horizon.
     */
    public Double fetchClosePriceAt(Instant target)
    {
        Double price = null;
        if (ohlcFetcher_ != null)
        {
            long startMs = target.toEpochMilli();
            ArrayNode bars = ohlcFetcher_.fetchCandles(startMs, startMs + 60_000L, "1m");
            if (bars.size() > 0)
            {
                price = bars.get(0).path("c").asDouble();
            }
        }
        return price;
    }
}
