package com.finsent.feedback;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

/**
 * Verifies {@link FeedbackPriceCache}: a day's closes are fetched once and memoized (no per-prediction
 * Binance call), a new day triggers another batch fetch, and a minute missing from the day map falls
 * back to the single-point fetch.
 */
public class FeedbackPriceCache_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void batchesOneFetchPerDayAndMemoizes()
    {
        int[] dayCalls = {0};
        Function<Instant, Map<Long, Double>> dayCloses = anchor ->
        {
            dayCalls[0]++;
            Map<Long, Double> closes = new HashMap<>();
            closes.put(minute("2026-06-04T08:00:00Z"), 100.0);
            closes.put(minute("2026-06-04T08:05:00Z"), 105.0);
            return closes;
        };
        int[] pointCalls = {0};
        Function<Instant, Double> point = target -> { pointCalls[0]++; return 999.0; };
        FeedbackPriceCache cache = new FeedbackPriceCache(dayCloses, point);

        assertEquals(100.0, cache.priceAt(Instant.parse("2026-06-04T08:00:00Z")), EPS);
        assertEquals(105.0, cache.priceAt(Instant.parse("2026-06-04T08:05:00Z")), EPS);

        assertEquals("one batched fetch for the day", 1, dayCalls[0]);
        assertEquals("no point fallback for cached minutes", 0, pointCalls[0]);
    }

    @Test
    public void newDayTriggersAnotherBatchFetch()
    {
        int[] dayCalls = {0};
        Function<Instant, Map<Long, Double>> dayCloses = anchor ->
        {
            dayCalls[0]++;
            Map<Long, Double> closes = new HashMap<>();
            closes.put(minute(anchor), 50.0);
            return closes;
        };
        FeedbackPriceCache cache = new FeedbackPriceCache(dayCloses, target -> null);

        cache.priceAt(Instant.parse("2026-06-04T08:00:00Z"));
        cache.priceAt(Instant.parse("2026-06-05T08:00:00Z"));

        assertEquals("one fetch per distinct day", 2, dayCalls[0]);
    }

    @Test
    public void fallsBackToPointFetchOnAGap()
    {
        int[] pointCalls = {0};
        Function<Instant, Double> point = target -> { pointCalls[0]++; return 777.0; };
        // The day fetch returns no close for the target minute (a gap) -> fall back to the point fetch.
        FeedbackPriceCache cache = new FeedbackPriceCache(anchor -> new HashMap<>(), point);

        assertEquals(777.0, cache.priceAt(Instant.parse("2026-06-04T08:00:00Z")), EPS);
        assertEquals(1, pointCalls[0]);
    }

    private static long minute(String iso)
    {
        return minute(Instant.parse(iso));
    }

    private static long minute(Instant instant)
    {
        return instant.toEpochMilli() / 60_000L * 60_000L;
    }
}
