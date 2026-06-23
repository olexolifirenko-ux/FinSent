package com.finsent.feedback;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.finsent.core.Times;

/**
 * A day-batching, memoizing {@link OutcomeScorer.PriceSource} for the feedback scan: the first lookup
 * into a UTC day fetches that whole day's 1-minute closes once and caches them, collapsing the
 * O(predictions) per-target Binance calls into ~one fetch per distinct target-day. A minute with no
 * cached close (a kline gap, or a failed day fetch) falls back to a single-point fetch. Not thread-safe
 * &mdash; the feedback scan runs on one dedicated thread.
 */
public final class FeedbackPriceCache implements OutcomeScorer.PriceSource
{
    private static final long MINUTE_MS = 60_000L;

    private final Function<Instant, Map<Long, Double>> dayCloses_;
    private final Function<Instant, Double> pointFetch_;
    private final Map<String, Map<Long, Double>> byDay_ = new HashMap<>();

    public FeedbackPriceCache(Function<Instant, Map<Long, Double>> dayCloses, Function<Instant, Double> pointFetch)
    {
        dayCloses_ = dayCloses;
        pointFetch_ = pointFetch;
    }

    @Override
    public Double priceAt(Instant target)
    {
        String day = Times.dayOf(Times.formatUtcIso(target));
        Map<Long, Double> closes = byDay_.computeIfAbsent(day, ignored -> dayCloses_.apply(target));
        Double price = closes.get(target.toEpochMilli() / MINUTE_MS * MINUTE_MS);
        return price != null ? price : pointFetch_.apply(target);
    }
}
