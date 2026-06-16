package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The mechanical scheduled-event surprise rules: hot inflation/jobs → bearish, cool → bullish, higher
 * unemployment → bullish, in-line → neutral/noise; magnitude → low/high via the per-event bands.
 */
public class EconEventSignals_utest
{
    @Test
    public void hotInflationIsBearishHighOnALargeSurprise()
    {
        ObjectNode s = EconEventSignals.signal("CPI MoM", "%", 0.3, 0.6, "bearish", 0.1, 0.2);
        assertEquals("bearish", s.path("direction").asText());
        assertEquals("high", s.path("impact_tier").asText());
        assertEquals("CPI MoM 0.6% vs 0.3% est (+0.3%, high -> bearish)", s.path("label").asText());
    }

    @Test
    public void coolInflationFlipsToBullishLowOnAModerateSurprise()
    {
        ObjectNode s = EconEventSignals.signal("CPI MoM", "%", 0.3, 0.1, "bearish", 0.1, 0.2);
        assertEquals("bullish", s.path("direction").asText());
        assertEquals("low", s.path("impact_tier").asText());
    }

    @Test
    public void inLineSurpriseIsNeutralNoise()
    {
        ObjectNode s = EconEventSignals.signal("CPI MoM", "%", 0.3, 0.35, "bearish", 0.1, 0.2);
        assertEquals("neutral", s.path("direction").asText());
        assertEquals("noise", s.path("impact_tier").asText());
        assertEquals("CPI MoM 0.35% vs 0.3% est (in line)", s.path("label").asText());
    }

    @Test
    public void higherUnemploymentIsBullish()
    {
        // a hot (higher) unemployment print is labor slack -> dovish -> bullish (inverse of CPI/NFP)
        ObjectNode s = EconEventSignals.signal("Unemployment Rate", "%", 4.0, 4.3, "bullish", 0.1, 0.2);
        assertEquals("bullish", s.path("direction").asText());
        assertEquals("high", s.path("impact_tier").asText());
    }

    @Test
    public void payrollsUseCountUnitsAndCountBands()
    {
        ObjectNode s = EconEventSignals.signal("Nonfarm Payrolls", "K", 180, 260, "bearish", 25, 75);
        assertEquals("bearish", s.path("direction").asText());
        assertEquals("high", s.path("impact_tier").asText()); // +80 > 75 high band
        assertEquals("Nonfarm Payrolls 260K vs 180K est (+80K, high -> bearish)", s.path("label").asText());
    }
}
