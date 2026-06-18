package com.finsent.trade.broker;

/**
 * The side of a single market order sent to a broker. A long is opened with {@code BUY} and closed
 * with {@code SELL}; a short is the reverse. Distinct from {@code Side} (the position's direction):
 * one position involves a {@code BUY} and a {@code SELL} over its life.
 */
public enum OrderSide
{
    BUY,
    SELL
}
