package com.finsent.collect;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

/**
 * Verifies {@link EconScheduler}'s pure timing helpers: classifying a release into fire-now / wait /
 * skip relative to its poll cap, and the delay to the next 00:00 UTC re-arm.
 */
public class EconScheduler_utest
{
    @Test
    public void armDelayForAFutureReleaseIsTheGap()
    {
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        Instant release = Instant.parse("2026-06-11T12:30:00Z");
        assertEquals(1800L, EconScheduler.armDelaySeconds(release, now, 10));
    }

    @Test
    public void armDelayInsideTheWindowFiresNow()
    {
        Instant release = Instant.parse("2026-06-11T12:30:00Z");
        Instant now = Instant.parse("2026-06-11T12:33:00Z"); // 3 min in, within the 10-min cap
        assertEquals(0L, EconScheduler.armDelaySeconds(release, now, 10));
    }

    @Test
    public void armDelayPastTheCapIsSkipped()
    {
        Instant release = Instant.parse("2026-06-11T12:30:00Z");
        Instant now = Instant.parse("2026-06-11T12:45:00Z"); // 15 min in, past the 10-min cap
        assertEquals(-1L, EconScheduler.armDelaySeconds(release, now, 10));
    }

    @Test
    public void secondsToMidnightCountsToTheNextUtcDay()
    {
        assertEquals(3600L, EconScheduler.secondsUntilNextUtcMidnight(Instant.parse("2026-06-11T23:00:00Z")));
        assertEquals(86400L, EconScheduler.secondsUntilNextUtcMidnight(Instant.parse("2026-06-11T00:00:00Z")));
    }
}
