package com.finsent.trade.broker;

/**
 * The execution seam between the trader's decisions and a venue. v1 ships only {@link PaperBroker}
 * (simulated fills against the collected BTC price); a live adapter (e.g. a WhiteBIT USD-M
 * perpetual-futures broker over its signed Trading API) implements the same interface and is
 * selected by config, so the trader's logic is unchanged. Opening or closing a long/short maps to a
 * market {@code BUY}/{@code SELL} of a BTC quantity; the broker reports the resulting {@link Fill}.
 */
public interface IBroker
{
    /** Execute a market order of {@code qty} BTC at the prevailing {@code price}; returns the fill. */
    Fill marketOrder(OrderSide side, double qty, double price);

    /** A short name for the venue (for logging / {@code trade status}). */
    String name();
}
