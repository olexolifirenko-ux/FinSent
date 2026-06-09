package com.finsent.collect;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import com.finsent.util.GlobalSystem;
import com.finsent.util.IUninitializer;

/**
 * Boundary-aligned scheduler for the collector (ports Python {@code monitor._collector_loop} +
 * {@code _seconds_until_next_boundary}). A single daemon thread sleeps until the next
 * {@code windowMinutes} boundary and runs one {@link FSCollector#collect} cycle (the collector
 * publishes the result to its subscribers). A failed cycle is logged and the loop continues.
 * {@link #uninitialize()} stops the thread (waking it from the boundary wait) and waits briefly for
 * an in-flight cycle to finish before the collector's persistence is shut down.
 */
public final class CollectorRunner implements IUninitializer
{
    private static final String NAME = "CollectorRunner";
    private static final long MIN_WAIT_SECONDS = 5;
    private static final long SHUTDOWN_WAIT_MS = 10_000;

    private final FSCollector collector_;
    private final Object lock_ = new Object();
    private final Thread thread_;
    private volatile boolean running_ = true;

    public CollectorRunner(FSCollector collector)
    {
        collector_ = collector;
        thread_ = new Thread(this::loop, "FS-Collector");
        thread_.setDaemon(true);
    }

    /** Start the scheduling thread. */
    public void start()
    {
        thread_.start();
    }

    @Override
    public void uninitialize()
    {
        running_ = false;
        synchronized (lock_)
        {
            lock_.notifyAll();
        }
        try
        {
            thread_.join(SHUTDOWN_WAIT_MS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void loop()
    {
        while (running_)
        {
            if (awaitNextBoundary())
            {
                runCycle();
            }
        }
    }

    /** Sleep until the next boundary; returns false when shutdown woke us instead. */
    private boolean awaitNextBoundary()
    {
        long waitSeconds = secondsUntilNextBoundary(Instant.now(), collector_.config().windowMinutes());
        synchronized (lock_)
        {
            if (running_)
            {
                try
                {
                    lock_.wait(waitSeconds * 1000L);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    running_ = false;
                }
            }
        }
        return running_;
    }

    private void runCycle()
    {
        try
        {
            collector_.collect(Instant.now()); // the collector logs the cycle summary (the windows it enriched)
        }
        catch (Exception cycleFailed)
        {
            // Abort this cycle only; the scheduling thread stays alive for the next boundary.
            GlobalSystem.error().writes(NAME, "Collection cycle failed -- aborted, poller continues", cycleFailed);
        }
    }

    /**
     * Seconds until the next {@code windowMinutes} boundary from {@code now} (UTC), floored at
     * {@value #MIN_WAIT_SECONDS}s. Ports {@code monitor._seconds_until_next_boundary}.
     */
    static long secondsUntilNextBoundary(Instant now, int windowMinutes)
    {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        int currentMin = utc.getHour() * 60 + utc.getMinute();
        int nextBoundaryMin = ((currentMin / windowMinutes) + 1) * windowMinutes;
        int remainingInMin = 60 - utc.getSecond();
        long wait = (long) (nextBoundaryMin - currentMin - 1) * 60 + remainingInMin;
        return Math.max(wait, MIN_WAIT_SECONDS);
    }
}
