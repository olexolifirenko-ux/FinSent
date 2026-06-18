package com.finsent.trade;

import java.time.Instant;

/**
 * Supplies the BTC price the trader fills and trails against. In production it is
 * {@code FSCollector::fetchClosePriceAt} (the collected Binance close, the same source the notifier
 * uses for its realtime price); tests inject a scripted source. Returns null when no price is
 * available for {@code target}.
 */
@FunctionalInterface
public interface PriceSource
{
    Double priceAt(Instant target);
}
