package com.finsent.collect.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies {@link NewsApiSource}'s quota gate {@code pollDue}: the first poll proceeds, polls within
 * the one-hour interval are skipped, and crossing the interval boundary re-opens (and resets) the
 * gate. This is what keeps the 10-min collector cadence from exhausting the developer NewsAPI quota.
 */
public class NewsApiSource_utest
{
    private static final long HOUR_MS = 60L * 60L * 1000L;
    // A realistic wall-clock epoch base (well past the interval), matching System.currentTimeMillis().
    private static final long START = 1_700_000_000_000L;

    @Test
    public void firstPollProceeds()
    {
        NewsApiSource source = new NewsApiSource("key");
        assertTrue(source.pollDue(START));
    }

    @Test
    public void pollsWithinIntervalAreSkipped()
    {
        NewsApiSource source = new NewsApiSource("key");
        assertTrue(source.pollDue(START));
        assertFalse("10 min later -- within the hour", source.pollDue(START + 10L * 60L * 1000L));
        assertFalse("just under an hour", source.pollDue(START + HOUR_MS - 1L));
    }

    @Test
    public void intervalBoundaryReopensAndResetsGate()
    {
        NewsApiSource source = new NewsApiSource("key");
        assertTrue(source.pollDue(START));
        assertTrue("exactly an hour later", source.pollDue(START + HOUR_MS));
        assertFalse("resets from the new fetch time", source.pollDue(START + HOUR_MS + 1L));
        assertTrue("another full hour on", source.pollDue(START + 2L * HOUR_MS));
    }
}
