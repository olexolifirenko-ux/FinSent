package com.finsent.trade.broker;

/**
 * Simulated broker: fills every market order at the price the trader passes in (the collected BTC
 * price at decision time), with no slippage or fees. It places no real orders and needs no
 * credentials &mdash; the safe default for v1. A live adapter replaces it behind {@link IBroker}
 * without changing the trader.
 */
public final class PaperBroker implements IBroker
{
    @Override
    public Fill marketOrder(OrderSide side, double qty, double price)
    {
        return new Fill(price, qty);
    }

    @Override
    public String name()
    {
        return "paper";
    }
}
