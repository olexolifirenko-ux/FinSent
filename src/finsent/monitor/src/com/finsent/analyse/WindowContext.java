package com.finsent.analyse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.claude.PromptBuilder;
import com.finsent.analyse.signal.EconEventSignals;
import com.finsent.analyse.signal.FundingSignals;
import com.finsent.analyse.signal.MacroSignals;
import com.finsent.analyse.signal.OptionsSignals;
import com.finsent.collect.FSCollector;
import com.finsent.core.Json;
import com.finsent.core.Times;

/**
 * Reads the collector-owned context an analysis window needs (ports the Python {@code _load_*}
 * helpers): the mechanical regime + options + funding signals, the per-article pre-publication OHLC
 * windows, and the window's BTC price. Pure with respect to the collector's in-memory registries
 * &mdash; it only reads them.
 */
public final class WindowContext
{
    private static final int OPTIONS_DELTA_WINDOWS = 3;
    private static final int FUNDING_LOOKBACK = 6; // windows (~1h) for the OI build/unwind + price move

    private WindowContext()
    {
    }

    /**
     * Mechanical options signal for the window (current snapshot vs three windows back), or
     * {@code null} when options are disabled or no current snapshot exists.
     */
    public static ObjectNode optionsSignal(FSCollector collector, String day, String key, int windowMinutes)
    {
        ObjectNode signal = null;
        if (collector.config().optionsEnabled())
        {
            ObjectNode current = collector.options().get(day, key);
            if (!current.isEmpty())
            {
                signal = OptionsSignals.signal(current, OptionsSignals.delta(current, previousOptions(collector, day, key, windowMinutes)));
            }
        }
        return signal;
    }

    /**
     * Mechanical perpetual-positioning signal for the window (funding crowding fused with the ~1h open-
     * interest change and the ~1h BTC move), or {@code null} when funding is disabled or no snapshot
     * exists. Reads the funding snapshot {@value #FUNDING_LOOKBACK} windows back for the OI delta and
     * the price-context price then vs now for the move (both tolerated absent, e.g. early after startup).
     */
    public static ObjectNode fundingSignal(FSCollector collector, String day, String key, int windowMinutes)
    {
        ObjectNode signal = null;
        if (collector.config().fundingEnabled())
        {
            Intervals.Shift back = Intervals.back(key, FUNDING_LOOKBACK, windowMinutes);
            String backDay = back.dayOffset() == 0 ? day : Intervals.minusDays(day, back.dayOffset());
            ObjectNode priorFunding = collector.funding().get(backDay, back.key());
            signal = FundingSignals.signal(collector.funding().get(day, key), priorFunding,
                    priceChangePct(collector, day, key, backDay, back.key()));
        }
        return signal;
    }

    /** The ~1h BTC percent move from the price-context snapshots ({@code backDay/backKey} -> {@code day/key}), or null. */
    private static Double priceChangePct(FSCollector collector, String day, String key, String backDay, String backKey)
    {
        double now = collector.price().get(day, key).path("btc_price").asDouble(0.0);
        double then = collector.price().get(backDay, backKey).path("btc_price").asDouble(0.0);
        return now > 0.0 && then > 0.0 ? (now - then) / then * 100.0 : null;
    }

    /**
     * The window's market context bundled for analysis: the mechanical signals an analysis record keeps
     * ({@code regime} / {@code options} / {@code funding} / {@code priceContext}) plus the formatted
     * {@code {market_signals}} {@code block} the Claude prompts interpolate. The macro trend is folded
     * into {@code block} (no caller needs it separately). {@code options} / {@code funding} /
     * {@code priceContext} may be empty/null when the signal is disabled or absent.
     */
    public record MarketContext(ObjectNode regime, ObjectNode options, ObjectNode funding,
                                ObjectNode priceContext, String block)
    {
        /** BTC price anchor for outcome scoring (#6): the price-context {@code btc_price}, or null when absent. */
        public Double anchor()
        {
            return priceContext != null && priceContext.path("btc_price").isNumber()
                    ? priceContext.path("btc_price").asDouble() : null;
        }
    }

    /**
     * Assemble the window's {@link MarketContext} in one read of the collector: the mechanical regime
     * (carried for the stored record/notifications, not shown to the model), the options + funding signals,
     * the price context, and the formatted {@code {market_signals}} block. The single place the Claude paths
     * (news deep analysis, econ) build the context they judge against -- so they stay consistent and read
     * the collector once. The block intentionally omits macro regime/trend (see {@link PromptBuilder#marketSignals}).
     */
    public static MarketContext marketContext(FSCollector collector, String day, String key, int windowMinutes)
    {
        ObjectNode regime = MacroSignals.regime(collector.macro().get(day, key));
        ObjectNode options = optionsSignal(collector, day, key, windowMinutes);
        ObjectNode funding = fundingSignal(collector, day, key, windowMinutes);
        ObjectNode price = collector.price().get(day, key);
        // regime is still carried in the returned context (for the stored record/notifications), but it is
        // deliberately NOT passed into the prompt block -- the deep pass must not be tinted by macro mood.
        String block = PromptBuilder.marketSignals(options, funding, price);
        return new MarketContext(regime, options, funding, price, block);
    }

    /**
     * Mechanical scheduled-event signal (#21) for a resolved release, derived at analysis time from the
     * collector's raw {@code econ_actuals} record (consensus + fetched actual), or {@code null} when the
     * event is not resolved for {@code day}. Mirrors the other signals: the collector stores raw inputs,
     * the surprise/direction is computed here by {@link EconEventSignals}.
     */
    public static ObjectNode econSignal(FSCollector collector, String day, String eventName)
    {
        ObjectNode resolved = collector.econ().get(day, eventName);
        ObjectNode signal = null;
        if (resolved.has("actual") && resolved.has("consensus"))
        {
            signal = EconEventSignals.signal(resolved.path("event").asText(eventName),
                    resolved.path("unit").asText("%"), resolved.path("consensus").asDouble(),
                    resolved.path("actual").asDouble(), resolved.path("hot_direction").asText("bearish"),
                    resolved.path("inline_band").asDouble(0.0), resolved.path("high_band").asDouble(Double.MAX_VALUE));
        }
        return signal;
    }

    /** The macro snapshot one window before {@code key} (crossing to the previous calendar day at 00:00). */
    public static ObjectNode previousMacro(FSCollector collector, String day, String key, int windowMinutes)
    {
        Intervals.Shift prev = Intervals.back(key, 1, windowMinutes);
        String prevDay = prev.dayOffset() == 0 ? day : Intervals.minusDays(day, prev.dayOffset());
        return collector.macro().get(prevDay, prev.key());
    }

    /**
     * Per-article pre-publication OHLC windows: each resonant article mapped to the stored bars in
     * {@code [publishedAt - ohlcWindowMinutes, publishedAt]} (ports {@code _load_article_ohlc}).
     */
    public static Map<Integer, ArrayNode> articleOhlc(FSCollector collector, List<ObjectNode> articles,
                                                      int ohlcWindowMinutes)
    {
        Map<Integer, ArrayNode> result = new LinkedHashMap<>();
        for (ObjectNode article : articles)
        {
            Instant publishedAt = parseInstant(article.path("publishedAt").asText(""));
            if (publishedAt != null)
            {
                ArrayNode bars = collector.ohlc().barsInRange(
                        Times.formatUtcIso(publishedAt.minusSeconds(ohlcWindowMinutes * 60L)),
                        Times.formatUtcIso(publishedAt));
                if (bars.size() > 0)
                {
                    result.put(article.path("id").asInt(), bars);
                }
            }
        }
        return result;
    }

    /** The window's BTC price: the close of the newest bar across all article windows, or null. */
    public static Double btcPrice(Map<Integer, ArrayNode> articleOhlc)
    {
        String latestTs = null;
        double price = 0.0;
        boolean found = false;
        for (ArrayNode bars : articleOhlc.values())
        {
            for (JsonNode bar : bars)
            {
                String ts = bar.path("ts").asText("");
                if (latestTs == null || ts.compareTo(latestTs) > 0)
                {
                    latestTs = ts;
                    price = bar.path("c").asDouble();
                    found = true;
                }
            }
        }
        return found ? price : null;
    }


    /** Options snapshot three windows back on the same day, or {@code null} when that wraps past midnight. */
    private static ObjectNode previousOptions(FSCollector collector, String day, String key, int windowMinutes)
    {
        Intervals.Shift prev = Intervals.back(key, OPTIONS_DELTA_WINDOWS, windowMinutes);
        ObjectNode result = null;
        if (prev.dayOffset() == 0)
        {
            ObjectNode snap = collector.options().get(day, prev.key());
            result = snap.isEmpty() ? null : snap;
        }
        return result;
    }

    private static Instant parseInstant(String iso)
    {
        Instant result = null;
        if (!iso.isEmpty())
        {
            try
            {
                result = Times.parseIso(iso);
            }
            catch (DateTimeParseException unparseable)
            {
                result = null;
            }
        }
        return result;
    }
}
