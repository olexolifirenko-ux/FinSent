package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Verifies {@link RegimeSignal} emits the {@code btc_regime} line ONLY for an EXTENDED multi-day tape
 * (deep drawdown from the 5d high AND near the 5d low), and is silent otherwise.
 */
public class RegimeSignal_utest
{
    @Test
    public void emitsLineWhenDeepDrawdownNearLow()
    {
        // -9.2% from the 5d high, range_pos 0.14 -> EXTENDED.
        String line = RegimeSignal.line(59000.0, 65000.0, 58000.0);
        assertEquals("btc_regime: -9.2% from 5d-high $65000, near the 5d low -- EXTENDED multi-day sell-off"
                + " (the broad risk-off is largely PRICED in)", line);
    }

    @Test
    public void silentWhenDrawdownShallow()
    {
        // -3.1% from the high -> not extended (needs <= -6%), even sitting near the low.
        assertEquals("", RegimeSignal.line(63000.0, 65000.0, 62500.0));
    }

    @Test
    public void silentWhenNotNearLow()
    {
        // -7.0% drawdown (deep enough) but range_pos 0.545 (mid range) -> not extended (needs <= 0.30).
        assertEquals("", RegimeSignal.line(60450.0, 65000.0, 55000.0));
    }

    @Test
    public void silentOnUnusableInputs()
    {
        assertEquals("", RegimeSignal.line(0.0, 65000.0, 58000.0));
        assertEquals("", RegimeSignal.line(59000.0, 0.0, 0.0));
    }
}
