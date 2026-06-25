package com.finsent.trade;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;

/**
 * Verifies {@link EntryPolicy}'s pure eligibility rules in isolation (no trader, broker, or book): the
 * news impact-tier gate, the momentum conviction gate (and its tunable minimum), and the reversal-exit
 * trigger (momentum-only, confirmed opposite, independent of the entry minimum).
 */
public class EntryPolicy_utest
{
    private static final Instant NOW = Instant.parse("2026-06-04T08:00:00Z");
    private static final EntryPolicy FULL = new EntryPolicy("high", Conviction.FULL, true);

    @Test
    public void newsOpensOnlyAtOrAboveTheMinTier()
    {
        assertTrue(FULL.qualifiesNews(news("bullish", "high")));
        assertFalse("low is below the 'high' min", FULL.qualifiesNews(news("bullish", "low")));
        assertFalse("neutral is not directional", FULL.qualifiesNews(news("neutral", "high")));
    }

    @Test
    public void newsTierGateIsTunable()
    {
        EntryPolicy lowOk = new EntryPolicy("low", Conviction.FULL, true);
        assertTrue(lowOk.qualifiesNews(news("bearish", "low")));
        assertFalse(lowOk.qualifiesNews(news("bearish", "noise")));
    }

    @Test
    public void fastOpensOnlyAtOrAboveTheMinConviction()
    {
        assertTrue(FULL.qualifiesFast(fast("bearish", Conviction.FULL)));
        assertFalse("reduced is below the 'full' min", FULL.qualifiesFast(fast("bearish", Conviction.REDUCED)));
        assertFalse(FULL.qualifiesFast(fast("bearish", Conviction.SKIP)));
    }

    @Test
    public void fastConvictionGateIsTunable()
    {
        EntryPolicy reducedOk = new EntryPolicy("high", Conviction.REDUCED, true);
        assertTrue(reducedOk.qualifiesFast(fast("bullish", Conviction.REDUCED)));
        assertFalse("skip never qualifies", reducedOk.qualifiesFast(fast("bullish", Conviction.SKIP)));
    }

    @Test
    public void reversalExitTriggersOnAConfirmedOppositeForAMomentumPosition()
    {
        Position shortPos = momentum(Side.SHORT);
        assertTrue("bullish full opposite a short", FULL.isReversalExit(fast("bullish", Conviction.FULL), shortPos));
        assertTrue("even reduced opposite exits", FULL.isReversalExit(fast("bullish", Conviction.REDUCED), shortPos));
    }

    @Test
    public void reversalExitIgnoresSkipSameSideNewsAndDisabled()
    {
        Position shortPos = momentum(Side.SHORT);
        assertFalse("skip is not confirmed", FULL.isReversalExit(fast("bullish", Conviction.SKIP), shortPos));
        assertFalse("same side is not opposite", FULL.isReversalExit(fast("bearish", Conviction.FULL), shortPos));
        assertFalse("a news position is left alone", FULL.isReversalExit(fast("bullish", Conviction.FULL), news(Side.SHORT)));
        assertFalse("flat -> nothing to reverse", FULL.isReversalExit(fast("bullish", Conviction.FULL), null));

        EntryPolicy off = new EntryPolicy("high", Conviction.FULL, false);
        assertFalse("reversal disabled", off.isReversalExit(fast("bullish", Conviction.FULL), shortPos));
    }

    private static AnalysisReady news(String direction, String tier)
    {
        return new AnalysisReady("20260604", "08:00", "news", direction, tier, 100.0, NOW, NOW);
    }

    private static FastMoveReady fast(String direction, Conviction conviction)
    {
        return new FastMoveReady("20260604", "08:00", direction, conviction, 100.0, -1.5, 0.85, 30, "", NOW);
    }

    private static Position momentum(Side side)
    {
        return Position.open("20260604", "08:00", "momentum", side, 100.0, 150.0, 3.0, NOW, 1.0);
    }

    private static Position news(Side side)
    {
        return Position.open("20260604", "08:00", "news", side, 100.0, 1000.0, 2.0, NOW, 1.0);
    }
}
