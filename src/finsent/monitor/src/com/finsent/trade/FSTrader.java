package com.finsent.trade;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.notify.ImpactTier;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.core.event.IEventListener;
import com.finsent.directory.DirectorySystem;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.IBroker;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.PaperBroker;
import com.finsent.util.GlobalSystem;
import com.finsent.util.IUninitializer;

/**
 * The trading module: it subscribes to the analyser's {@link AnalysisReady} signals and runs a
 * paper long/short BTC strategy off them. A directional, sufficiently material call opens a single
 * net position (long on bullish, short on bearish); a trailing stop manages the exit (initial stop
 * adverse, ratcheting toward the trade as price moves favorably), with an optional "not in profit by
 * N minutes" time stop and a max-hold backstop. It mirrors
 * {@link FSCollector}/{@code FSAnalyser}: it both runs the strategy and owns its runtime concerns
 * &mdash; its own {@link TradeBook} (persistence), the {@link IBroker} (paper in v1) and a single
 * daemon thread that drains queued signals and re-evaluates the open position every
 * {@code pricePollInSec}.
 *
 * <p>Like the analyser, the production constructor builds everything from {@link Config}; the
 * package-private injecting constructor takes the book, broker, {@link PriceSource} and {@link Params}
 * directly (tests drive {@link #onSignal} / {@link #manage} synchronously). Started paused or running
 * from the {@code -DrunTrader} launcher property and toggled at runtime via the {@code trade} command
 * group. {@link #onEvent} must never block (it runs on the single-threaded event bus), so it only
 * enqueues; the worker owns the price fetches and order placement.
 */
public final class FSTrader implements IEventListener<AnalysisReady>, IUninitializer
{
    private static final String NAME = "FSTrader";
    private static final long SHUTDOWN_JOIN_MILLIS = 10_000;

    private final TradeBook book_;
    private final IBroker broker_;
    private final PriceSource priceSource_;
    private final Params params_;
    private final AtomicBoolean paused_;
    private final BlockingQueue<AnalysisReady> queue_ = new LinkedBlockingQueue<>();
    private final Thread thread_;
    private volatile boolean running_ = true;
    // The single open position, or null when flat. Guarded by this monitor: the worker thread mutates
    // it (entry/trail/exit); the command thread reads it (status) and can flatten it.
    private Position position_;

    /** Production wiring: build the recovered book and the paper broker from config. */
    public FSTrader(FSCollector collector, Config config, boolean startPaused)
    {
        // The live ticker price is always "now", so the PriceSource timestamp is ignored.
        this(buildBook(config), new PaperBroker(), target -> collector.currentPrice(), paramsFrom(config), startPaused);
    }

    /** Injecting constructor: book, broker, price source and params supplied directly (used by tests). */
    FSTrader(TradeBook book, IBroker broker, PriceSource priceSource, Params params, boolean startPaused)
    {
        book_ = book;
        broker_ = broker;
        priceSource_ = priceSource;
        params_ = params;
        paused_ = new AtomicBoolean(startPaused);
        position_ = book.openPosition(); // adopt a recovered open position, if any
        thread_ = new Thread(this::loop, "FS-Trader");
        thread_.setDaemon(true);
        GlobalSystem.info().writes(NAME, "Trader created (" + status() + ", broker=" + broker.name()
                + (position_ == null ? ", flat" : ", adopted open " + position_.side()) + ").");
    }

    private static TradeBook buildBook(Config config)
    {
        TradeBook book = new TradeBook(DirectorySystem.resolveToFile(config.dataDir()).toPath());
        book.recover(config.recoveryLookbackInDays());
        return book;
    }

    private static Params paramsFrom(Config config)
    {
        return new Params(config.tradeEntryImpactTier(), config.tradeNotionalInUsd(), config.tradeLeverage(),
                config.tradeStopLossInPct(), config.tradeTrailInPct(), config.tradeMaxHoldInHours() * 3_600_000L,
                config.tradePricePollInSec() * 1000L, config.tradeProfitGraceInMin() * 60_000L);
    }

    /** Start the worker thread (called by {@code FSApp} after wiring; tests drive the seams directly). */
    public void start()
    {
        thread_.start();
    }

    // == Event intake + worker (runtime concerns) ==============================

    @Override
    public void onEvent(AnalysisReady signal)
    {
        if (paused_.get())
        {
            GlobalSystem.debug().writes(NAME, "Paused -- skipping " + signal.day() + " " + signal.intervalKey());
        }
        else
        {
            queue_.offer(signal); // non-blocking: never stall the single-threaded event bus
        }
    }

    /** Resume trading (the {@code trade on} command); only affects whether new signals are acted on. */
    public void resume()
    {
        if (paused_.compareAndSet(true, false))
        {
            GlobalSystem.info().writes(NAME, "Trading started.");
        }
    }

    /** Pause trading (the {@code trade off} command); an open position is still managed to its exit. */
    public void pause()
    {
        if (paused_.compareAndSet(false, true))
        {
            GlobalSystem.info().writes(NAME, "Trading paused.");
        }
    }

    /** One-word state for the {@code trade status} command. */
    public String status()
    {
        return paused_.get() ? "paused" : "running";
    }

    @Override
    public void uninitialize()
    {
        running_ = false;
        thread_.interrupt();
        joinWorker();
        book_.shutdown();
    }

    private void joinWorker()
    {
        try
        {
            thread_.join(SHUTDOWN_JOIN_MILLIS);
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
            AnalysisReady signal = awaitSignal();
            if (signal != null)
            {
                onSignal(signal, Instant.now());
            }
            manage(Instant.now());
        }
    }

    private AnalysisReady awaitSignal()
    {
        AnalysisReady signal = null;
        try
        {
            signal = queue_.poll(params_.pricePollMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException shuttingDown)
        {
            Thread.currentThread().interrupt();
            running_ = false;
        }
        return signal;
    }

    // == Strategy (the deterministic seams) ====================================

    /** Act on one signal: open a position when it qualifies and we are flat. Worker-thread / test entry point. */
    void onSignal(AnalysisReady signal, Instant now)
    {
        if (qualifies(signal) && isFlat())
        {
            Double price = priceSource_.priceAt(now);
            if (price == null)
            {
                GlobalSystem.warning().writes(NAME, "No price for entry on " + signal.day() + " "
                        + signal.intervalKey() + " -- signal skipped");
            }
            else
            {
                open(signal, price, now);
            }
        }
    }

    /** Re-evaluate the open position against the current price: trail the stop, exit on breach/max-hold. */
    void manage(Instant now)
    {
        Position open = current();
        if (open != null)
        {
            Double price = priceSource_.priceAt(now);
            if (price != null)
            {
                evaluate(open, price, now);
            }
        }
    }

    private boolean qualifies(AnalysisReady signal)
    {
        boolean directional = "bullish".equals(signal.direction()) || "bearish".equals(signal.direction());
        int rank = ImpactTier.order(signal.impactTier(), -1);
        int minimum = ImpactTier.order(params_.entryImpactTier(), 2);
        return directional && rank >= minimum;
    }

    private synchronized boolean isFlat()
    {
        return position_ == null;
    }

    private synchronized Position current()
    {
        return position_;
    }

    private synchronized void open(AnalysisReady signal, double price, Instant now)
    {
        Side side = "bullish".equals(signal.direction()) ? Side.LONG : Side.SHORT;
        OrderSide order = side.isLong() ? OrderSide.BUY : OrderSide.SELL;
        double qty = params_.notionalInUsd() * params_.leverage() / price;
        Fill fill = broker_.marketOrder(order, qty, price);
        position_ = Position.open(signal.day(), signal.intervalKey(), signal.source(), side, fill.price(),
                params_.notionalInUsd(), params_.leverage(), now, params_.stopLossInPct());
        book_.recordPosition(position_);
        GlobalSystem.info().writes(NAME, "OPEN " + side + " @ " + Num.round(fill.price(), 2) + " stop "
                + Num.round(position_.currentStop(), 2) + " (" + signal.day() + " " + signal.intervalKey() + " "
                + signal.direction() + "/" + signal.impactTier() + ")");
    }

    private synchronized void evaluate(Position open, double price, Instant now)
    {
        if (position_ == open) // still the live position (not flattened on another thread meanwhile)
        {
            double prevStop = position_.currentStop();
            position_.updateTrail(price, params_.trailInPct());
            String reason = exitReason(position_, price, now);
            if (reason != null)
            {
                close(price, now, reason);
            }
            else if (position_.currentStop() != prevStop)
            {
                book_.recordPosition(position_); // persist only when the stop actually ratcheted
            }
        }
    }

    private String exitReason(Position open, double price, Instant now)
    {
        String reason = null;
        long heldMillis = now.toEpochMilli() - open.openedAt().toEpochMilli();
        if (TrailingStop.breached(open.side(), price, open.currentStop()))
        {
            reason = "trailing_stop";
        }
        else if (params_.profitGraceMillis() > 0 && heldMillis >= params_.profitGraceMillis()
                && open.pnlUsd(price) <= 0.0)
        {
            reason = "no_profit_timeout"; // catalyst not working: not in profit by the grace deadline
        }
        else if (heldMillis >= params_.maxHoldMillis())
        {
            reason = "max_hold";
        }
        return reason;
    }

    private synchronized void close(double price, Instant now, String reason)
    {
        Position open = position_;
        OrderSide order = open.side().isLong() ? OrderSide.SELL : OrderSide.BUY;
        Fill fill = broker_.marketOrder(order, open.qty(), price);
        ObjectNode closed = open.toClosedRecord(fill.price(), now, reason);
        book_.recordClose(open, closed);
        position_ = null;
        GlobalSystem.info().writes(NAME, "CLOSE " + open.side() + " @ " + Num.round(fill.price(), 2) + " (" + reason
                + ") pnl " + Num.round(open.pnlUsd(fill.price()), 2) + " USD / "
                + Num.round(open.pnlPct(fill.price()), 2) + "%");
    }

    // == Commands ==============================================================

    /** {@code trade flatten}: close the open position now at the current price. */
    public String flatten(Instant now)
    {
        String message;
        if (isFlat())
        {
            message = "Flat -- nothing to close.";
        }
        else
        {
            message = closeManually(priceSource_.priceAt(now), now);
        }
        return message;
    }

    private synchronized String closeManually(Double price, Instant now)
    {
        String message;
        if (position_ == null)
        {
            message = "Flat -- nothing to close.";
        }
        else if (price == null)
        {
            message = "No price available -- could not flatten.";
        }
        else
        {
            Position open = position_;
            double pnl = open.pnlUsd(price);
            close(price, now, "manual_flatten");
            message = "Flattened " + open.side() + " @ " + Num.round(price, 2) + " pnl " + Num.round(pnl, 2) + " USD.";
        }
        return message;
    }

    /** {@code trade status}: a one-line summary of state, the open position and today's realized P&amp;L. */
    public String describe(Instant now)
    {
        Double price = isFlat() ? null : priceSource_.priceAt(now);
        return render(price, now);
    }

    private synchronized String render(Double price, Instant now)
    {
        StringBuilder out = new StringBuilder();
        out.append("Trader: ").append(status()).append(" (broker=").append(broker_.name()).append("). ");
        if (position_ == null)
        {
            out.append("Flat.");
        }
        else
        {
            out.append("Open ").append(position_.side()).append(" entry ").append(Num.round(position_.entryPrice(), 2))
                    .append(" stop ").append(Num.round(position_.currentStop(), 2));
            if (price != null)
            {
                out.append(" last ").append(Num.round(price, 2)).append(" uPnL ")
                        .append(Num.round(position_.pnlUsd(price), 2)).append(" USD");
            }
            out.append('.');
        }
        out.append(" Realized today: ").append(Num.round(realizedToday(now), 2)).append(" USD over ")
                .append(closedToday(now)).append(" trade(s).");
        return out.toString();
    }

    private double realizedToday(Instant now)
    {
        double sum = 0.0;
        for (JsonNode trade : book_.closedForDay(Times.dayOf(Times.formatUtcIso(now))))
        {
            sum += trade.path("pnl_usd").asDouble();
        }
        return sum;
    }

    private int closedToday(Instant now)
    {
        return book_.closedForDay(Times.dayOf(Times.formatUtcIso(now))).size();
    }

    /** The numeric strategy parameters, built from {@link Config} in production and directly in tests. */
    public record Params(String entryImpactTier, double notionalInUsd, double leverage, double stopLossInPct,
            double trailInPct, long maxHoldMillis, long pricePollMillis, long profitGraceMillis)
    {
    }
}
