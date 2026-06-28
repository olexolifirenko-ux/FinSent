package com.finsent.trade.broker;

/**
 * Simulated broker: fills every market order at the price the trader passes in (the collected BTC
 * price at decision time), adjusted adversely by a configured per-side slippage so paper P&amp;L
 * reflects the spread/impact a real market order crosses (a {@code BUY} fills above, a {@code SELL}
 * below). Taker <i>fees</i> are modeled separately on the position (they are a notional cost, not a
 * price effect). It places no real orders and needs no credentials &mdash; the safe default for v1.
 * A live adapter replaces it behind {@link IBroker} without changing the trader.
 */
public final class PaperBroker implements IBroker
{
    private final double slippageInPct_;

    /** Zero-slippage paper broker: fills exactly at the passed price (tests / strict signal-price fills). */
    public PaperBroker()
    {
        this(0.0);
    }

    /** Paper broker filling each market order {@code slippageInPct} adverse to the passed price (spread+impact). */
    public PaperBroker(double slippageInPct)
    {
        slippageInPct_ = slippageInPct;
    }

    @Override
    public Fill marketOrder(OrderSide side, double qty, double price)
    {
        return new Fill(fillPrice(side, price), qty);
    }

    /** A market {@code BUY} crosses up, a {@code SELL} down, by the configured slippage fraction. */
    private double fillPrice(OrderSide side, double price)
    {
        double frac = slippageInPct_ / 100.0;
        return side == OrderSide.BUY ? price * (1.0 + frac) : price * (1.0 - frac);
    }

    @Override
    public String name()
    {
        return "paper";
    }
}
