package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link OptionsSignals} against the behaviour of Python {@code options.compute_options_delta}
 * and {@code options.compute_options_signal}: delta now/prev/delta and percent-change figures, the
 * neutral default, near-term positioning with aggregate fallback, IV elevation, OI surge, DVOL trend,
 * and the signal-strength tally.
 */
public class OptionsSignals_utest
{
    private static final double EPS = 1e-9;

    @Test
    public void deltaWithoutPreviousHasNoDelta()
    {
        ObjectNode current = snapshot(0.8, 110.0, 1.1, 85.0, 40.0, 38.0);

        ObjectNode delta = OptionsSignals.delta(current, null);

        assertFalse(delta.path("has_delta").asBoolean());
        assertEquals(1, delta.size());
    }

    @Test
    public void deltaWithoutPreviousTreatsEmptyAsMissing()
    {
        ObjectNode current = snapshot(0.8, 110.0, 1.1, 85.0, 40.0, 38.0);

        ObjectNode delta = OptionsSignals.delta(current, Json.newObject());

        assertFalse(delta.path("has_delta").asBoolean());
    }

    @Test
    public void deltaComputesNowPrevAndDeltas()
    {
        ObjectNode current = snapshot(0.8, 110.0, 1.2, 85.0, 40.0, 38.0);
        ObjectNode previous = snapshot(0.6, 100.0, 1.0, 80.0, 36.0, 35.0);

        ObjectNode delta = OptionsSignals.delta(current, previous);

        assertTrue(delta.path("has_delta").asBoolean());
        assertEquals(0.8, delta.path("pc_ratio_now").asDouble(), EPS);
        assertEquals(0.6, delta.path("pc_ratio_prev").asDouble(), EPS);
        assertEquals(0.2, delta.path("pc_ratio_delta").asDouble(), EPS);
        assertEquals(10.0, delta.path("total_oi_change_pct").asDouble(), EPS);
        assertEquals(0.2, delta.path("near_pc_ratio_delta").asDouble(), EPS);
        assertEquals(5.0, delta.path("near_atm_iv_delta").asDouble(), EPS);
        assertEquals(4.0, delta.path("dvol_delta").asDouble(), EPS);
    }

    @Test
    public void deltaLeavesFieldsNullWhenMetricMissing()
    {
        ObjectNode current = Json.newObject();
        current.putNull("pc_ratio");
        current.put("total_oi", 0.0);
        ObjectNode previous = Json.newObject();
        previous.putNull("pc_ratio");
        previous.put("total_oi", 0.0);

        ObjectNode delta = OptionsSignals.delta(current, previous);

        assertTrue(delta.path("pc_ratio_delta").isNull());
        // prev total_oi is 0, so percent change is undefined.
        assertTrue(delta.path("total_oi_change_pct").isNull());
    }

    @Test
    public void signalWithoutSnapshotIsNeutral()
    {
        ObjectNode signal = OptionsSignals.signal(null, Json.newObject());

        assertEquals("neutral", signal.path("positioning").asText());
        assertFalse(signal.path("iv_elevated").asBoolean());
        assertFalse(signal.path("oi_surge").asBoolean());
        assertEquals("none", signal.path("signal_strength").asText());
        assertTrue(signal.path("near_pc_ratio").isNull());
        assertTrue(signal.path("dvol_trend").isNull());
        assertTrue(signal.path("near_atm_iv").isNull());
        assertEquals("unknown", signal.path("priced_in").asText());
    }

    @Test
    public void pricedInClassifiesComplacencyFromIvLevelAndTrend()
    {
        // Low IV (40 < 50), flat DVOL -> complacent (a real catalyst would land unhedged).
        assertEquals("complacent", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 40.0, 30.0, 30.0),
                noDelta()).path("priced_in").asText());
        // Elevated IV (85 > 80) -> braced regardless of trend.
        assertEquals("braced", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 85.0, 30.0, 30.0),
                noDelta()).path("priced_in").asText());
        // Mid IV (60), DVOL bid up (+10%) -> braced (market bracing for a move).
        assertEquals("braced", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 60.0, 42.0, 38.0),
                noDelta()).path("priced_in").asText());
        // Mid IV (60), flat DVOL -> normal (neither complacent nor braced).
        assertEquals("normal", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 60.0, 40.0, 40.0),
                noDelta()).path("priced_in").asText());
    }

    @Test
    public void positioningPrefersNearTermAndFallsBackToAggregate()
    {
        ObjectNode bearish = snapshot(0.4, 100.0, 1.2, 50.0, 30.0, 30.0);
        assertEquals("bearish", OptionsSignals.signal(bearish, noDelta()).path("positioning").asText());

        ObjectNode bullish = snapshot(0.9, 100.0, 0.4, 50.0, 30.0, 30.0);
        assertEquals("bullish", OptionsSignals.signal(bullish, noDelta()).path("positioning").asText());

        // Near-term P/C absent: fall back to the aggregate ratio (0.4 -> bullish).
        ObjectNode fallback = Json.newObject();
        fallback.put("pc_ratio", 0.4);
        fallback.putNull("near_pc_ratio");
        assertEquals("bullish", OptionsSignals.signal(fallback, noDelta()).path("positioning").asText());
    }

    @Test
    public void strongSignalCombinesPositioningIvAndOiSurge()
    {
        ObjectNode snapshot = snapshot(0.8, 110.0, 1.2, 85.0, 40.0, 38.0);
        ObjectNode previous = snapshot(0.8, 100.0, 1.2, 85.0, 40.0, 38.0);
        ObjectNode delta = OptionsSignals.delta(snapshot, previous);

        ObjectNode signal = OptionsSignals.signal(snapshot, delta);

        assertEquals("bearish", signal.path("positioning").asText());
        assertTrue(signal.path("iv_elevated").asBoolean());
        assertTrue(signal.path("oi_surge").asBoolean());
        assertEquals("strong", signal.path("signal_strength").asText());
        assertEquals(1.2, signal.path("near_pc_ratio").asDouble(), EPS);
        // IV 85 is elevated -> the priced-in view reads braced.
        assertEquals(85.0, signal.path("near_atm_iv").asDouble(), EPS);
        assertEquals("braced", signal.path("priced_in").asText());
    }

    @Test
    public void dvolTrendClassifiesByHourlyChange()
    {
        assertEquals("rising", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 50.0, 40.0, 38.0), noDelta())
                .path("dvol_trend").asText());
        assertEquals("falling", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 50.0, 38.0, 40.0), noDelta())
                .path("dvol_trend").asText());
        assertEquals("flat", OptionsSignals.signal(snapshot(0.8, 100.0, 0.8, 50.0, 40.0, 40.0), noDelta())
                .path("dvol_trend").asText());
    }

    @Test
    public void moderateSignalFromSingleTrigger()
    {
        // Only IV elevated; positioning neutral, no OI surge.
        ObjectNode snapshot = snapshot(0.8, 100.0, 0.8, 85.0, 30.0, 30.0);

        ObjectNode signal = OptionsSignals.signal(snapshot, noDelta());

        assertEquals("neutral", signal.path("positioning").asText());
        assertTrue(signal.path("iv_elevated").asBoolean());
        assertFalse(signal.path("oi_surge").asBoolean());
        assertEquals("moderate", signal.path("signal_strength").asText());
    }

    /** A snapshot carrying the fields the signals read; mirrors {@code OptionsFetcher} output shape. */
    private static ObjectNode snapshot(double pcRatio, double totalOi, double nearPcRatio,
            double nearAtmIv, double dvol, double dvol1hAgo)
    {
        ObjectNode node = Json.newObject();
        node.put("pc_ratio", pcRatio);
        node.put("total_oi", totalOi);
        node.put("near_pc_ratio", nearPcRatio);
        node.put("near_atm_iv", nearAtmIv);
        node.put("dvol", dvol);
        node.put("dvol_1h_ago", dvol1hAgo);
        return node;
    }

    private static ObjectNode noDelta()
    {
        ObjectNode delta = Json.newObject();
        delta.put("has_delta", false);
        return delta;
    }
}
