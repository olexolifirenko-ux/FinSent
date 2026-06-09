package com.finsent.collect;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

/**
 * Verifies {@link CollectorRunner#secondsUntilNextBoundary} against Python
 * {@code monitor._seconds_until_next_boundary}: the wait to the next window boundary and the
 * 5-second floor.
 */
public class CollectorRunner_utest
{
    @Test
    public void waitsUntilNextBoundary()
    {
        // 12:03:20, 10m window -> next boundary 12:10:00 = 6m40s = 400s.
        assertEquals(400, CollectorRunner.secondsUntilNextBoundary(Instant.parse("2026-05-31T12:03:20Z"), 10));
    }

    @Test
    public void exactlyOnBoundaryWaitsAFullWindow()
    {
        // 12:10:00, 10m window -> next boundary 12:20:00 = 600s.
        assertEquals(600, CollectorRunner.secondsUntilNextBoundary(Instant.parse("2026-05-31T12:10:00Z"), 10));
    }

    @Test
    public void clampsToFiveSecondFloorNearBoundary()
    {
        // 12:09:58, 10m window -> raw 2s, floored to 5s.
        assertEquals(5, CollectorRunner.secondsUntilNextBoundary(Instant.parse("2026-05-31T12:09:58Z"), 10));
    }
}
