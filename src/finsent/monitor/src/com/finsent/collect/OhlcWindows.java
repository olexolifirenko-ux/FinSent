package com.finsent.collect;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The two OHLC fetch time ranges, as epoch-millisecond {@code [startMs, endMs]} pairs. These
 * mirror the Python collector's window math exactly &mdash; the buffers are load-bearing for
 * data parity, so they are isolated here as pure, testable functions.
 */
public final class OhlcWindows
{
    private static final long MS_PER_MIN = 60_000L;
    /** Extra minutes fetched before the boundary strip start. */
    private static final int STRIP_START_BUFFER_MIN = 5;
    /** Extra minutes fetched before the earliest article. */
    private static final int ARTICLE_START_BUFFER_MIN = 2;
    /** Extra minutes fetched after the latest article. */
    private static final int ARTICLE_END_BUFFER_MIN = 1;

    private OhlcWindows()
    {
    }

    /**
     * Range for the boundary strip of the interval containing {@code now}: from
     * {@code boundary - ohlcImpactWindow - 5m} to {@code boundary + window}. Ports
     * {@code collect.store_btc_price_strip}.
     */
    public static long[] boundaryStrip(Instant now, int windowMinutes, int ohlcImpactWindowMinutes)
    {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        int flooredMin = (utc.getMinute() / windowMinutes) * windowMinutes;
        int boundaryMin = utc.getHour() * 60 + flooredMin;
        int startMin = boundaryMin - ohlcImpactWindowMinutes - STRIP_START_BUFFER_MIN;
        int endMin = boundaryMin + windowMinutes;
        long baseMs = utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return new long[] { baseMs + startMin * MS_PER_MIN, baseMs + endMin * MS_PER_MIN };
    }

    /**
     * Range covering the articles' pre-publication windows: from {@code earliest -
     * (ohlcImpactWindow + 2m)} to {@code latest + 1m}. Ports {@code collect.store_article_ohlc}.
     */
    public static long[] articleWindow(Instant earliest, Instant latest, int ohlcImpactWindowMinutes)
    {
        long startMs = earliest.minus(Duration.ofMinutes(ohlcImpactWindowMinutes + ARTICLE_START_BUFFER_MIN))
                .toEpochMilli();
        long endMs = latest.plus(Duration.ofMinutes(ARTICLE_END_BUFFER_MIN)).toEpochMilli();
        return new long[] { startMs, endMs };
    }
}
