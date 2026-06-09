package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link PreTrend} against Python {@code analyse._compute_pre_trend}: regression-based
 * rising/falling/flat/volatile classification, the slope/R²/range figures, and the &lt;3-bar case.
 */
public class PreTrend_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void monotoneUpIsRising()
    {
        ObjectNode t = PreTrend.of(bars(100, 101, 102, 103, 104));
        assertEquals("rising", t.path("label").asText());
        assertEquals(4.0, t.path("slope_pct").asDouble(), EPS);  // slope 1/bar * 4 bars / 100 * 100
        assertEquals(1.0, t.path("r_squared").asDouble(), EPS);  // perfect line
        assertEquals(4.0, t.path("range_pct").asDouble(), EPS);
    }

    @Test
    public void monotoneDownIsFalling()
    {
        ObjectNode t = PreTrend.of(bars(104, 103, 102, 101, 100));
        assertEquals("falling", t.path("label").asText());
        assertEquals(1.0, t.path("r_squared").asDouble(), EPS);     // perfect line
        assertTrue("negative slope past threshold", t.path("slope_pct").asDouble() < -0.3);
    }

    @Test
    public void constantIsFlat()
    {
        assertEquals("flat", PreTrend.of(bars(100, 100, 100, 100)).path("label").asText());
    }

    @Test
    public void oscillatingIsVolatile()
    {
        // ~5% range but no linear fit (R² ~ 0) -> volatile
        ObjectNode t = PreTrend.of(bars(100, 105, 100, 105, 100));
        assertEquals("volatile", t.path("label").asText());
        assertTrue(t.path("range_pct").asDouble() > 1.0);
        assertTrue(t.path("r_squared").asDouble() <= 0.5);
    }

    @Test
    public void tooFewBarsHasNullLabel()
    {
        assertTrue(PreTrend.of(bars(100, 101)).path("label").isNull());
        assertTrue(PreTrend.of(Json.newArray()).path("label").isNull());
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
