package com.finsent.trade;

/**
 * The direction of an open position: {@code LONG} profits when price rises, {@code SHORT} when it
 * falls. The trader opens {@code LONG} on a bullish call and {@code SHORT} on a bearish one; the
 * trailing-stop and P&amp;L math branch on this.
 */
public enum Side
{
    LONG,
    SHORT;

    /** True for a long position (price-up bet); false for a short. */
    public boolean isLong()
    {
        return this == LONG;
    }

    /** Signed multiplier for P&amp;L: {@code +1} long, {@code -1} short. */
    public int sign()
    {
        return isLong() ? 1 : -1;
    }
}
