package com.finsent.trade;

/**
 * Pure trailing-stop math, side-aware and stateless. The stop starts a fixed percent adverse to the
 * entry and then ratchets toward the trade as price moves favorably, never backward &mdash; so as
 * price advances past roughly {@code trailInPct} the stop crosses the entry (breakeven) and beyond
 * that it locks in profit. Percentages are whole-number percent ({@code 1.0} = 1%). A {@code LONG} is the
 * mirror of a {@code SHORT}: the long stop sits below price and only rises; the short stop sits above
 * price and only falls.
 */
public final class TrailingStop
{
    private TrailingStop()
    {
    }

    /** The initial stop: {@code stopInPct} below the entry for a long, above it for a short. */
    public static double initialStop(Side side, double entryPrice, double stopInPct)
    {
        double frac = stopInPct / 100.0;
        return side.isLong() ? entryPrice * (1.0 - frac) : entryPrice * (1.0 + frac);
    }

    /** The best (most favorable) price seen so far: the running max for a long, min for a short. */
    public static double bestSoFar(Side side, double bestPrice, double price)
    {
        return side.isLong() ? Math.max(bestPrice, price) : Math.min(bestPrice, price);
    }

    /**
     * The ratcheted stop: {@code trailInPct} behind the best price, but never moved against the trade
     * &mdash; for a long the higher of the current stop and {@code bestPrice*(1-trail)}, for a short
     * the lower of the current stop and {@code bestPrice*(1+trail)}.
     */
    public static double trail(Side side, double currentStop, double bestPrice, double trailInPct)
    {
        double frac = trailInPct / 100.0;
        double candidate = side.isLong() ? bestPrice * (1.0 - frac) : bestPrice * (1.0 + frac);
        return side.isLong() ? Math.max(currentStop, candidate) : Math.min(currentStop, candidate);
    }

    /** Whether {@code price} has hit the stop: at or below it for a long, at or above it for a short. */
    public static boolean breached(Side side, double price, double stop)
    {
        return side.isLong() ? price <= stop : price >= stop;
    }
}
