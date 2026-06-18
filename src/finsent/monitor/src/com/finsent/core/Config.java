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
    private final XMLData traderNode_;

    /**
     * Wrap the process bootstrap node (the {@code <FSSatellite>} section) that carries the
     * {@code <FSCollector>} and {@code <FSAnalyser>} configuration sections as direct children.
     */
    public Config(XMLData processNode)
    {
        collectorNode_ = subSection(processNode, "FSCollector");
        analyserNode_ = subSection(processNode, "FSAnalyser");
        traderNode_ = subSection(processNode, "FSTrader");
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
     * Directory the Claude prompt templates ({@code screener.txt}, {@code deep_analysis.txt}) live
     * in. Analyser-owned. Relative values resolve against the release home via the directory
     * subsystem; an absolute path is honoured as-is.
     */
    public String promptsDir()
    {
        return attr(analyserNode_, "promptsDir", "prompts");
    }

    // == Analyser-owned: notification gate & delivery ==========================

    public String notifyMinImpactTier()
    {
        return attr(analyserNode_, "notifyMinImpactTier", "high");
    }

    public String newsAgeToNotify()
    {
        return attr(analyserNode_, "newsAgeToNotify", "1h");
    }

    public int newsAgeToNotifyMinutes()
    {
        return Times.intervalMinutes(newsAgeToNotify());
    }

    public String telegramToken()
    {
        return Secrets.resolve(attr(analyserNode_, "telegramToken", ""));
    }

    public String telegramChatId()
    {
        return attr(analyserNode_, "telegramChatId", "");
    }

    /** Base URL of the Telegram Bot API the notifier POSTs {@code sendMessage} to. */
    public String telegramApiBaseUrl()
    {
        return attr(analyserNode_, "telegramApiBaseUrl", "https://api.telegram.org");
    }

    public String emailTo()
    {
        return attr(analyserNode_, "emailTo", "");
    }

    public String smtpHost()
    {
        return attr(analyserNode_, "smtpHost", "");
    }

    public int smtpPort()
    {
        return intAttr(analyserNode_, "smtpPort", 587);
    }

    public String smtpUser()
    {
        return attr(analyserNode_, "smtpUser", "");
    }

    public String smtpPassword()
    {
        return Secrets.resolve(attr(analyserNode_, "smtpPassword", ""));
    }

    /** Macro-alert breach thresholds. */
    public MacroThresholds macroAlertThresholds()
    {
        XMLData node = analyserNode_ == null ? null : analyserNode_.getDocumentPart("MacroAlertThresholds", false);
        MacroThresholds thresholds;
        if (node == null)
        {
            thresholds = new MacroThresholds(10.0, 0.5, 1.0, 3.0, 1.0);
        }
        else
        {
            thresholds = new MacroThresholds(
                    node.getAttributeDoubleValue("vixInPct", 10.0),
                    node.getAttributeDoubleValue("dxyInPct", 0.5),
                    node.getAttributeDoubleValue("sp500InPct", 1.0),
                    node.getAttributeDoubleValue("us10yInPct", 3.0),
                    node.getAttributeDoubleValue("goldInPct", 1.0));
        }
        return thresholds;
    }

    // == Trader-owned: paper trading strategy (FSTrader) =======================

    /** Minimum impact tier a directional call must reach to open a position ({@code high} by default). */
    public String tradeEntryImpactTier()
    {
        return attr(traderNode_, "entryImpactTier", "high");
    }

    /** Margin committed per trade in USD; exposure is this times {@link #tradeLeverage()}. */
    public double tradeNotionalInUsd()
    {
        return doubleAttr(traderNode_, "notionalInUsd", 1000.0);
    }

    public double tradeLeverage()
    {
        return doubleAttr(traderNode_, "leverage", 2.0);
    }

    /** Initial stop distance from entry, in percent (the trailing stop ratchets from here). */
    public double tradeStopLossInPct()
    {
        return doubleAttr(traderNode_, "stopLossInPct", 1.0);
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

    /** Macro indicator breach thresholds (percent). */
    public record MacroThresholds(double vixInPct, double dxyInPct, double sp500InPct,
                                  double us10yInPct, double goldInPct)
    {
    }
}
