package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link MacroTrend} against Python {@code analyse._compute_macro_trend}: per-indicator
 * streak/direction/cumulative-delta and the risk-off/on/mixed/flat rollup + sustained flag, over a
 * supplied oldest-first snapshot list.
 */
public class MacroTrend_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void sustainedRisingVixIsSustainedRiskOff()
    {
        // VIX change_pct rising every window: deltas +1 x4 -> streak 4 (>=4 sustained), rising = risk_off.
        ObjectNode t = MacroTrend.of(List.of(vix(1), vix(2), vix(3), vix(4), vix(5)));

        assertEquals("risk_off", t.path("net_trend").asText());
        assertTrue(t.path("sustained").asBoolean());
        assertEquals(4, t.path("lookback_windows").asInt());
        ObjectNode v = (ObjectNode) t.path("indicators").path("VIX");
        assertEquals("rising", v.path("direction").asText());
        assertEquals(4, v.path("streak").asInt());
        assertEquals(4.0, v.path("cumulative_delta").asDouble(), EPS); // 5 - 1
    }

    @Test
    public void fallingVixIsRiskOnNotSustained()
    {
        // VIX falling: deltas -1 x3 -> streak 3 (<4), falling != risk-off-dir(rising) -> risk_on.
        ObjectNode t = MacroTrend.of(List.of(vix(5), vix(4), vix(3), vix(2)));

        assertEquals("risk_on", t.path("net_trend").asText());
        assertFalse(t.path("sustained").asBoolean());
        assertEquals("falling", t.path("indicators").path("VIX").path("direction").asText());
        assertEquals(3, t.path("indicators").path("VIX").path("streak").asInt());
    }

    @Test
    public void tooFewSnapshotsIsFlat()
    {
        ObjectNode t = MacroTrend.of(List.of(vix(1), vix(2)));
        assertEquals("flat", t.path("net_trend").asText());
        assertEquals(0, t.path("lookback_windows").asInt());
        assertEquals(0, t.path("indicators").size());
    }

    /** A macro snapshot carrying only a VIX change_pct (other indicators absent → flat). */
    private static ObjectNode vix(double changePct)
    {
        ObjectNode indicator = Json.newObject();
        indicator.put("change_pct", changePct);
        ObjectNode yahoo = Json.newObject();
        yahoo.set("VIX", indicator);
        ObjectNode snap = Json.newObject();
        snap.set("yahoo", yahoo);
        return snap;
    }
}
