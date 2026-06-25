package com.finsent.trade;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.notify.ImpactTier;
import com.finsent.analyse.signal.Conviction;

/**
 * The trading strategy's <b>eligibility rules</b>: pure yes/no judgments about whether a signal merits an
 * action, separated from the {@link FSTrader}'s execution and lifecycle. It holds the strategy thresholds
 * (the news minimum impact tier, the momentum minimum conviction, and whether reversal exits are armed)
 * and answers three questions -- open on this news call? open on this momentum fire? close an open momentum
 * position because the move reversed? No clock, no broker, no book, no logging, so it is trivially unit-
 * testable. The trader still owns the execution-time guards (catalyst freshness, live-price divergence) and
 * the order/position machinery; it consults this policy to <i>decide</i>, then <i>acts</i>.
 */
public final class EntryPolicy
{
    private final String newsMinTier_;
    private final Conviction fastMinConviction_;
    private final boolean reversalExit_;

    public EntryPolicy(String newsMinTier, Conviction fastMinConviction, boolean reversalExit)
    {
        newsMinTier_ = newsMinTier;
        fastMinConviction_ = fastMinConviction;
        reversalExit_ = reversalExit;
    }

    /** Whether a news call is eligible to open: directional and at or above the minimum impact tier. */
    public boolean qualifiesNews(AnalysisReady signal)
    {
        return directional(signal.direction())
                && ImpactTier.order(signal.impactTier(), -1) >= ImpactTier.order(newsMinTier_, 2);
    }

    /** Whether a FastMove fire is eligible to open: directional and at or above the minimum conviction. */
    public boolean qualifiesFast(FastMoveReady signal)
    {
        return directional(signal.direction()) && signal.conviction().meets(fastMinConviction_);
    }

    /**
     * Whether a fire should close an open momentum position as a reversal: reversal exits armed, the open
     * position is a momentum one (a news position keeps its own thesis), and the fire is a confirmed
     * (non-{@code skip}) directional move opposite the position. Independent of the entry minimum conviction
     * -- exiting a turned position is the conservative action, so even a {@code reduced} opposite closes it.
     */
    public boolean isReversalExit(FastMoveReady signal, Position open)
    {
        return reversalExit_ && open != null && "momentum".equals(open.source())
                && directional(signal.direction()) && signal.conviction() != Conviction.SKIP && opposes(signal, open);
    }

    private static boolean directional(String direction)
    {
        return "bullish".equals(direction) || "bearish".equals(direction);
    }

    /** Whether the signal's implied side is opposite the open position's side. */
    private static boolean opposes(FastMoveReady signal, Position open)
    {
        Side signalSide = "bullish".equals(signal.direction()) ? Side.LONG : Side.SHORT;
        return signalSide != open.side();
    }
}
