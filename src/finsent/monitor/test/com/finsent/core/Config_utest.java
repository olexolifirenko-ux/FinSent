package com.finsent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.finsent.util.xml.XMLData;

/**
 * Verifies the {@code <FSTrader>} per-lane config layout: the news-lane entry knobs read from
 * {@code <NewsLane>}, the momentum-lane knobs from {@code <FastMoveLane>}, the shared execution/exit
 * settings from {@code <FSTrader>} itself, and the FastMove DETECTION knobs from {@code <FSFastMove>}.
 * The distinct per-lane values (notably the 777 vs 111 notionals) prove each accessor reads its own
 * sub-node -- a node-name typo would silently fall back to a default and these assertions would fail.
 */
public class Config_utest
{
    private static final double EPS = 1e-9;

    private static final Config CONFIG = new Config(XMLData.valueOf(""
            + "<FSSatellite>"
            + "  <FSTrader broker=\"whitebit\" trailInPct=\"0.9\" profitGraceInMin=\"17\" maxHoldInHours=\"9\""
            + "            entryMaxPriceDivergencePct=\"2.5\" pricePollInSec=\"11\">"
            + "    <NewsLane entryImpactTier=\"low\" entryMaxNewsAgeInMin=\"7\" notionalInUsd=\"777.0\""
            + "              leverage=\"4.0\" stopLossInPct=\"0.25\"/>"
            + "    <FastMoveLane trade=\"true\" reversalExit=\"false\" minConviction=\"reduced\""
            + "                  notionalInUsd=\"111.0\" leverage=\"6.0\" stopLossInPct=\"1.3\"/>"
            + "  </FSTrader>"
            + "  <FSFastMove pollInSec=\"9\" cooldownInMin=\"12\" oiBuildingPct=\"0.7\""
            + "              oiLookbackInMin=\"33\" fundingCompressionDropPct=\"55\" fundingCompressionWindow=\"45m\">"
            + "    <Windows><Window span=\"20m\" thresholdPct=\"1.1\" r2Floor=\"0.6\"/></Windows>"
            + "  </FSFastMove>"
            + "</FSSatellite>"));

    @Test
    public void newsLaneEntryKnobsComeFromTheNewsLaneElement()
    {
        assertEquals("low", CONFIG.tradeEntryImpactTier());
        assertEquals(7, CONFIG.tradeEntryMaxNewsAgeInMin());
        assertEquals(777.0, CONFIG.tradeNotionalInUsd(), EPS);
        assertEquals(4.0, CONFIG.tradeLeverage(), EPS);
        assertEquals(0.25, CONFIG.tradeStopLossInPct(), EPS);
    }

    @Test
    public void fastMoveLaneTradeKnobsComeFromTheFastMoveLaneElement()
    {
        assertTrue(CONFIG.fastMoveTrade());
        assertFalse(CONFIG.fastMoveReversalExit());
        assertEquals("reduced", CONFIG.fastMoveMinConviction());
        assertEquals(111.0, CONFIG.fastMoveNotionalInUsd(), EPS); // distinct from the news lane's 777
        assertEquals(6.0, CONFIG.fastMoveLeverage(), EPS);
        assertEquals(1.3, CONFIG.fastMoveStopLossInPct(), EPS);
    }

    @Test
    public void sharedExecutionAndExitSettingsComeFromTheTraderElement()
    {
        assertEquals("whitebit", CONFIG.tradeBroker());
        assertEquals(0.9, CONFIG.tradeTrailInPct(), EPS);
        assertEquals(17, CONFIG.tradeProfitGraceInMin());
        assertEquals(9, CONFIG.tradeMaxHoldInHours());
        assertEquals(2.5, CONFIG.tradeEntryMaxPriceDivergencePct(), EPS);
        assertEquals(11, CONFIG.tradePricePollInSec());
    }

    @Test
    public void fastMoveDetectionKnobsComeFromTheFastMoveElement()
    {
        assertEquals(9, CONFIG.fastMovePollInSec());
        assertEquals(12, CONFIG.fastMoveCooldownInMin());
        assertEquals(0.7, CONFIG.fastMoveOiBuildingPct(), EPS);
        assertEquals(33, CONFIG.fastMoveOiLookbackInMin());
        assertEquals(55.0, CONFIG.fastMoveFundingCompressionDropPct(), EPS);
        assertEquals(45, CONFIG.fastMoveFundingCompressionWindowMinutes());

        List<FastMoveWindow> windows = CONFIG.fastMoveWindows();
        assertEquals(1, windows.size());
        assertEquals(20, windows.get(0).spanMinutes());
        assertEquals(1.1, windows.get(0).thresholdPct(), EPS);
        assertEquals(0.6, windows.get(0).r2Floor(), EPS);
    }
}
