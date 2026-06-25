package com.finsent.collect;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.finsent.core.Json;
import com.finsent.core.Times;

/**
 * A small rolling buffer of live price samples, bucketed into 1-minute closes, that feeds the FastMove
 * detector. The collector's persisted OHLC registry only refreshes on the 10-minute boundary cycle, too
 * coarse to detect sharp intra-window moves on; instead the poller samples {@code currentPrice()} every
 * few seconds into this buffer, where the last sample of each minute becomes that minute's close (the
 * shape {@link com.finsent.analyse.signal.PreTrend}/{@code FastMoveSignal} consume). Purely in-memory and
 * never written to the persisted registry. Single-threaded (the poller thread), so unsynchronized.
 */
final class PriceTapeBuffer
{
    private final int retentionMinutes_;
    // minute-epoch (epoch seconds / 60) -> that minute's latest sampled price (its "close").
    private final TreeMap<Long, Double> closesByMinute_ = new TreeMap<>();

    PriceTapeBuffer(int retentionMinutes)
    {
        retentionMinutes_ = retentionMinutes;
    }

    /** Record a price sample; the last sample within a minute is that minute's close. Prunes stale minutes. */
    void add(Instant when, double price)
    {
        long minute = when.getEpochSecond() / 60L;
        closesByMinute_.put(minute, price);
        long cutoff = minute - retentionMinutes_;
        while (!closesByMinute_.isEmpty() && closesByMinute_.firstKey() < cutoff)
        {
            closesByMinute_.pollFirstEntry();
        }
    }

    /** Number of 1-minute buckets currently held. */
    int size()
    {
        return closesByMinute_.size();
    }

    /**
     * The last {@code spanMinutes} 1-minute bars (chronological, oldest first), each an object with a
     * {@code ts} (UTC ISO of the minute) and a {@code c} close. Returns fewer when the buffer holds fewer.
     */
    ArrayNode barsLastMinutes(int spanMinutes)
    {
        ArrayNode bars = Json.newArray();
        int skip = Math.max(0, closesByMinute_.size() - spanMinutes);
        int index = 0;
        for (Map.Entry<Long, Double> entry : closesByMinute_.entrySet())
        {
            if (index >= skip)
            {
                appendBar(bars, entry.getKey(), entry.getValue());
            }
            index++;
        }
        return bars;
    }

    private static void appendBar(ArrayNode bars, long minute, double close)
    {
        bars.addObject()
                .put("ts", Times.formatUtcIso(Instant.ofEpochSecond(minute * 60L)))
                .put("c", close);
    }
}
