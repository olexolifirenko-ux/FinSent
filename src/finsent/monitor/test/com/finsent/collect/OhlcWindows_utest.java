package com.finsent.collect;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

/**
 * Verifies {@link OhlcWindows} against Python {@code collect.store_btc_price_strip} /
 * {@code store_article_ohlc}: the boundary-strip and per-article epoch-ms ranges including the
 * exact -5m / +window / -(window+2)m / +1m buffers.
 */
public class OhlcWindows_utest
{
    private static final long MS_PER_MIN = 60_000L;

    @Test
    public void boundaryStripCoversImpactWindowToNextBoundary()
    {
        // 12:03:20 with a 10m window floors to boundary 12:00 (720 min since midnight).
        Instant now = Instant.parse("2026-05-31T12:03:20Z");
        long base = Instant.parse("2026-05-31T00:00:00Z").toEpochMilli();

        long[] range = OhlcWindows.boundaryStrip(now, 10, 30);

        // start = 720 - 30 - 5 = 685 min; end = 720 + 10 = 730 min.
        assertEquals(base + 685 * MS_PER_MIN, range[0]);
        assertEquals(base + 730 * MS_PER_MIN, range[1]);
    }

    @Test
    public void articleWindowSpansEarliestMinusBufferToLatestPlusOne()
    {
        Instant earliest = Instant.parse("2026-05-31T12:31:00Z");
        Instant latest = Instant.parse("2026-05-31T12:34:00Z");

        long[] range = OhlcWindows.articleWindow(earliest, latest, 30);

        // start = earliest - (30 + 2)m = 11:59:00Z; end = latest + 1m = 12:35:00Z.
        assertEquals(Instant.parse("2026-05-31T11:59:00Z").toEpochMilli(), range[0]);
        assertEquals(Instant.parse("2026-05-31T12:35:00Z").toEpochMilli(), range[1]);
    }
}
