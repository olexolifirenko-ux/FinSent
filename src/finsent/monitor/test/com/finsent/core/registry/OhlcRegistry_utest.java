package com.finsent.core.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link OhlcRegistry#extremes} computes the max-high / min-low across EVERY day in a multi-day
 * span -- including the middle days that {@link OhlcRegistry#barsInRange} (built for sub-day strips) skips
 * -- and respects the inclusive time bounds.
 */
public class OhlcRegistry_utest
{
    @Test
    public void extremesSpanEveryDayInRangeNotJustEndpoints()
    {
        OhlcRegistry registry = new OhlcRegistry();
        ArrayNode bars = Json.newArray();
        // 3 days; the HIGH (66000) and LOW (58000) both live on the MIDDLE day -- which barsInRange skips.
        bars.add(bar("2026-06-22T10:00:00Z", 64100, 63900));
        bars.add(bar("2026-06-23T12:00:00Z", 66000, 58000));
        bars.add(bar("2026-06-24T09:00:00Z", 60200, 59800));
        registry.merge(bars);

        ObjectNode extremes = registry.extremes("2026-06-22T00:00:00Z", "2026-06-24T23:59:00Z");

        assertEquals(66000.0, extremes.path("high").asDouble(), 1e-9);
        assertEquals(58000.0, extremes.path("low").asDouble(), 1e-9);
    }

    @Test
    public void extremesRespectInclusiveTimeBounds()
    {
        OhlcRegistry registry = new OhlcRegistry();
        ArrayNode bars = Json.newArray();
        bars.add(bar("2026-06-23T08:00:00Z", 70000, 63000)); // before the from-bound -> excluded
        bars.add(bar("2026-06-23T12:00:00Z", 61000, 59000)); // in range
        registry.merge(bars);

        ObjectNode extremes = registry.extremes("2026-06-23T10:00:00Z", "2026-06-23T14:00:00Z");

        assertEquals(61000.0, extremes.path("high").asDouble(), 1e-9);
        assertEquals(59000.0, extremes.path("low").asDouble(), 1e-9);
    }

    @Test
    public void extremesEmptyWhenNoBarsInRange()
    {
        assertFalse(new OhlcRegistry().extremes("2026-06-22T00:00:00Z", "2026-06-24T00:00:00Z").has("high"));
    }

    private static ObjectNode bar(String ts, double high, double low)
    {
        ObjectNode bar = Json.newObject();
        bar.put("ts", ts);
        bar.put("o", high);
        bar.put("h", high);
        bar.put("l", low);
        bar.put("c", low);
        bar.put("v", 0.1);
        return bar;
    }
}
