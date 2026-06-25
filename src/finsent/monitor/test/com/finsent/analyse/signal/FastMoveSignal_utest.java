package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.FastMoveWindow;
import com.finsent.core.Json;

/**
 * Verifies {@link FastMoveSignal}: a clean enough endpoint move fires directionally, the r2 floor rejects
 * a choppy move of the same magnitude, a flat/too-small tape does not fire, and the strongest qualifying
 * window wins. The magnitude/r2 numbers mirror the worked examples used to design the detector.
 */
public class FastMoveSignal_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void cleanDropFiresBearish()
    {
        // -150/bar, perfectly linear: -1.0% over the strip, r2 = 1.0.
        FastMoveSignal.Fire fire = FastMoveSignal.evaluate(bars(60000, 59850, 59700, 59550, 59400),
                List.of(new FastMoveWindow(5, 1.0, 0.5)));
        assertTrue(fire.fired());
        assertEquals("bearish", fire.direction());
        assertEquals(-1.0, fire.magnitudePct(), EPS);
        assertEquals(1.0, fire.r2(), EPS);
        assertEquals(5, fire.spanMinutes());
    }

    @Test
    public void choppyMoveOfSameMagnitudeRejectedByR2Floor()
    {
        // Same -1.0% endpoints as the clean case, but zigzag -> r2 ~ 0.27, below the floor.
        FastMoveSignal.Fire fire = FastMoveSignal.evaluate(bars(60000, 59400, 60100, 59450, 59400),
                List.of(new FastMoveWindow(5, 1.0, 0.5)));
        assertFalse("a -1% drift that is not clean must not fire", fire.fired());
    }

    @Test
    public void flatTapeDoesNotFire()
    {
        FastMoveSignal.Fire fire = FastMoveSignal.evaluate(bars(60000, 60010, 59990, 60005, 60000),
                List.of(new FastMoveWindow(5, 1.0, 0.5)));
        assertFalse(fire.fired());
    }

    @Test
    public void cleanRiseFiresBullish()
    {
        FastMoveSignal.Fire fire = FastMoveSignal.evaluate(bars(100, 101, 102, 103, 104),
                List.of(new FastMoveWindow(5, 1.0, 0.5)));
        assertTrue(fire.fired());
        assertEquals("bullish", fire.direction());
        assertEquals(4.0, fire.magnitudePct(), EPS);
    }

    @Test
    public void strongestQualifyingWindowWins()
    {
        // On a steady drop both windows fire; the longer (5-bar) span has the larger magnitude, so it wins.
        FastMoveSignal.Fire fire = FastMoveSignal.evaluate(bars(60000, 59850, 59700, 59550, 59400),
                List.of(new FastMoveWindow(3, 0.3, 0.5), new FastMoveWindow(5, 0.3, 0.5)));
        assertTrue(fire.fired());
        assertEquals(5, fire.spanMinutes());
    }

    @Test
    public void tooFewBarsDoesNotFire()
    {
        assertFalse(FastMoveSignal.evaluate(bars(60000, 59000), List.of(new FastMoveWindow(5, 1.0, 0.5))).fired());
    }

    private static ArrayNode bars(double... closes)
    {
        ArrayNode array = Json.newArray();
        for (double c : closes)
        {
            ObjectNode bar = Json.newObject();
            bar.put("c", c);
            array.add(bar);
        }
        return array;
    }
}
