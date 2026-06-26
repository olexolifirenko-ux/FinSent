package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link FundingCompression}: funding magnitude draining into a flat tape past the threshold flags
 * as compressing -- positive funding draining primes BEARISH, negative funding draining primes BULLISH --
 * while a non-flat tape, a magnitude rise, a zero prior, or a sub-threshold fall do not.
 */
public class FundingCompression_utest
{
    private static final double DROP_40 = 40.0;

    @Test
    public void positiveFundingDrainingIntoFlatTapePrimesBearish()
    {
        // +3.0e-5 -> +1.0e-5 is a 66.7% relative fall (longs bleeding) into a flat tape -> bearish-primed.
        FundingCompression.Compression c = FundingCompression.of(snapshot(1.0e-5), snapshot(3.0e-5), true, DROP_40);
        assertTrue(c.compressing());
        assertEquals("bearish", c.primedDirection());
        assertEquals(66.67, c.fundingDropPct(), 0.01);
    }

    @Test
    public void negativeFundingDrainingIntoFlatTapePrimesBullish()
    {
        // -3.0e-5 -> -1.0e-5 is a 66.7% magnitude fall (shorts bleeding) into a flat tape -> bullish-primed.
        FundingCompression.Compression c = FundingCompression.of(snapshot(-1.0e-5), snapshot(-3.0e-5), true, DROP_40);
        assertTrue(c.compressing());
        assertEquals("bullish", c.primedDirection());
        assertEquals(66.67, c.fundingDropPct(), 0.01);
    }

    @Test
    public void notCompressingWhenPriceNotFlat()
    {
        assertFalse(FundingCompression.of(snapshot(1.0e-5), snapshot(3.0e-5), false, DROP_40).compressing());
    }

    @Test
    public void notCompressingWhenFundingMagnitudeRose()
    {
        assertFalse(FundingCompression.of(snapshot(4.0e-5), snapshot(3.0e-5), true, DROP_40).compressing());
    }

    @Test
    public void notCompressingWhenPriorIsZero()
    {
        // A zero prior makes a relative drop meaningless -> no signal regardless of the current rate.
        assertFalse(FundingCompression.of(snapshot(-1.0e-5), snapshot(0.0), true, DROP_40).compressing());
    }

    @Test
    public void notCompressingOnSignFlip()
    {
        // +3.0e-5 -> -2.0e-5 is a sign FLIP (a regime change), not a drain -- even though magnitude fell.
        assertFalse(FundingCompression.of(snapshot(-2.0e-5), snapshot(3.0e-5), true, DROP_40).compressing());
    }

    @Test
    public void belowThresholdDoesNotCompress()
    {
        // 3.0e-5 -> 2.5e-5 is only a ~16.7% fall, below the 40% threshold.
        assertFalse(FundingCompression.of(snapshot(2.5e-5), snapshot(3.0e-5), true, DROP_40).compressing());
    }

    private static ObjectNode snapshot(double fundingRate)
    {
        ObjectNode snapshot = Json.newObject();
        snapshot.put("funding_rate", fundingRate);
        return snapshot;
    }
}
