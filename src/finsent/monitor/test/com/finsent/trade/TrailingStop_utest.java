package com.finsent.trade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the {@link TrailingStop} math for both sides: the initial adverse stop, the best-price
 * ratchet that only ever moves toward the trade, breakeven at {@code trailInPct} of favorable move,
 * and the breach test.
 */
public class TrailingStop_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void longInitialStopIsBelowEntry()
    {
        assertEquals(99.0, TrailingStop.initialStop(Side.LONG, 100.0, 1.0), EPS);
    }

    @Test
    public void shortInitialStopIsAboveEntry()
    {
        assertEquals(101.0, TrailingStop.initialStop(Side.SHORT, 100.0, 1.0), EPS);
    }

    @Test
    public void longStopRatchetsUpWithBestPriceButNeverDown()
    {
        double stop = TrailingStop.initialStop(Side.LONG, 100.0, 1.0); // 99
        double best = 100.0;

        best = TrailingStop.bestSoFar(Side.LONG, best, 105.0); // 105
        stop = TrailingStop.trail(Side.LONG, stop, best, 1.0); // 103.95
        assertEquals(103.95, stop, EPS);

        // A pullback to 102 must not lower the stop or the best.
        best = TrailingStop.bestSoFar(Side.LONG, best, 102.0); // still 105
        stop = TrailingStop.trail(Side.LONG, stop, best, 1.0); // still 103.95
        assertEquals(105.0, best, EPS);
        assertEquals(103.95, stop, EPS);
    }

    @Test
    public void longStopCrossesIntoProfitAsPriceRuns()
    {
        // A 1% trail behind a +1% high sits a hair under entry (100 * 1.01 * 0.99 = 99.99) ...
        double nearBreakeven = TrailingStop.trail(Side.LONG, 99.0, TrailingStop.bestSoFar(Side.LONG, 100.0, 101.0), 1.0);
        assertEquals(99.99, nearBreakeven, EPS);
        // ... and once price runs a little further the stop locks in profit (100 * 1.02 * 0.99 = 100.98).
        double inProfit = TrailingStop.trail(Side.LONG, 99.0, TrailingStop.bestSoFar(Side.LONG, 100.0, 102.0), 1.0);
        assertTrue("stop above entry guarantees a profitable exit", inProfit > 100.0);
    }

    @Test
    public void shortStopRatchetsDownWithBestPriceButNeverUp()
    {
        double stop = TrailingStop.initialStop(Side.SHORT, 100.0, 1.0); // 101
        double best = 100.0;

        best = TrailingStop.bestSoFar(Side.SHORT, best, 95.0); // 95
        stop = TrailingStop.trail(Side.SHORT, stop, best, 1.0); // 95.95
        assertEquals(95.95, stop, EPS);

        best = TrailingStop.bestSoFar(Side.SHORT, best, 97.0); // still 95
        stop = TrailingStop.trail(Side.SHORT, stop, best, 1.0); // still 95.95
        assertEquals(95.0, best, EPS);
        assertEquals(95.95, stop, EPS);
    }

    @Test
    public void breachTriggersAtOrThroughTheStop()
    {
        assertTrue(TrailingStop.breached(Side.LONG, 99.0, 99.0));
        assertTrue(TrailingStop.breached(Side.LONG, 98.5, 99.0));
        assertFalse(TrailingStop.breached(Side.LONG, 99.5, 99.0));

        assertTrue(TrailingStop.breached(Side.SHORT, 101.0, 101.0));
        assertTrue(TrailingStop.breached(Side.SHORT, 101.5, 101.0));
        assertFalse(TrailingStop.breached(Side.SHORT, 100.5, 101.0));
    }
}
