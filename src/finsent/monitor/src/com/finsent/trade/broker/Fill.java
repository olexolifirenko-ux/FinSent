package com.finsent.trade.broker;

/**
 * The outcome of a {@link IBroker#marketOrder} call: the price at which {@code qty} BTC was filled.
 * A live broker would carry an order id, fees and partial-fill detail; v1 paper needs only the
 * executed price and quantity.
 *
 * @param price the fill price.
 * @param qty   the filled quantity in BTC.
 */
public record Fill(double price, double qty)
{
}
