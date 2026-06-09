package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Verifies {@link Scenario} against Python {@code analyse._compute_scenario}: noise for
 * neutral/no-direction, fresh_&lt;dir&gt; for no/flat/volatile pre-trend, front_run when aligned,
 * reversal when opposed.
 */
public class Scenario_utest
{
    @Test
    public void neutralOrMissingDirectionIsNoise()
    {
        assertEquals("noise", Scenario.of("rising", null));
        assertEquals("noise", Scenario.of("rising", "neutral"));
        assertEquals("noise", Scenario.of(null, "neutral"));
    }

    @Test
    public void noPreTrendIsFresh()
    {
        assertEquals("fresh_bullish", Scenario.of(null, "bullish"));
        assertEquals("fresh_bearish", Scenario.of(null, "bearish"));
    }

    @Test
    public void alignedTrendIsFrontRun()
    {
        assertEquals("front_run", Scenario.of("rising", "bullish"));
        assertEquals("front_run", Scenario.of("falling", "bearish"));
    }

    @Test
    public void opposedTrendIsReversal()
    {
        assertEquals("reversal", Scenario.of("rising", "bearish"));
        assertEquals("reversal", Scenario.of("falling", "bullish"));
    }

    @Test
    public void flatOrVolatileIsFresh()
    {
        assertEquals("fresh_bullish", Scenario.of("flat", "bullish"));
        assertEquals("fresh_bearish", Scenario.of("volatile", "bearish"));
    }
}
