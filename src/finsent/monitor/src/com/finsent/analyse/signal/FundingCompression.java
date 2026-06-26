package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Num;

/**
 * The FastMove pre-move early-warning: perpetual funding <b>draining</b> (leverage conviction quietly
 * bleeding) while price is still flat -- the precursor that, on the 2026-06-24 unwind, fell monotonically
 * for ~80 minutes before the price break. Symmetric: positive funding draining = long conviction bleeding
 * -> {@code bearish}-primed (longs giving up before a drop); negative funding draining = short conviction
 * bleeding -> {@code bullish}-primed (shorts covering before a squeeze). Pure: compares the magnitude of
 * the current funding rate to a prior one and the concurrent price state. It flags only when funding's
 * MAGNITUDE has fallen by at least {@code dropThresholdPct} (relative) into a flat tape; informational
 * (never trades), so it is deliberately conservative about a zero prior where a relative drop is meaningless.
 */
public final class FundingCompression
{
    private FundingCompression()
    {
    }

    /**
     * @param current          the latest funding snapshot ({@code funding_rate} required).
     * @param prior            the funding snapshot ~{@code windowMin} back.
     * @param priceFlat        whether the medium-window price trend is flat (the move has not started).
     * @param dropThresholdPct the minimum relative fall in funding magnitude (percent) that counts as compressing.
     */
    public static Compression of(ObjectNode current, ObjectNode prior, boolean priceFlat, double dropThresholdPct)
    {
        Compression result = Compression.NONE;
        if (priceFlat && current != null && prior != null
                && current.path("funding_rate").isNumber() && prior.path("funding_rate").isNumber())
        {
            double now = current.path("funding_rate").asDouble();
            double then = prior.path("funding_rate").asDouble();
            // A genuine DRAIN is the crowded side bleeding while still on its side (or fully neutralised to
            // zero) -- not a sign FLIP, which is a regime change, not compression. {@code now * then >= 0}
            // keeps same-sign and the drain-to-zero case while rejecting a cross-zero whipsaw.
            if (then != 0.0 && now * then >= 0.0 && Math.abs(now) < Math.abs(then))
            {
                double dropPct = (Math.abs(then) - Math.abs(now)) / Math.abs(then) * 100.0;
                if (dropPct >= dropThresholdPct)
                {
                    // The crowded side that is bleeding is the one that gets run over: positive funding
                    // (crowded longs) draining -> primed DOWN; negative funding (crowded shorts) -> primed UP.
                    result = new Compression(true, then > 0.0 ? "bearish" : "bullish", Num.round(dropPct, 2));
                }
            }
        }
        return result;
    }

    /** Whether funding is compressing into a flat tape, which break it primes, and the relative magnitude fall. */
    public record Compression(boolean compressing, String primedDirection, double fundingDropPct)
    {
        public static final Compression NONE = new Compression(false, "", 0.0);
    }
}
