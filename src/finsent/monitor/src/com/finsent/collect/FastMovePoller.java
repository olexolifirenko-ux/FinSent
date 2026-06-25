package com.finsent.collect;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;
import com.finsent.analyse.signal.FastMoveSignal;
import com.finsent.analyse.signal.FundingCompression;
import com.finsent.analyse.signal.FundingSignals;
import com.finsent.analyse.signal.PreTrend;
import com.finsent.core.Config;
import com.finsent.core.FastMoveWindow;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.core.event.IEventPublisher;
import com.finsent.util.GlobalSystem;
import com.finsent.util.IUninitializer;

/**
 * The FastMove detector: a self-clocked daemon thread that samples the live BTC price into a rolling
 * {@link PriceTapeBuffer} every {@code pollInSec} and fires a mechanical, news-independent momentum
 * signal when the tape moves far and cleanly enough on any configured {@link FastMoveWindow} (see
 * {@link FastMoveSignal}). Each fire is graded by a funding/OI structural gate -- OI <b>building</b> into
 * the move = {@code full} conviction (fresh positioning, a real trend), OI <b>unwinding</b> = {@code skip}
 * (positions closing, a likely transient wick), otherwise {@code reduced} -- and published as a
 * {@link FastMoveReady} the trader consumes. A per-direction latch fires once per move (cleared when the
 * tape goes flat, re-armed after the cooldown). A separate soft {@link FundingCompression} early-warning
 * is logged, not traded. Models the {@link UrgentPoller} thread pattern; decoupled from the boundary
 * collect loop and the news pipeline -- it joins the rest only at the trader's single-position gate.
 *
 * <p>The thread always runs and is gated by a {@code paused_} flag (mirroring the analyser/trader): it
 * starts paused unless {@code -DrunFastMove}, and is toggled live via the {@code fastmove on|off|status}
 * command.
 */
public final class FastMovePoller implements IUninitializer
{
    private static final String NAME = "FastMove";
    private static final long SHUTDOWN_WAIT_MS = 10_000;
    private static final int RETENTION_MARGIN_MIN = 5;

    private final FSCollector collector_;
    private final IEventPublisher publisher_;
    private final long pollMillis_;
    private final int windowMinutes_;
    private final List<FastMoveWindow> windows_;
    private final int longestSpanMinutes_;
    private final long cooldownMillis_;
    private final double oiBuildingPct_;
    private final int oiLookbackMinutes_;
    private final double compressionDropPct_;
    private final int compressionWindowMinutes_;
    private final PriceTapeBuffer buffer_;
    private final Object lock_ = new Object();
    private final Thread thread_;
    // Runtime on/off (the `fastmove on|off` command). The thread always runs; when paused it idles --
    // no price sampling and no fires -- so detection can be toggled live without a restart.
    private final AtomicBoolean paused_;
    private volatile boolean running_ = true;

    // Poller-thread-only state (no synchronization needed -- a single producer thread).
    private String latchedDirection_;
    private long lastFireMillis_;
    private boolean compressionFlagged_;

    public FastMovePoller(FSCollector collector, IEventPublisher publisher, boolean startPaused)
    {
        collector_ = collector;
        publisher_ = publisher;
        paused_ = new AtomicBoolean(startPaused);
        Config config = collector.config();
        pollMillis_ = config.fastMovePollInSec() * 1000L;
        windowMinutes_ = config.windowMinutes();
        windows_ = config.fastMoveWindows();
        longestSpanMinutes_ = longestSpan(windows_);
        cooldownMillis_ = config.fastMoveCooldownInMin() * 60_000L;
        oiBuildingPct_ = config.fastMoveOiBuildingPct();
        oiLookbackMinutes_ = config.fastMoveOiLookbackInMin();
        compressionDropPct_ = config.fastMoveFundingCompressionDropPct();
        compressionWindowMinutes_ = config.fastMoveFundingCompressionWindowMinutes();
        buffer_ = new PriceTapeBuffer(longestSpanMinutes_ + RETENTION_MARGIN_MIN);
        thread_ = new Thread(this::loop, "FS-FastMove");
        thread_.setDaemon(true);
    }

    private static int longestSpan(List<FastMoveWindow> windows)
    {
        int longest = 1;
        for (FastMoveWindow window : windows)
        {
            longest = Math.max(longest, window.spanMinutes());
        }
        return longest;
    }

    /** Start the polling thread; it idles while paused, so it is always started and toggled via {@code fastmove}. */
    public void start()
    {
        GlobalSystem.info().writes(NAME, "FastMove poller started -- " + status() + " (" + windows_.size()
                + " window(s), poll " + (pollMillis_ / 1000L) + "s, warmup " + longestSpanMinutes_ + "m)"
                + (paused_.get() ? "; `fastmove on` to enable" : "") + ".");
        thread_.start();
    }

    /** Resume momentum detection (the {@code fastmove on} command). */
    public void resume()
    {
        if (paused_.compareAndSet(true, false))
        {
            GlobalSystem.info().writes(NAME, "FastMove started.");
        }
    }

    /** Pause momentum detection (the {@code fastmove off} command); the thread idles -- no sampling/firing. */
    public void pause()
    {
        if (paused_.compareAndSet(false, true))
        {
            GlobalSystem.info().writes(NAME, "FastMove paused.");
        }
    }

    /** One-word state for the {@code fastmove status} command. */
    public String status()
    {
        return paused_.get() ? "paused" : "running";
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
            if (!paused_.get())
            {
                pollOnce();
            }
            awaitNextPoll();
        }
    }

    private void pollOnce()
    {
        try
        {
            evaluate(Instant.now());
        }
        catch (Exception pollFailed)
        {
            // Abort this poll only; the polling thread stays alive for the next poll.
            GlobalSystem.error().writes(NAME, "FastMove poll failed -- aborted, poller continues", pollFailed);
        }
    }

    private void evaluate(Instant now)
    {
        Double price = collector_.currentPrice();
        if (price == null)
        {
            GlobalSystem.debug().writes(NAME, "No live price -- skipping this poll.");
        }
        else
        {
            buffer_.add(now, price);
            ArrayNode bars = buffer_.barsLastMinutes(longestSpanMinutes_);
            boolean flat = "flat".equals(PreTrend.of(bars).path("label").asText(""));
            act(now, price, FastMoveSignal.evaluate(bars, windows_), flat);
            checkCompression(now, flat);
        }
    }

    private void act(Instant now, double price, FastMoveSignal.Fire fire, boolean flat)
    {
        if (fire.fired())
        {
            fire(now, price, fire);
        }
        else if (flat)
        {
            latchedDirection_ = null; // the move is over -- re-arm for the next one
        }
    }

    private void fire(Instant now, double price, FastMoveSignal.Fire fire)
    {
        String direction = fire.direction();
        boolean reArmed = !direction.equals(latchedDirection_)
                || now.toEpochMilli() - lastFireMillis_ >= cooldownMillis_;
        if (reArmed)
        {
            publish(now, price, fire);
            latchedDirection_ = direction;
            lastFireMillis_ = now.toEpochMilli();
        }
        else
        {
            GlobalSystem.debug().writes(NAME, "Latched " + direction + " -- not re-firing this move.");
        }
    }

    private void publish(Instant now, double price, FastMoveSignal.Fire fire)
    {
        String day = Times.dayOf(Times.formatUtcIso(now));
        String key = Times.intervalKey(now, windowMinutes_);
        ObjectNode positioning = positioningAt(day, now, fire.magnitudePct());
        Conviction conviction = conviction(positioning);
        String setup = positioning == null ? "" : positioning.path("setup").asText("");
        publisher_.publish(new FastMoveReady(day, key, fire.direction(), conviction, price, fire.magnitudePct(),
                fire.r2(), fire.spanMinutes(), setup, now));
        GlobalSystem.info().writes(NAME, "FASTMOVE " + fire.direction() + " " + Num.round(fire.magnitudePct(), 2)
                + "% (" + fire.spanMinutes() + "m, r2=" + Num.round(fire.r2(), 2) + ") conviction=" + conviction.label()
                + (setup.isEmpty() ? "" : " setup=" + setup) + " @ " + Num.round(price, 2));
    }

    /** The funding/OI snapshot fused with the price move, or null when no funding rate is available. */
    private ObjectNode positioningAt(String day, Instant now, double magnitudePct)
    {
        ObjectNode current = collector_.funding().get(day, Times.intervalKey(now, windowMinutes_));
        Instant before = now.minusSeconds(oiLookbackMinutes_ * 60L);
        ObjectNode prior = collector_.funding().get(Times.dayOf(Times.formatUtcIso(before)),
                Times.intervalKey(before, windowMinutes_));
        return FundingSignals.signal(current, prior, magnitudePct);
    }

    /**
     * Grade the fire from open interest: OI building past the threshold is fresh positioning into the move
     * ({@code full} -- a real trend); OI unwinding is positions closing ({@code skip} -- a likely transient
     * wick); anything else (including no funding data) is {@code reduced} -- traded smaller, never trusted
     * as fully confirmed.
     */
    private Conviction conviction(ObjectNode positioning)
    {
        Conviction conviction = Conviction.REDUCED;
        if (positioning != null && positioning.path("oi_change_pct").isNumber())
        {
            double oiPct = positioning.path("oi_change_pct").asDouble();
            if (oiPct >= oiBuildingPct_)
            {
                conviction = Conviction.FULL;
            }
            else if (oiPct <= -oiBuildingPct_)
            {
                conviction = Conviction.SKIP;
            }
        }
        return conviction;
    }

    /** Log (once per episode) the soft early-warning: positive funding draining into a still-flat tape. */
    private void checkCompression(Instant now, boolean flat)
    {
        String day = Times.dayOf(Times.formatUtcIso(now));
        ObjectNode current = collector_.funding().get(day, Times.intervalKey(now, windowMinutes_));
        Instant before = now.minusSeconds(compressionWindowMinutes_ * 60L);
        ObjectNode prior = collector_.funding().get(Times.dayOf(Times.formatUtcIso(before)),
                Times.intervalKey(before, windowMinutes_));
        FundingCompression.Compression compression =
                FundingCompression.of(current, prior, flat, compressionDropPct_);
        if (compression.compressing() && !compressionFlagged_)
        {
            GlobalSystem.info().writes(NAME, "EARLY-WARNING: funding compressing " + compression.fundingDropPct()
                    + "% over " + compressionWindowMinutes_ + "m into a flat tape -- long conviction draining.");
            compressionFlagged_ = true;
        }
        else if (!compression.compressing())
        {
            compressionFlagged_ = false;
        }
    }

    private void awaitNextPoll()
    {
        synchronized (lock_)
        {
            if (running_)
            {
                try
                {
                    lock_.wait(pollMillis_);
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
