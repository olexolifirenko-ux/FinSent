package com.finsent.collect;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Binance BTC market-data fetcher: OHLC klines (ports Python {@code collect._fetch_btc_candles}) plus
 * the 24h rolling ticker for price context and a lightweight last-price ticker for high-frequency
 * sampling. Klines are normalized into {@code {ts,o,h,l,c,v}} bars
 * (prices 2dp, volume 4dp, timestamp the bar's open time as UTC ISO) &mdash; the shape the
 * {@code OhlcRegistry} stores. This module only fetches; the boundary-strip and per-article time
 * ranges are computed by {@link OhlcWindows}, and the store/merge semantics live in the registry.
 */
public final class OhlcFetcher
{
    private static final String NAME = "OhlcFetcher";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String SYMBOL = "BTCUSDT";
    private static final int MAX_LIMIT = 1000;

    private final String klinesUrl_;
    private final String ticker24hUrl_;
    private final String tickerPriceUrl_;

    public OhlcFetcher(String binanceBaseUrl)
    {
        klinesUrl_ = binanceBaseUrl;
        // The tickers are sibling endpoints of klines on the same Binance base (no extra config).
        int lastSlash = binanceBaseUrl.lastIndexOf('/');
        String apiBase = lastSlash > 0 ? binanceBaseUrl.substring(0, lastSlash) : binanceBaseUrl;
        ticker24hUrl_ = apiBase + "/ticker/24hr";
        tickerPriceUrl_ = apiBase + "/ticker/price";
    }

    /**
     * Fetch the 24h rolling-window ticker for BTC (last price, 24h high/low, 24h percent change) in one
     * light call &mdash; the price-context backdrop without pulling a full day of candles. Null on failure.
     */
    public JsonNode fetch24hTicker()
    {
        JsonNode ticker = null;
        try
        {
            JsonNode parsed = Json.parse(Http.get(ticker24hUrl_, Map.of("symbol", SYMBOL), null, TIMEOUT));
            if (parsed.has("lastPrice"))
            {
                ticker = parsed;
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "24h ticker fetch failed", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching 24h ticker", interrupted);
        }
        return ticker;
    }

    /**
     * Fetch just BTC's last trade price in one lightweight call (the {@code /ticker/price} {@code {symbol,
     * price}} endpoint &mdash; far lighter than the 24h ticker) for high-frequency sampling. Null on failure.
     */
    public Double fetchLastPrice()
    {
        Double price = null;
        try
        {
            JsonNode parsed = Json.parse(Http.get(tickerPriceUrl_, Map.of("symbol", SYMBOL), null, TIMEOUT));
            if (parsed.has("price"))
            {
                price = parsed.path("price").asDouble();
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "last-price fetch failed", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching last price", interrupted);
        }
        return price;
    }

    /**
     * Fetch candles covering {@code [startMs, endMs]} at {@code barSize} (e.g. {@code "1m"}),
     * normalized and sorted ascending (Binance returns them ordered). Returns an empty array on
     * failure, matching the Python fetcher.
     */
    public ArrayNode fetchCandles(long startMs, long endMs, String barSize)
    {
        ArrayNode bars = Json.newArray();
        int limit = klineLimit(endMs - startMs, Times.intervalMinutes(barSize));
        try
        {
            String body = Http.get(klinesUrl_, Map.of(
                    "symbol", SYMBOL, "interval", barSize,
                    "startTime", String.valueOf(startMs), "endTime", String.valueOf(endMs),
                    "limit", String.valueOf(limit)), null, TIMEOUT);
            bars = normalizeKlines(Json.parse(body));
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "OHLC fetch failed", fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching OHLC", interrupted);
        }
        return bars;
    }

    /** Normalize a Binance klines array into {@code {ts,o,h,l,c,v}} bars (open-time as the ts). */
    static ArrayNode normalizeKlines(JsonNode klines)
    {
        ArrayNode bars = Json.newArray();
        if (klines.isArray())
        {
            for (JsonNode kline : klines)
            {
                ObjectNode bar = Json.newObject();
                bar.put("ts", Times.formatUtcIso(Instant.ofEpochMilli(kline.path(0).asLong())));
                bar.put("o", Num.round(kline.path(1).asDouble(), 2));
                bar.put("h", Num.round(kline.path(2).asDouble(), 2));
                bar.put("l", Num.round(kline.path(3).asDouble(), 2));
                bar.put("c", Num.round(kline.path(4).asDouble(), 2));
                bar.put("v", Num.round(kline.path(5).asDouble(), 4));
                bars.add(bar);
            }
        }
        return bars;
    }

    /** Candle count for a range: one per bar interval plus a 2-bar margin, capped at the API max. */
    static int klineLimit(long rangeMs, int barMinutes)
    {
        long barMs = (long) barMinutes * 60_000L;
        int limit = (int) (rangeMs / barMs) + 2;
        return Math.min(limit, MAX_LIMIT);
    }
}
