package com.finsent.collect;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Macro risk-off indicator fetcher (ports Python {@code macro.py}). Reads the latest price and
 * daily change for a fixed set of market indicators from the Yahoo Finance chart API and builds
 * the macro snapshot stored per interval by the collector:
 * <pre>{ "fetched_at": ISO, "yahoo": { NAME: {price, prev_close, change_pct, yield_pct} } }</pre>
 * This module only fetches the indicators. The analyser never sees the raw numbers in Claude's
 * prompt &mdash; it feeds Claude only the mechanical regime label computed over this snapshot by
 * {@code com.finsent.analyse.signal.MacroSignals}.
 */
public final class MacroFetcher
{
    private static final String NAME = "MacroFetcher";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Indicator name &rarr; Yahoo ticker symbol. Iteration order is the canonical indicator order. */
    private static final Map<String, String> TICKERS = tickers();
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Mozilla/5.0");

    private final String chartBaseUrl_;

    public MacroFetcher(String yahooChartBaseUrl)
    {
        chartBaseUrl_ = yahooChartBaseUrl;
    }

    /**
     * Fetch all macro indicators and return the snapshot. The {@code yahoo} map holds whichever
     * indicators were retrieved (empty when none). Never returns null &mdash; matching the Python
     * {@code get_macro_snapshot}.
     */
    public ObjectNode fetchSnapshot()
    {
        ObjectNode yahoo = fetchIndicators();
        ObjectNode snapshot = Json.newObject();
        snapshot.put("fetched_at", Times.nowUtcIso());
        snapshot.set("yahoo", yahoo);
        return snapshot;
    }

    private ObjectNode fetchIndicators()
    {
        ObjectNode yahoo = Json.newObject();
        for (Map.Entry<String, String> ticker : TICKERS.entrySet())
        {
            ObjectNode entry = fetchIndicator(ticker.getKey(), ticker.getValue());
            if (entry != null)
            {
                yahoo.set(ticker.getKey(), entry);
            }
        }
        if (yahoo.size() > 0)
        {
            GlobalSystem.info().writes(NAME, "Fetched " + yahoo.size() + "/" + TICKERS.size() + " Yahoo indicators");
        }
        else
        {
            GlobalSystem.warning().writes(NAME, "No Yahoo indicators fetched");
        }
        return yahoo;
    }

    private ObjectNode fetchIndicator(String name, String symbol)
    {
        ObjectNode entry = null;
        try
        {
            String url = chartBaseUrl_ + "/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
            String body = Http.get(url, Map.of("interval", "1d", "range", "5d"), HEADERS, TIMEOUT);
            double[] pricePrev = parsePriceAndPrev(body);
            if (pricePrev != null && pricePrev[1] != 0.0)
            {
                entry = buildYahooEntry(name, pricePrev[0], pricePrev[1]);
            }
            else
            {
                GlobalSystem.debug().writes(NAME, name + " (" + symbol + ") returned no data");
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.debug().writes(NAME, "Failed to fetch " + name + " (" + symbol + ")", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.debug().writes(NAME, "Interrupted fetching " + name + " (" + symbol + ")", interrupted);
        }
        return entry;
    }

    /**
     * Extract {@code [regularMarketPrice, previousClose]} from a Yahoo chart response, or null
     * when the payload carries no usable price. Prefers {@code previousClose} and falls back to
     * {@code chartPreviousClose} (the field yfinance's {@code fast_info} reads).
     */
    static double[] parsePriceAndPrev(String chartJson)
    {
        double[] result = null;
        try
        {
            JsonNode meta = Json.parse(chartJson).path("chart").path("result").path(0).path("meta");
            if (meta.has("regularMarketPrice"))
            {
                JsonNode prev = meta.hasNonNull("previousClose") ? meta.path("previousClose")
                        : meta.path("chartPreviousClose");
                if (prev.isNumber())
                {
                    result = new double[] { meta.path("regularMarketPrice").asDouble(), prev.asDouble() };
                }
            }
        }
        catch (JsonProcessingException badJson)
        {
            result = null;
        }
        return result;
    }

    /** Build one indicator entry; US10Y additionally carries its actual yield (Yahoo returns tenths of a percent). */
    static ObjectNode buildYahooEntry(String name, double price, double prevClose)
    {
        double changePct = (price - prevClose) / prevClose * 100.0;
        ObjectNode entry = Json.newObject();
        entry.put("price", Num.round(price, 4));
        entry.put("prev_close", Num.round(prevClose, 4));
        entry.put("change_pct", Num.round(changePct, 2));
        if (name.equals("US10Y"))
        {
            entry.put("yield_pct", Num.round(price / 10.0, 3));
        }
        else
        {
            entry.putNull("yield_pct");
        }
        return entry;
    }

    private static Map<String, String> tickers()
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("DXY", "DX-Y.NYB");     // US Dollar Index
        map.put("VIX", "^VIX");          // CBOE Volatility Index
        map.put("US10Y", "^TNX");        // US 10-Year Treasury Yield (value / 10 = %)
        map.put("SP500", "^GSPC");       // S&P 500
        map.put("Gold", "GC=F");         // Gold futures
        return Collections.unmodifiableMap(map);
    }
}
