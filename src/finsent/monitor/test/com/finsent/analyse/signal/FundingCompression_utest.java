package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link FundingCompression}: positive funding draining into a flat tape past the threshold flags
 * as compressing, while a non-flat tape, a funding rise, a non-positive prior, or a sub-threshold fall do not.
 */
public class FundingCompression_utest
{
    private static final double DROP_40 = 40.0;

    @Test
    public void positiveFundingDrainingIntoFlatTapeCompresses()
    {
        // 3.0e-5 -> 1.0e-5 is a 66.7% relative fall, into a flat tape -> compressing.
        FundingCompression.Compression c = FundingCompression.of(snapshot(1.0e-5), snapshot(3.0e-5), true, DROP_40);
        assertTrue(c.compressing());
        assertEquals(66.67, c.fundingDropPct(), 0.01);
    }

    @Test
    public void notCompressingWhenPriceNotFlat()
    {
        assertFalse(FundingCompression.of(snapshot(1.0e-5), snapshot(3.0e-5), false, DROP_40).compressing());
    }

    @Test
    public void notCompressingWhenFundingRose()
    {
        assertFalse(FundingCompression.of(snapshot(4.0e-5), snapshot(3.0e-5), true, DROP_40).compressing());
    }

    @Test
    public void notCompressingWhenPriorNonPositive()
    {
        assertFalse(FundingCompression.of(snapshot(-1.0e-5), snapshot(0.0), true, DROP_40).compressing());
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
