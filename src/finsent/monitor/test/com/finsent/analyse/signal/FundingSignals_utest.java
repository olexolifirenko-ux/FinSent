package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link FundingSignals}: the funding-rate crowding bands (crowded/extreme long/short and
 * neutral, with inclusive boundaries), the reported rate-percent, and the null result when no
 * funding rate is present.
 */
public class FundingSignals_utest
{
    @Test
    public void classifiesCrowdingBands()
    {
        assertEquals("crowded_long", positioning(0.0002));
        assertEquals("extreme_long", positioning(0.0006));
        assertEquals("crowded_short", positioning(-0.0002));
        assertEquals("extreme_short", positioning(-0.0006));
        assertEquals("neutral", positioning(0.00005));
        assertEquals("neutral", positioning(-0.00005));
    }

    @Test
    public void boundariesAreInclusive()
    {
        assertEquals("crowded_long", positioning(0.0001));
        assertEquals("extreme_long", positioning(0.0005));
        assertEquals("crowded_short", positioning(-0.0001));
        assertEquals("extreme_short", positioning(-0.0005));
    }

    @Test
    public void reportsRatePercentAndPositioning()
    {
        ObjectNode signal = FundingSignals.signal(snapshot(0.00038));
        assertEquals(0.038, signal.path("funding_rate_pct").asDouble(), 1e-9);
        assertEquals("crowded_long", signal.path("positioning").asText());
    }

    @Test
    public void nullWhenNoFundingRate()
    {
        assertNull("empty snapshot -> no signal", FundingSignals.signal(Json.newObject()));
        assertNull("null snapshot -> no signal", FundingSignals.signal(null));
    }

    @Test
    public void fusesOiTrendAndPriceMoveIntoSetup()
    {
        ObjectNode building = withOi(snapshot(0.0002), 105.0); // +5% vs prior 100
        ObjectNode prior = withOi(Json.newObject(), 100.0);

        ObjectNode up = FundingSignals.signal(building, prior, 1.2); // building into a rising price
        assertEquals(5.0, up.path("oi_change_pct").asDouble(), 1e-9);
        assertEquals("building", up.path("oi_trend").asText());
        assertEquals("new longs -> down-cascade fuel", "down_cascade_fuel", up.path("setup").asText());

        assertEquals("new shorts -> up-squeeze fuel", "up_squeeze_fuel",
                FundingSignals.signal(building, prior, -1.2).path("setup").asText());

        ObjectNode unwinding = withOi(snapshot(0.0002), 90.0); // -10% vs prior
        assertEquals("OI unwinding -> exhausting", "exhausting",
                FundingSignals.signal(unwinding, prior, 1.2).path("setup").asText());
    }

    @Test
    public void noOiFusionWithoutPriorOpenInterest()
    {
        ObjectNode signal = FundingSignals.signal(withOi(snapshot(0.0002), 105.0), null, 1.2);
        assertEquals("crowded_long", signal.path("positioning").asText());
        assertFalse("no OI delta -> no setup field", signal.has("setup"));
    }

    private static ObjectNode withOi(ObjectNode snapshot, double openInterest)
    {
        snapshot.put("open_interest", openInterest);
        return snapshot;
    }

    private static String positioning(double rate)
    {
        return FundingSignals.signal(snapshot(rate)).path("positioning").asText();
    }

    private static ObjectNode snapshot(double rate)
    {
        ObjectNode snapshot = Json.newObject();
        snapshot.put("funding_rate", rate);
        return snapshot;
    }
}
