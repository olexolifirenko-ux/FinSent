package com.finsent.trade;

import java.time.Instant;

/**
 * Supplies the BTC price the trader fills and trails against. In production it is
 * {@code FSCollector::currentPrice} (the live Binance 24h-ticker {@code lastPrice}, which has no
 * mid-minute gap); tests inject a scripted source. Returns null when no price is available.
 */
@FunctionalInterface
public interface PriceSource
{
    Double priceAt(Instant target);
}
