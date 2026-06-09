package com.finsent.collect;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.Test;

import com.finsent.core.Json;
import com.finsent.core.Times;

/**
 * Verifies {@link OhlcFetcher} against Python {@code collect._fetch_btc_candles}: kline-to-bar
 * normalization (open-time as ts, prices 2dp, volume 4dp) and the candle-count limit math.
 */
public class OhlcFetcher_utest
{
    private static final double EPS = 1e-9;
    private static final long OPEN_TIME_MS = 1748692860000L;

    @Test
    public void normalizeKlinesBuildsRoundedBars() throws Exception
    {
        // Binance kline: [openTime, open, high, low, close, volume, closeTime, ...] (prices are strings).
        String klines = "[[" + OPEN_TIME_MS + ",\"73925.336\",\"73950.00\",\"73900.10\",\"73940.55\",\"12.34567\","
                + "1748692919999,\"912345.67\",100,\"6.1\",\"450000.0\",\"0\"]]";

        ArrayNode bars = OhlcFetcher.normalizeKlines(Json.parse(klines));

        assertEquals(1, bars.size());
        JsonNode bar = bars.get(0);
        assertEquals(Times.formatUtcIso(Instant.ofEpochMilli(OPEN_TIME_MS)), bar.path("ts").asText());
        assertEquals(73925.34, bar.path("o").asDouble(), EPS); // half-even round of 73925.336
        assertEquals(73950.00, bar.path("h").asDouble(), EPS);
        assertEquals(73900.10, bar.path("l").asDouble(), EPS);
        assertEquals(73940.55, bar.path("c").asDouble(), EPS);
        assertEquals(12.3457, bar.path("v").asDouble(), EPS); // 4dp
    }

    @Test
    public void normalizeKlinesToleratesNonArray() throws Exception
    {
        assertEquals(0, OhlcFetcher.normalizeKlines(Json.parse("{}")).size());
    }

    @Test
    public void klineLimitCountsBarsPlusMarginAndCaps()
    {
        // 60 minutes of 1m bars -> 60 + 2 margin.
        assertEquals(62, OhlcFetcher.klineLimit(60L * 60_000L, 1));
        // 1h of 5m bars -> 12 + 2.
        assertEquals(14, OhlcFetcher.klineLimit(60L * 60_000L, 5));
        // Huge range is capped at the Binance maximum.
        assertEquals(1000, OhlcFetcher.klineLimit(100_000L * 60_000L, 1));
    }
}
