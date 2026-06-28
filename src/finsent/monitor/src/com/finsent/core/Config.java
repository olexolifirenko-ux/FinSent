package com.finsent.core;

import java.util.ArrayList;
import java.util.List;

import com.finsent.util.GlobalSystem;
import com.finsent.util.xml.XMLData;

/**
 * Typed read-only view over this process's bootstrap configuration. Replaces the Python
 * {@code sentiment_config.json}, split across two sibling sections of the {@code <FSSatellite>}
 * bootstrap node (see {@code Config/processes.xml}):
 * <ul>
 *   <li>{@code <FSCollector>} &mdash; what the collector owns: data cadence &amp; shape (article
 *       sources/feeds, OHLC bar size, article retention, poll/recovery cadence). It also carries
 *       the three genuinely-shared structural params ({@code analysisNewsWindow},
 *       {@code ohlcImpactWindow}, {@code optionsEnabled}); the analyser reads these from the
 *       collector at runtime, so there is a single source of truth and no drift.</li>
 *   <li>{@code <FSAnalyser>} &mdash; what the analyser owns: interpretation &amp; notification
 *       (Claude models/screening, the notification gate, delivery channels, macro thresholds).</li>
 * </ul>
 * Scalars are attributes, the source/feed lists and macro thresholds are child elements.
 * Secret-bearing attributes are resolved through {@link Secrets} (the {@code ENV:}/{@code $VAR}
 * convention), and duration specs such as {@code "10m"} are exposed both raw and as minutes via
 * {@link Times}.
 */
public final class Config
{
    private final XMLData collectorNode_;
    private final XMLData analyserNode_;
    private final XMLData notifyNode_;
    private final XMLData traderNode_;
    private final XMLData newsLaneNode_;
    private final XMLData fastLaneNode_;
    private final XMLData fastMoveNode_;

    /**
     * Wrap the process bootstrap node (the {@code <FSSatellite>} section) that carries the
     * {@code <FSCollector>} and {@code <FSAnalyser>} configuration sections as direct children. The
     * notification settings (delivery channels + the notify gate) live in a {@code <Notifications>}
     * child of {@code <FSAnalyser>}, read here as a sub-section.
     */
    public Config(XMLData processNode)
    {
        collectorNode_ = subSection(processNode, "FSCollector");
        analyserNode_ = subSection(processNode, "FSAnalyser");
        notifyNode_ = subSection(analyserNode_, "Notifications");
        traderNode_ = subSection(processNode, "FSTrader");
        // Per-lane trade settings live under <FSTrader>; the shared execution + exit-management settings stay
        // as <FSTrader> attributes. <FSFastMove> holds only the detection settings (read by the poller).
        newsLaneNode_ = subSection(traderNode_, "NewsLane");
        fastLaneNode_ = subSection(traderNode_, "FastMoveLane");
        fastMoveNode_ = subSection(processNode, "FSFastMove");
    }

    /** Build from this process's bootstrap section ({@link GlobalSystem#getBootstrapConfigData()}). */
    public static Config fromGlobalSystem()
    {
        return new Config(GlobalSystem.getBootstrapConfigData());
    }

    // == Collector-owned: article sourcing & data cadence/shape ================

    /** Article sources ({@code <Sources><Source name=.. apiKey=../></Sources>}). */
    public List<Source> sources()
    {
        List<Source> result = new ArrayList<>();
        for (XMLData source : children(collectorNode_, "Sources", "Source"))
        {
            result.add(new Source(source.getAttributeStringValue("name", ""),
                    Secrets.resolve(source.getAttributeStringValue("apiKey", ""))));
        }
        return result;
    }

    /** Standard RSS feeds polled on window boundaries. */
    public List<Feed> rssFeeds()
    {
        return feeds(collectorNode_, "RssFeeds", "Feed");
    }

    /** High-frequency urgent feeds. */
    public List<Feed> urgentSources()
    {
        return feeds(collectorNode_, "UrgentSources", "Source");
    }

    public String ohlcBarSize()
    {
        return attr(collectorNode_, "ohlcBarSize", "1m");
    }

    public String articleMaxAge()
    {
        return attr(collectorNode_, "articleMaxAge", "12h");
    }

    public int articleMaxAgeMinutes()
    {
        return Times.intervalMinutes(articleMaxAge());
    }

    public int urgentPollInSec()
    {
        return intAttr(collectorNode_, "urgentPollInSec", 30);
    }

    /** Base URL of the Yahoo Finance chart API the macro fetcher reads indicators from. */
    public String yahooChartBaseUrl()
    {
        return attr(collectorNode_, "yahooChartBaseUrl", "https://query1.finance.yahoo.com/v8/finance/chart");
    }

    /** Base URL of the public Deribit v2 API the options fetcher reads BTC options from. */
    public String deribitBaseUrl()
    {
        return attr(collectorNode_, "deribitBaseUrl", "https://www.deribit.com/api/v2");
    }

    /** URL of the public Binance klines endpoint the OHLC fetcher reads BTC candles from. */
    public String binanceBaseUrl()
    {
        return attr(collectorNode_, "binanceBaseUrl", "https://api.binance.com/api/v3/klines");
    }

    /** Base URL of the public Binance USD-M futures API the funding fetcher reads from. */
    public String binanceFuturesBaseUrl()
    {
        return attr(collectorNode_, "binanceFuturesBaseUrl", "https://fapi.binance.com/fapi/v1");
    }

    /** BLS public API v2 base (#21); the series id is appended as a path segment. */
    public String blsBaseUrl()
    {
        return attr(collectorNode_, "blsBaseUrl", "https://api.bls.gov/publicAPI/v2/timeseries/data");
    }

    /** Registration key for the BLS public API v2 (#21); "" falls back to the throttled keyless tier. */
    public String blsApiKey()
    {
        return Secrets.resolve(attr(collectorNode_, "blsApiKey", ""));
    }

    /** Whether the econ-calendar scheduler auto-arms scheduled releases; manual {@code collect/anal econ} stay available. */
    public boolean econEnabled()
    {
        return boolAttr(collectorNode_, "econEnabled", false);
    }

    /** Static econ-event definitions catalog (#21), resolved against the run dir; "" disables the module. */
    public String econDefinitionsFile()
    {
        return attr(collectorNode_, "econDefinitionsFile", "cfg/econ_definitions.json");
    }

    /** How long after a scheduled release to keep polling BLS for the fresh print before giving up (#21). */
    public int econPollCapMinutes()
    {
        return Times.intervalMinutes(attr(collectorNode_, "econPollCap", "10m"));
    }

    public int recoveryLookbackInDays()
    {
        return intAttr(collectorNode_, "recoveryLookbackInDays", 3);
    }

    /** GetXAPI advanced-search endpoint the fast X (Twitter) amplifier source polls. */
    public String getxapiSearchUrl()
    {
        return attr(collectorNode_, "getxapiSearchUrl", "https://api.getxapi.com/twitter/tweet/advanced_search");
    }

    /** GetXAPI registration key for the X amplifier source; "" disables the source. */
    public String getxapiKey()
    {
        return Secrets.resolve(attr(collectorNode_, "getxapiKey", ""));
    }

    /**
     * Backward-walk page cap for the X amplifier source's advanced_search per poll: each page is ~20
     * tweets, paged back until the watermark, so this bounds a cold-start catch-up / runaway burst
     * (default 5 = ~100 tweets/poll, far above any realistic 15-30s burst).
     */
    public int getxapiMaxPages()
    {
        return intAttr(collectorNode_, "getxapiMaxPages", 5);
    }

    /** Core X (Twitter) amplifier handles (permanent), polled as part of the merged squawk query. */
    public List<String> xAccounts()
    {
        return handles("XAccounts");
    }

    /**
     * Situational X handles to follow temporarily as world events shift (a principal who matters during
     * a developing crisis); merged with {@link #xAccounts()} into the one squawk query. Meant to be
     * curated/pruned by hand as situations resolve.
     */
    public List<String> xSituationalAccounts()
    {
        return handles("XSituationalAccounts");
    }

    /** The {@code handle} attributes of {@code <Account>} children under {@code collectorNode_/<container>}. */
    private List<String> handles(String container)
    {
        List<String> result = new ArrayList<>();
        for (XMLData account : children(collectorNode_, container, "Account"))
        {
            result.add(account.getAttributeStringValue("handle", ""));
        }
        return result;
    }

    /**
     * Directory the collected data files live in (articles, context snapshots, OHLC). Collector-
     * owned; the analyser reads/writes the same directory through the collector. Relative values
     * resolve against the release home via the directory subsystem; an absolute path is honoured
     * as-is.
     */
    public String dataDir()
    {
        return attr(collectorNode_, "dataDir", "data");
    }

    // == Shared structural params (live under <FSCollector>) ====================

    public String analysisNewsWindow()
    {
        return attr(collectorNode_, "analysisNewsWindow", "10m");
    }

    public int windowMinutes()
    {
        return Times.intervalMinutes(analysisNewsWindow());
    }

    public String ohlcImpactWindow()
    {
        return attr(collectorNode_, "ohlcImpactWindow", "30m");
    }

    public int ohlcImpactWindowMinutes()
    {
        return Times.intervalMinutes(ohlcImpactWindow());
    }

    public boolean optionsEnabled()
    {
        return boolAttr(collectorNode_, "optionsEnabled", false);
    }

    public boolean fundingEnabled()
    {
        return boolAttr(collectorNode_, "fundingEnabled", false);
    }

    /** Whether the macro tape (VIX/DXY/S&amp;P/10y/gold) is collected; also gates the macro-alert breach check. */
    public boolean macroEnabled()
    {
        return boolAttr(collectorNode_, "macroEnabled", false);
    }

    // == Analyser-owned: Claude analysis & screening ===========================

    public String anthropicApiKey()
    {
        return Secrets.resolve(attr(analyserNode_, "anthropicApiKey", ""));
    }

    public String claudeDeepAnalModel()
    {
        return attr(analyserNode_, "claudeDeepAnalModel", "claude-sonnet-4-6");
    }

    /**
     * Reasoning effort for the deep pass's adaptive thinking ({@code output_config.effort}): caps how
     * much the model deliberates before answering. {@code medium} is the quality/latency sweet spot for
     * the bounded 3-step test; {@code high} (the API default) is the slower fallback. Sonnet 4.6 accepts
     * low|medium|high (not max). Applied only to the thinking deep pass, never the Haiku screener.
     */
    public String claudeDeepEffort()
    {
        return attr(analyserNode_, "claudeDeepEffort", "medium");
    }

    public String claudeScreenerModel()
    {
        return attr(analyserNode_, "claudeScreenerModel", "claude-haiku-4-5-20251001");
    }

    /** URL of the Anthropic Messages API the Claude passes POST to. */
    public String anthropicMessagesUrl()
    {
        return attr(analyserNode_, "anthropicMessagesUrl", "https://api.anthropic.com/v1/messages");
    }

    public int screenerThreshold()
    {
        return intAttr(analyserNode_, "screenerThreshold", 2);
    }

    /** Lookback for the screener's cross-window dedup memory of recently-resonant stories (default 6h). */
    public int screenerDedupLookbackMinutes()
    {
        return Times.intervalMinutes(attr(analyserNode_, "screenerDedupLookback", "6h"));
    }

    /**
     * Lookback for the DEEP pass's cross-window dedup memory (default 6h). Set higher than the screener's
     * to catch multi-day running themes -- the deep pass runs on far fewer items (only the resonant set),
     * so a longer covered block is affordable; the recall-biased screener stays at its shorter window.
     */
    public int deepDedupLookbackMinutes()
    {
        return Times.intervalMinutes(attr(analyserNode_, "deepDedupLookback", "6h"));
    }

    /**
     * Directory the Claude prompt templates ({@code screener.txt}, {@code deep_analysis.txt}) live
     * in. Analyser-owned. Relative values resolve against the release home via the directory
     * subsystem; an absolute path is honoured as-is.
     */
    public String promptsDir()
    {
        return attr(analyserNode_, "promptsDir", "prompts");
    }

    // == Analyser-owned: notification gate & delivery (the <Notifications> child of <FSAnalyser>) ====

    public String notifyMinImpactTier()
    {
        return attr(notifyNode_, "notifyMinImpactTier", "high");
    }

    public String newsAgeToNotify()
    {
        return attr(notifyNode_, "newsAgeToNotify", "1h");
    }

    public int newsAgeToNotifyMinutes()
    {
        return Times.intervalMinutes(newsAgeToNotify());
    }

    public String telegramToken()
    {
        return Secrets.resolve(attr(notifyNode_, "telegramToken", ""));
    }

    public String telegramChatId()
    {
        return attr(notifyNode_, "telegramChatId", "");
    }

    /** Base URL of the Telegram Bot API the notifier POSTs {@code sendMessage} to. */
    public String telegramApiBaseUrl()
    {
        return attr(notifyNode_, "telegramApiBaseUrl", "https://api.telegram.org");
    }

    public String emailTo()
    {
        return attr(notifyNode_, "emailTo", "");
    }

    public String smtpHost()
    {
        return attr(notifyNode_, "smtpHost", "");
    }

    public int smtpPort()
    {
        return intAttr(notifyNode_, "smtpPort", 587);
    }

    public String smtpUser()
    {
        return attr(notifyNode_, "smtpUser", "");
    }

    public String smtpPassword()
    {
        return Secrets.resolve(attr(notifyNode_, "smtpPassword", ""));
    }

    // == Trader-owned: paper trading strategy (FSTrader) =======================

    /** Minimum impact tier a directional call must reach to open a position ({@code high} by default). */
    public String tradeEntryImpactTier()
    {
        return attr(newsLaneNode_, "entryImpactTier", "high");
    }

    /**
     * Max age of the catalyst (newest resonant article) for the trader to open on it, in minutes: real-money
     * entry must be on a FRESH event, so a stale call (re-analysis, backfill, late-arriving article) is
     * skipped. Tight by default ({@code 5}, half a news window) since committing capital is more
     * time-sensitive than an alert; {@code 0} disables the freshness gate.
     */
    public int tradeEntryMaxNewsAgeInMin()
    {
        return intAttr(newsLaneNode_, "entryMaxNewsAgeInMin", 5);
    }

    /**
     * Max price divergence (percent) between the analysis-time BTC price ({@code btc_at_prediction}) and
     * the live entry price for the trader to still open: a sharp move in the gap between the deep analysis
     * and the order means the market has repriced since the verdict, so the entry is skipped (real-money
     * safety rail). Set above the Binance/WhiteBIT basis to avoid false trips; {@code 0} disables it.
     */
    public double tradeEntryMaxPriceDivergencePct()
    {
        return doubleAttr(traderNode_, "entryMaxPriceDivergencePct", 1.0);
    }

    /** Execution venue for the auto-trader: {@code paper} (simulated, the default) or {@code whitebit} (live). */
    public String tradeBroker()
    {
        return attr(traderNode_, "broker", "paper");
    }

    /** Margin committed per trade in USD (paper and live alike); exposure is this times {@link #tradeLeverage()}. */
    public double tradeNotionalInUsd()
    {
        return doubleAttr(newsLaneNode_, "notionalInUsd", 1000.0);
    }

    public double tradeLeverage()
    {
        return doubleAttr(newsLaneNode_, "leverage", 2.0);
    }

    /** Initial stop distance from entry, in percent (the trailing stop ratchets from here). */
    public double tradeStopLossInPct()
    {
        return doubleAttr(newsLaneNode_, "stopLossInPct", 1.0);
    }

    /** Whether a fresh opposite-direction qualifying news call closes an open news position (reversal exit). */
    public boolean newsReversalExit()
    {
        return boolAttr(newsLaneNode_, "reversalExit", true);
    }

    /** Trailing-stop distance behind the best price, in percent. */
    public double tradeTrailInPct()
    {
        return doubleAttr(traderNode_, "trailInPct", 1.0);
    }

    /**
     * Grace period after entry to become profitable: if the position is not in profit by then it is
     * closed (the fast-catalyst "is the thesis working yet?" exit). {@code 0} disables the time stop.
     */
    public int tradeProfitGraceInMin()
    {
        return intAttr(traderNode_, "profitGraceInMin", 30);
    }

    /** Max holding time before a position is closed regardless of price (a chop backstop). */
    public int tradeMaxHoldInHours()
    {
        return intAttr(traderNode_, "maxHoldInHours", 24);
    }

    /** How often the open position is re-evaluated against the price for the trailing stop. */
    public int tradePricePollInSec()
    {
        return intAttr(traderNode_, "pricePollInSec", 20);
    }

    /**
     * Taker fee charged per side as a percent of traded notional (exposure), applied on BOTH the entry and
     * the exit; reported P&amp;L is net of it. WhiteBIT BTC_USDT futures taker is ~0.035% &mdash; set it to
     * your actual fee tier. Shared by both lanes; {@code 0} models a costless fill.
     */
    public double tradeFeeRatePct()
    {
        return doubleAttr(traderNode_, "feeRatePct", 0.035);
    }

    /**
     * Per-side slippage the <b>paper</b> broker applies adversely to each market fill (percent of price): a
     * BUY fills this much above, a SELL below, modeling the spread/impact a market order crosses. Live fills
     * already carry real slippage, so this only affects paper/backtest realism. {@code 0} fills at the price.
     */
    public double tradeSlippageInPct()
    {
        return doubleAttr(traderNode_, "slippageInPct", 0.02);
    }

    /**
     * Daily realized-loss kill-switch in USD: once the day's net realized P&amp;L is at or below {@code -this}
     * value, no NEW position opens for the rest of the UTC day (an open position is still managed to its exit).
     * Account-level, shared by both lanes. {@code 0} disables it (the default &mdash; opt in deliberately).
     */
    public double tradeMaxDailyLossInUsd()
    {
        return doubleAttr(traderNode_, "maxDailyLossInUsd", 0.0);
    }

    /**
     * Max number of trades (round trips) per UTC day before new opens are halted &mdash; the runaway/whipsaw
     * circuit breaker. Counts closed trades for the day; an open position is still managed to its exit.
     * Account-level, shared by both lanes. {@code 0} disables it.
     */
    public int tradeMaxTradesPerDay()
    {
        return intAttr(traderNode_, "maxTradesPerDay", 0);
    }

    /**
     * Whether a live entry attaches a venue-resting protective stop (an OTO bracket at the initial stop) so the
     * position keeps a stop at the venue even if this process dies, and a close first cancels it. {@code false}
     * by default &mdash; it places live conditional orders whose reduce-only / auto-cancel-on-flat behavior
     * must be validated against the venue first. Paper ignores it (no resting orders). Shared by both lanes.
     */
    public boolean tradeVenueStop()
    {
        return boolAttr(traderNode_, "venueStop", false);
    }

    /** WhiteBIT private-API public key (read-only connectivity check for now); "" disables it. */
    public String whitebitApiKey()
    {
        return Secrets.resolve(attr(traderNode_, "whitebitApiKey", ""));
    }

    /** WhiteBIT private-API secret used to HMAC-sign requests; "" disables the WhiteBIT client. */
    public String whitebitApiSecret()
    {
        return Secrets.resolve(attr(traderNode_, "whitebitApiSecret", ""));
    }

    /** Base URL of the WhiteBIT API the private client posts to (paths like {@code /api/v4/...}). */
    public String whitebitBaseUrl()
    {
        return attr(traderNode_, "whitebitBaseUrl", "https://whitebit.com");
    }

    /** WhiteBIT collateral (futures) market the trader operates on, e.g. {@code BTC_USDT}. */
    public String whitebitMarket()
    {
        return attr(traderNode_, "whitebitMarket", "BTC_USDT");
    }

    // == FastMove-owned: mechanical price-tape trigger (FSFastMove) =============

    /**
     * Whether a FastMove fire may open a position. When false the detector still publishes/logs its
     * signals (alert-only telemetry) but the trader never opens on them -- the safe default while the
     * windows/thresholds are still being tuned against a live tape.
     */
    public boolean fastMoveTrade()
    {
        return boolAttr(fastLaneNode_, "trade", false);
    }

    /**
     * How often the detector samples the live price into its rolling buffer. Sub-minute (the leading-edge
     * latency is FastMove's edge) but not finer than needed: the buffer collapses to 1-minute closes, so
     * faster sampling only refreshes the current minute's endpoint, not the resolution.
     */
    public int fastMovePollInSec()
    {
        return intAttr(fastMoveNode_, "pollInSec", 20);
    }

    /** Whether a confirmed opposite-direction fire closes an open momentum position at market (reversal exit). */
    public boolean fastMoveReversalExit()
    {
        return boolAttr(fastLaneNode_, "reversalExit", true);
    }

    /**
     * Minutes to suppress a fresh FastMove OPEN after a momentum position closes -- the re-entry cooldown
     * that keeps a choppy post-move bounce from whipsawing the trader in and out. The reversal exit still
     * closes a live position; this gates only re-OPENING. 0 disables.
     */
    public int fastMoveReentryCooldownInMin()
    {
        return intAttr(fastLaneNode_, "reentryCooldownInMin", 30);
    }

    /**
     * Minimum conviction a fire must reach to OPEN a momentum position: {@code full} (default) or
     * {@code reduced}. Default {@code full} because the backtest showed {@code reduced}-conviction fires
     * were net losers across days. A below-minimum fire still publishes/logs (alert-only telemetry).
     */
    public String fastMoveMinConviction()
    {
        return attr(fastLaneNode_, "minConviction", "full");
    }

    /**
     * The detection windows ({@code <Windows><Window span=.. thresholdPct=.. r2Floor=../></Windows>}):
     * each a lookback span (e.g. {@code "30m"}), the endpoint move (percent) that fires it, and the
     * regression-fit floor that keeps choppy drift from firing. Multiple spans cover spikes through grinds.
     */
    public List<FastMoveWindow> fastMoveWindows()
    {
        List<FastMoveWindow> result = new ArrayList<>();
        for (XMLData window : children(fastMoveNode_, "Windows", "Window"))
        {
            result.add(new FastMoveWindow(Times.intervalMinutes(window.getAttributeStringValue("span", "30m")),
                    window.getAttributeDoubleValue("thresholdPct", 1.5),
                    window.getAttributeDoubleValue("r2Floor", 0.5)));
        }
        return result;
    }

    /** Minimum gap before the same-direction move may re-fire (the once-per-move debounce). */
    public int fastMoveCooldownInMin()
    {
        return intAttr(fastMoveNode_, "cooldownInMin", 30);
    }

    /** Open-interest change over the lookback that grades a fire as fresh positioning ({@code full}); +/- threshold. */
    public double fastMoveOiBuildingPct()
    {
        return doubleAttr(fastMoveNode_, "oiBuildingPct", 0.5);
    }

    /** How far back the funding/OI structural gate compares open interest from. */
    public int fastMoveOiLookbackInMin()
    {
        return intAttr(fastMoveNode_, "oiLookbackInMin", 40);
    }

    /** Acceleration ratio (shortest-window pace / longest-window pace) at/above which an unwinding-OI fire is
     *  a FORCED cascade/squeeze and graded {@code reduced} rather than {@code skip}. */
    public double fastMoveAccelRatio()
    {
        return doubleAttr(fastMoveNode_, "accelRatio", 1.5);
    }

    /** Relative fall in funding (percent) over its window that counts as the compressing early-warning. */
    public double fastMoveFundingCompressionDropPct()
    {
        return doubleAttr(fastMoveNode_, "fundingCompressionDropPct", 40.0);
    }

    /** Lookback for the funding-compression early-warning. */
    public int fastMoveFundingCompressionWindowMinutes()
    {
        return Times.intervalMinutes(attr(fastMoveNode_, "fundingCompressionWindow", "60m"));
    }

    /** Margin per FastMove trade in USD (deliberately smaller than the news trader's by default). */
    public double fastMoveNotionalInUsd()
    {
        return doubleAttr(fastLaneNode_, "notionalInUsd", 150.0);
    }

    public double fastMoveLeverage()
    {
        return doubleAttr(fastLaneNode_, "leverage", 3.0);
    }

    /**
     * Initial stop for a FastMove entry, in percent. FastMove-owned (not shared with {@code <FSTrader>}):
     * a momentum entry fires mid-move and needs a wider stop than a news entry to survive the normal
     * counter-retrace -- the backtest showed the tight news stop whipsaws it. Trail / max-hold / profit-grace
     * are shared with the news trader.
     */
    public double fastMoveStopLossInPct()
    {
        return doubleAttr(fastLaneNode_, "stopLossInPct", 1.0);
    }

    // == Helpers ===============================================================

    private static XMLData subSection(XMLData parent, String tag)
    {
        return parent == null ? null : parent.getDocumentPart(tag, false);
    }

    private List<Feed> feeds(XMLData node, String container, String childTag)
    {
        List<Feed> result = new ArrayList<>();
        for (XMLData feed : children(node, container, childTag))
        {
            result.add(new Feed(feed.getAttributeStringValue("name", ""),
                    feed.getAttributeStringValue("url", "")));
        }
        return result;
    }

    private List<XMLData> children(XMLData node, String container, String childTag)
    {
        List<XMLData> result = new ArrayList<>();
        XMLData containerNode = node == null ? null : node.getDocumentPart(container, false);
        if (containerNode != null)
        {
            for (XMLData child : containerNode.subElements(childTag))
            {
                result.add(child);
            }
        }
        return result;
    }

    private static String attr(XMLData node, String name, String defaultValue)
    {
        return node == null ? defaultValue : node.getAttributeStringValue(name, defaultValue);
    }

    private static int intAttr(XMLData node, String name, int defaultValue)
    {
        return node == null ? defaultValue : node.getAttributeIntValue(name, defaultValue);
    }

    private static boolean boolAttr(XMLData node, String name, boolean defaultValue)
    {
        return node == null ? defaultValue : node.getAttributeBooleanValue(name, defaultValue);
    }

    private static double doubleAttr(XMLData node, String name, double defaultValue)
    {
        return node == null ? defaultValue : node.getAttributeDoubleValue(name, defaultValue);
    }

    /** A configured article source and its (resolved) API key. */
    public record Source(String name, String apiKey)
    {
    }

    /** A named RSS/Atom feed URL. */
    public record Feed(String name, String url)
    {
    }

}
