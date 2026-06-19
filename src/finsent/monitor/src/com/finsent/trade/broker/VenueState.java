package com.finsent.trade.broker;

import com.finsent.trade.Side;

/**
 * The venue's authoritative open-position state for the traded market, read at startup so the trader
 * can reconcile its local book against reality. {@code UNTRACKED} means the broker has no venue truth
 * (the paper broker &mdash; the book is authoritative); {@code FLAT} means a live venue holding no
 * position; {@code OPEN} carries the live position's side, base quantity and entry price.
 *
 * @param kind       which of the three reconciliation cases this represents.
 * @param side       the open position's direction (only meaningful when {@code kind == OPEN}).
 * @param qty        the open position's base (BTC) quantity (only when {@code kind == OPEN}).
 * @param entryPrice the open position's average entry price (only when {@code kind == OPEN}).
 * @param leverage   the position's effective leverage on the venue (exposure / posted margin), or
 *                   {@code 0} when unknown / not an open position; used to flag a config mismatch.
 */
public record VenueState(Kind kind, Side side, double qty, double entryPrice, double leverage)
{
    /** Which reconciliation case a {@link VenueState} represents. */
    public enum Kind
    {
        UNTRACKED,
        FLAT,
        OPEN
    }

    /** The broker has no venue truth (paper): the local book stays authoritative. */
    public static VenueState untracked()
    {
        return new VenueState(Kind.UNTRACKED, null, 0.0, 0.0, 0.0);
    }

    /** The live venue holds no open position. */
    public static VenueState flat()
    {
        return new VenueState(Kind.FLAT, null, 0.0, 0.0, 0.0);
    }

    /** The live venue holds an open position with the given side, base quantity, entry price and leverage. */
    public static VenueState open(Side side, double qty, double entryPrice, double leverage)
    {
        return new VenueState(Kind.OPEN, side, qty, entryPrice, leverage);
    }
}
