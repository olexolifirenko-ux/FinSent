package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Num;

/**
 * The soft FastMove early-warning: positive funding <b>draining</b> (long conviction quietly bleeding)
 * while price is still flat -- the precursor that, on the 2026-06-24 unwind, fell monotonically for
 * ~80 minutes before the price break. Pure: compares the current funding rate to a prior one and the
 * concurrent price state. It only flags when funding was positive and has fallen by at least
 * {@code dropThresholdPct} (relative) into a flat tape; it is informational (never trades), so it is
 * deliberately conservative about near-zero/negative priors where a relative drop is meaningless.
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
     * @param dropThresholdPct the minimum relative fall in funding (percent) that counts as compressing.
     */
    public static Compression of(ObjectNode current, ObjectNode prior, boolean priceFlat, double dropThresholdPct)
    {
        Compression result = Compression.NONE;
        if (priceFlat && current != null && prior != null
                && current.path("funding_rate").isNumber() && prior.path("funding_rate").isNumber())
        {
            double now = current.path("funding_rate").asDouble();
            double then = prior.path("funding_rate").asDouble();
            if (then > 0.0 && now < then)
            {
                double dropPct = (then - now) / then * 100.0;
                if (dropPct >= dropThresholdPct)
                {
                    result = new Compression(true, Num.round(dropPct, 2));
                }
            }
        }
        return result;
    }

    /** Whether funding is compressing into a flat tape, and by how much (relative percent fall). */
    public record Compression(boolean compressing, double fundingDropPct)
    {
        public static final Compression NONE = new Compression(false, 0.0);
    }
}
