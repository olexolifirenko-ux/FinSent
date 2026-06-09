package com.finsent.collect;

import java.time.Instant;

import com.finsent.util.GlobalSystem;
import com.finsent.util.IUninitializer;

/**
 * High-frequency urgent poller (ports the Phase-3 subset of Python {@code urgent.run_urgent_loop} +
 * the monitor urgent thread). A single daemon thread calls {@link FSCollector#collectUrgent} every
 * {@code urgentPollInSec} seconds; the collector flags urgent-worthy articles, fetches per-article
 * OHLC, and publishes an urgent-flagged result when one lands in the current window. Each poll logs
 * at info when it stores new articles, otherwise a debug-level heartbeat (so the poll cadence is
 * visible with debug on); a failed poll is logged and the loop continues. {@link #uninitialize()}
 * stops the thread (waking it from the poll wait) and waits briefly for an in-flight poll to finish.
 */
public final class UrgentPoller implements IUninitializer
{
    private static final String NAME = "UrgentPoller";
    private static final long SHUTDOWN_WAIT_MS = 10_000;

    private final FSCollector collector_;
    private final Object lock_ = new Object();
    private final Thread thread_;
    private volatile boolean running_ = true;

    public UrgentPoller(FSCollector collector)
    {
        collector_ = collector;
        thread_ = new Thread(this::loop, "FS-Urgent");
        thread_.setDaemon(true);
    }

    /** Start the polling thread. */
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
            pollOnce();
            awaitNextPoll();
        }
    }

    private void pollOnce()
    {
        try
        {
            collector_.collectUrgent(Instant.now()); // the collector logs the cycle summary (the windows it enriched)
        }
        catch (Exception pollFailed)
        {
            // Abort this poll only; the polling thread stays alive for the next poll.
            GlobalSystem.error().writes(NAME, "Urgent poll failed -- aborted, poller continues", pollFailed);
        }
    }

    private void awaitNextPoll()
    {
        long pollMillis = collector_.config().urgentPollInSec() * 1000L;
        synchronized (lock_)
        {
            if (running_)
            {
                try
                {
                    lock_.wait(pollMillis);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    running_ = false;
                }
            }
        }
    }
}
