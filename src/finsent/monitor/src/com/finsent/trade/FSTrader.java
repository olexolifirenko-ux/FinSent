package com.finsent.trade;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.core.event.IEventListener;
import com.finsent.directory.DirectorySystem;
import com.finsent.trade.broker.BrokerException;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.IBroker;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.PaperBroker;
import com.finsent.trade.broker.VenueState;
import com.finsent.trade.broker.whitebit.WhiteBitBroker;
import com.finsent.trade.broker.whitebit.WhiteBitClient;
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
 * group. {@link #onAnalysisReadyEvent} must never block (it runs on the single-threaded event bus), so it only
 * enqueues; the worker owns the price fetches and order placement.
 */
public final class FSTrader implements IUninitializer
{
    private static final String NAME = "FSTrader";
    private static final long SHUTDOWN_JOIN_MILLIS = 10_000;

    private final TradeBook book_;
    private final IBroker broker_;
    private final PriceSource priceSource_;
    private final Params params_;
    // Entry/exit params for the FastMove (momentum) lane -- own sizing/stops, plus whether it may trade.
    private final Params fastParams_;
    private final boolean fastTrade_;
    // The strategy's pure eligibility rules (news min tier / momentum min conviction / reversal-exit gate).
    // The trader consults it to DECIDE, then owns the execution guards (freshness, divergence) and acts.
    private final EntryPolicy policy_;
    private final AtomicBoolean paused_;
    // Both lanes (news AnalysisReady + momentum FastMoveReady) feed this one queue; the worker dispatches
    // by type and routes both through the single synchronized open()/position_ gate.
    private final BlockingQueue<Object> queue_ = new LinkedBlockingQueue<>();
    private final Thread thread_;
    private volatile boolean running_ = true;
    // The single open position, or null when flat. Guarded by this monitor: the worker thread mutates
    // it (entry/trail/exit); the command thread reads it (status) and can flatten it.
    private Position position_;

    /** Production wiring: build the recovered book and the configured broker (paper or live WhiteBIT). */
    public FSTrader(FSCollector collector, Config config, WhiteBitClient whitebit, boolean startPaused)
    {
        // The live ticker price is always "now", so the PriceSource timestamp is ignored.
        this(buildBook(config), brokerFrom(config, whitebit), priceSourceFrom(config, whitebit, collector),
                paramsFrom(config), fastParamsFrom(config), config.fastMoveTrade(), config.fastMoveReversalExit(),
                Conviction.of(config.fastMoveMinConviction(), Conviction.FULL), startPaused);
    }

    /**
     * Injecting constructor: book, broker, price source and the news params supplied directly (used by
     * tests). The momentum lane defaults to the same params and alert-only (it is not exercised here).
     */
    FSTrader(TradeBook book, IBroker broker, PriceSource priceSource, Params params, boolean startPaused)
    {
        this(book, broker, priceSource, params, params, false, true, Conviction.FULL, startPaused);
    }

    /** Canonical injecting constructor: both lanes' params and the momentum trade / reversal / min-conviction. */
    FSTrader(TradeBook book, IBroker broker, PriceSource priceSource, Params params, Params fastParams,
            boolean fastTrade, boolean reversalExit, Conviction fastMinConviction, boolean startPaused)
    {
        book_ = book;
        broker_ = broker;
        priceSource_ = priceSource;
        params_ = params;
        fastParams_ = fastParams;
        fastTrade_ = fastTrade;
        policy_ = new EntryPolicy(params.entryImpactTier(), fastMinConviction, reversalExit);
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

    /** Select the live WhiteBIT broker only when configured for it and keyed; otherwise the safe paper broker. */
    private static IBroker brokerFrom(Config config, WhiteBitClient whitebit)
    {
        IBroker broker = new PaperBroker();
        if (liveWhiteBit(config, whitebit))
        {
            broker = new WhiteBitBroker(whitebit);
        }
        else if ("whitebit".equalsIgnoreCase(config.tradeBroker()))
        {
            GlobalSystem.warning().writes(NAME, "broker=whitebit but WhiteBIT keys are unset -- falling back to paper.");
        }
        return broker;
    }

    /**
     * The price feed. Live WhiteBIT trades read the price from the execution <b>venue</b> (its last price)
     * so the stop math and the fills share one feed; the Binance ticker is the fallback (and the source
     * for paper). The {@code PriceSource} timestamp is ignored -- both feeds are live "now" prices.
     */
    private static PriceSource priceSourceFrom(Config config, WhiteBitClient whitebit, FSCollector collector)
    {
        PriceSource source = target -> collector.currentPrice();
        if (liveWhiteBit(config, whitebit))
        {
            source = target ->
            {
                Double venue = whitebit.lastPrice();
                return venue != null ? venue : collector.currentPrice();
            };
        }
        return source;
    }

    /** Whether the trader is configured for live WhiteBIT execution (broker=whitebit and the keys are present). */
    private static boolean liveWhiteBit(Config config, WhiteBitClient whitebit)
    {
        return "whitebit".equalsIgnoreCase(config.tradeBroker()) && whitebit.configured();
    }

    private static Params paramsFrom(Config config)
    {
        return new Params(config.tradeEntryImpactTier(), config.tradeNotionalInUsd(), config.tradeLeverage(),
                config.tradeStopLossInPct(), config.tradeTrailInPct(), config.tradeMaxHoldInHours() * 3_600_000L,
                config.tradePricePollInSec() * 1000L, config.tradeProfitGraceInMin() * 60_000L,
                config.tradeEntryMaxNewsAgeInMin() * 60_000L, config.tradeEntryMaxPriceDivergencePct());
    }

    /**
     * Params for the momentum lane. Only the entry-side knobs that genuinely differ are FastMove-owned --
     * its own size ({@code notionalInUsd}/{@code leverage}, deliberately smaller) and its own initial
     * {@code stopLossInPct} (a momentum entry fires mid-move, so it needs a wider stop than a news entry;
     * the backtest confirmed the news stop whipsaws it). Exit management (trail / profit-grace / max-hold)
     * and the divergence rail are shared with {@code <FSTrader>}, so they are not duplicated in config. No
     * impact tier and no news-age gate apply, so {@code entryImpactTier} is unused and the news-age is 0.
     */
    private static Params fastParamsFrom(Config config)
    {
        return new Params("", config.fastMoveNotionalInUsd(), config.fastMoveLeverage(),
                config.fastMoveStopLossInPct(), config.tradeTrailInPct(),
                config.tradeMaxHoldInHours() * 3_600_000L, config.tradePricePollInSec() * 1000L,
                config.tradeProfitGraceInMin() * 60_000L, 0L, config.tradeEntryMaxPriceDivergencePct());
    }

    /**
     * Reconcile the recovered book against the venue, then start the worker thread (called by
     * {@code FSApp} after wiring; tests drive the seams directly). Reconciliation is a no-op for the
     * paper broker (no venue truth).
     */
    public void start()
    {
        reconcile(Instant.now());
        thread_.start();
    }

    // == Event intake + worker (runtime concerns) ==============================

    public void onAnalysisReadyEvent(AnalysisReady signal)
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

    /**
     * Intake for the FastMove (momentum) lane, subscribed to {@link FastMoveReady} via a method reference
     * (the class already implements {@link IEventListener} for {@link AnalysisReady}, so it cannot also
     * implement it for FastMoveReady). Same contract as {@link #onAnalysisReadyEvent}: never block the bus -- only enqueue.
     */
    public void onFastEvent(FastMoveReady signal)
    {
        if (paused_.get())
        {
            GlobalSystem.debug().writes(NAME, "Paused -- skipping FastMove " + signal.intervalKey());
        }
        else
        {
            queue_.offer(signal);
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
            dispatch(awaitSignal(), Instant.now());
            manage(Instant.now());
        }
    }

    /** Route a dequeued signal to its lane; both lanes converge on the single synchronized {@link #open}. */
    private void dispatch(Object signal, Instant now)
    {
        if (signal instanceof AnalysisReady news)
        {
            onSignal(news, now);
        }
        else if (signal instanceof FastMoveReady fast)
        {
            onFastSignal(fast, now);
        }
    }

    private Object awaitSignal()
    {
        Object signal = null;
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

    /** Act on one news signal: open a position when it qualifies and we are flat. Worker-thread / test entry point. */
    void onSignal(AnalysisReady signal, Instant now)
    {
        // Merit (tier/direction) is the policy's call; freshness is an execution-time guard the trader owns.
        if (isFlat() && policy_.qualifiesNews(signal) && fresh(signal, now))
        {
            Double price = priceSource_.priceAt(now);
            if (price == null)
            {
                GlobalSystem.warning().writes(NAME, "No price for entry on " + signal.day() + " "
                        + signal.intervalKey() + " -- signal skipped");
            }
            else if (!priceDiverged(signal.anchorPrice(), price, params_.entryMaxPriceDivergencePct(),
                    signal.day() + " " + signal.intervalKey()))
            {
                open(signal.day(), signal.intervalKey(), signal.source(), signal.direction(), price, params_, now,
                        signal.day() + " " + signal.intervalKey() + " " + signal.direction() + "/" + signal.impactTier());
            }
        }
    }

    /**
     * Act on one FastMove (momentum) signal. Worker-thread / test entry point. No impact-tier or news-age
     * gate (those are news concerns); it opens when directional, the conviction is not {@code skip}, and we
     * are flat -- but only when {@code FSFastMove trade} is on. When trade is off the fire is alert-only:
     * the poller already published/logged it; the trader just notes it without opening.
     */
    void onFastSignal(FastMoveReady signal, Instant now)
    {
        if (isFlat())
        {
            maybeOpenFastMove(signal, now);
        }
        else
        {
            maybeReversalExit(signal, now);
        }
    }

    private void maybeOpenFastMove(FastMoveReady signal, Instant now)
    {
        if (policy_.qualifiesFast(signal))
        {
            if (!fastTrade_)
            {
                GlobalSystem.debug().writes(NAME, "FastMove alert-only (trade=false) -- not opening "
                        + signal.direction() + "/" + signal.conviction() + " " + signal.intervalKey());
            }
            else
            {
                openFastMove(signal, now);
            }
        }
    }

    private void openFastMove(FastMoveReady signal, Instant now)
    {
        Double price = priceSource_.priceAt(now);
        if (price == null)
        {
            GlobalSystem.warning().writes(NAME, "No price for FastMove entry " + signal.intervalKey() + " -- skipped");
        }
        else if (!priceDiverged(signal.anchorPrice(), price, fastParams_.entryMaxPriceDivergencePct(),
                "fastmove " + signal.intervalKey()))
        {
            open(signal.day(), signal.intervalKey(), "momentum", signal.direction(), price, fastParams_, now,
                    "fastmove " + signal.direction() + "/" + signal.conviction() + " " + signal.spanMinutes() + "m");
        }
    }

    /**
     * Reversal exit: a confirmed opposite-direction FastMove closes an open MOMENTUM position at market --
     * the move we rode has turned, so bank it now rather than wait for the trailing stop to give back. Scoped
     * to momentum positions (a news position keeps its own thesis/exit) and gated on a qualifying (non-skip)
     * opposite fire, so a weak counter-trend wick does not shake the position out. Off when {@code reversalExit}
     * is false.
     */
    private void maybeReversalExit(FastMoveReady signal, Instant now)
    {
        Position open = current();
        if (policy_.isReversalExit(signal, open))
        {
            Double price = priceSource_.priceAt(now);
            if (price == null)
            {
                GlobalSystem.warning().writes(NAME, "No price for FastMove reversal exit " + signal.intervalKey());
            }
            else
            {
                reversalClose(open, price, now);
            }
        }
    }

    private synchronized void reversalClose(Position expected, double price, Instant now)
    {
        if (position_ == expected) // still the same live position (not closed on another thread meanwhile)
        {
            close(price, now, "fastmove_reversal");
        }
    }

    /**
     * Whether the live entry price has moved more than {@code maxPct} from the {@code anchor} price -- a
     * real-money safety rail: a sharp move between the verdict/fire and the order means the market repriced,
     * so the entry is skipped (logged with {@code tag}). Disabled at {@code <= 0}; not triggered when no
     * anchor price is available (cannot compute, so it does not block). Shared by both lanes.
     */
    private boolean priceDiverged(Double anchor, double price, double maxPct, String tag)
    {
        boolean diverged = false;
        if (maxPct > 0 && anchor != null && anchor != 0.0)
        {
            double divergencePct = Math.abs(price - anchor) / anchor * 100.0;
            diverged = divergencePct > maxPct;
            if (diverged)
            {
                GlobalSystem.warning().writes(NAME, "Entry skipped (price diverged " + Num.round(divergencePct, 2)
                        + "% > " + maxPct + "% since analysis: $" + anchor + " -> $" + price + ") " + tag);
            }
        }
        return diverged;
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

    /**
     * Whether the catalyst is recent enough to open on -- real-money entry must be on a FRESH event, so a
     * stale call (re-analysis, backfill, a late-arriving article) is rejected. Measured against the newest
     * resonant article's publish time ({@code catalystAt}); a missing catalyst time is treated as not fresh
     * (fail-safe). The gate is disabled when {@code entryMaxNewsAgeMillis <= 0}.
     */
    private boolean fresh(AnalysisReady signal, Instant now)
    {
        boolean fresh = true;
        if (params_.entryMaxNewsAgeMillis() > 0)
        {
            Instant catalystAt = signal.catalystAt();
            fresh = catalystAt != null
                    && now.toEpochMilli() - catalystAt.toEpochMilli() <= params_.entryMaxNewsAgeMillis();
            if (!fresh)
            {
                GlobalSystem.warning().writes(NAME, "Entry skipped (stale catalyst) " + signal.day() + " "
                        + signal.intervalKey() + " -- catalyst=" + (catalystAt == null ? "unknown" : catalystAt)
                        + " now=" + now + " max=" + (params_.entryMaxNewsAgeMillis() / 60_000L) + "m");
            }
        }
        return fresh;
    }

    private synchronized boolean isFlat()
    {
        return position_ == null;
    }

    private synchronized Position current()
    {
        return position_;
    }

    /**
     * Open a single net position from raw fields, sized and stopped by {@code params} (the news lane passes
     * {@code params_}, the momentum lane {@code fastParams_} -- which differs only in size and initial stop;
     * exit management is shared). The sole entry point for both lanes -- {@code synchronized} on the one
     * {@code position_}, so the flat-check and the open are atomic and two lanes can never both open.
     */
    private synchronized void open(String day, String key, String source, String direction, double price,
            Params params, Instant now, String tag)
    {
        Side side = "bullish".equals(direction) ? Side.LONG : Side.SHORT;
        OrderSide order = side.isLong() ? OrderSide.BUY : OrderSide.SELL;
        double qty = params.notionalInUsd() * params.leverage() / price;
        try
        {
            Fill fill = broker_.marketOrder(order, qty, price);
            position_ = Position.open(day, key, source, side, fill.price(), params.notionalInUsd(),
                    params.leverage(), now, params.stopLossInPct());
            book_.recordPosition(position_);
            GlobalSystem.info().writes(NAME, "OPEN " + side + " @ " + Num.round(fill.price(), 2) + " stop "
                    + Num.round(position_.currentStop(), 2) + " (" + tag + ")");
        }
        catch (BrokerException orderRejected)
        {
            // Entry rejected by the venue: stay flat (the signal is dropped, no phantom position booked).
            GlobalSystem.error().writes(NAME, "OPEN " + side + " REJECTED -- staying flat: " + orderRejected.getMessage());
        }
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

    private synchronized boolean close(double price, Instant now, String reason)
    {
        Position open = position_;
        OrderSide order = open.side().isLong() ? OrderSide.SELL : OrderSide.BUY;
        boolean closed = false;
        try
        {
            Fill fill = broker_.marketOrder(order, open.qty(), price);
            ObjectNode record = open.toClosedRecord(fill.price(), now, reason);
            book_.recordClose(open, record);
            position_ = null;
            closed = true;
            GlobalSystem.info().writes(NAME, "CLOSE " + open.side() + " @ " + Num.round(fill.price(), 2) + " (" + reason
                    + ") pnl " + Num.round(open.pnlUsd(fill.price()), 2) + " USD / "
                    + Num.round(open.pnlPct(fill.price()), 2) + "%");
        }
        catch (BrokerException orderRejected)
        {
            // Exit rejected: keep the position so the next poll retries -- never drop a live position we still hold.
            GlobalSystem.error().writes(NAME, "CLOSE " + open.side() + " REJECTED (" + reason
                    + ") -- position STILL OPEN, will retry: " + orderRejected.getMessage());
        }
        return closed;
    }

    // == Startup reconciliation (live broker) ==================================

    /**
     * Align the recovered book with the venue's actual open position at startup. No-op for the paper
     * broker (no venue truth). On any mismatch the venue is treated as the source of truth and the
     * divergence is logged loudly; if the venue cannot be read, the recovered book is kept as-is.
     * Package-private so tests can drive it with a fake broker.
     */
    void reconcile(Instant now)
    {
        try
        {
            applyVenueState(broker_.venueState(), now);
        }
        catch (BrokerException unreachable)
        {
            GlobalSystem.error().writes(NAME, "Reconcile FAILED (venue unreachable): " + unreachable.getMessage()
                    + " -- starting with the recovered book as-is.");
        }
    }

    private synchronized void applyVenueState(VenueState venue, Instant now)
    {
        if (venue.kind() == VenueState.Kind.FLAT)
        {
            reconcileAgainstFlatVenue();
        }
        else if (venue.kind() == VenueState.Kind.OPEN)
        {
            reconcileAgainstOpenVenue(venue, now);
        }
        // UNTRACKED (paper): the local book is authoritative -- nothing to reconcile.
    }

    private void reconcileAgainstFlatVenue()
    {
        if (position_ != null)
        {
            GlobalSystem.error().writes(NAME, "RECONCILE MISMATCH: book shows an open " + position_.side()
                    + " but the venue is FLAT -- clearing it (closed/liquidated outside the trader).");
            book_.clearPosition(position_.day());
            position_ = null;
        }
        else
        {
            GlobalSystem.debug().writes(NAME, "Reconcile: book flat, venue flat -- in sync.");
        }
    }

    private void reconcileAgainstOpenVenue(VenueState venue, Instant now)
    {
        warnOnVenueLeverageMismatch(venue);
        if (position_ == null)
        {
            position_ = adopt(venue, now);
            GlobalSystem.error().writes(NAME, "RECONCILE: book was flat but the venue holds an open " + venue.side()
                    + " " + Num.round(venue.qty(), 6) + " BTC @ " + Num.round(venue.entryPrice(), 2)
                    + " -- ADOPTED it (managing to exit).");
        }
        else if (matches(position_, venue))
        {
            GlobalSystem.info().writes(NAME, "Reconcile: book and venue agree on the open " + venue.side()
                    + " position -- in sync.");
        }
        else
        {
            GlobalSystem.error().writes(NAME, "RECONCILE MISMATCH: book shows " + position_.side() + " "
                    + Num.round(position_.qty(), 6) + " but venue holds " + venue.side() + " "
                    + Num.round(venue.qty(), 6) + " @ " + Num.round(venue.entryPrice(), 2)
                    + " -- adopting the venue position as truth.");
            book_.clearPosition(position_.day());
            position_ = adopt(venue, now);
        }
    }

    /** Build and persist a fresh position from the venue's open state (entry/side/qty), anchored at {@code now}. */
    private Position adopt(VenueState venue, Instant now)
    {
        String day = Times.dayOf(Times.formatUtcIso(now));
        double notionalInUsd = venue.qty() * venue.entryPrice() / params_.leverage();
        Position adopted = Position.open(day, "reconciled", "reconcile", venue.side(), venue.entryPrice(),
                notionalInUsd, params_.leverage(), now, params_.stopLossInPct());
        book_.recordPosition(adopted);
        return adopted;
    }

    /**
     * Warn (once, at reconcile) when the venue position's effective leverage diverges from config by more
     * than 10%: position sizing and {@code pnl_pct} assume the config value, and the real liquidation
     * follows the venue's. The fix is to set {@code <FSTrader leverage>} to match the WhiteBIT UI.
     */
    private void warnOnVenueLeverageMismatch(VenueState venue)
    {
        double venueLeverage = venue.leverage();
        if (venueLeverage > 0.0 && Math.abs(venueLeverage - params_.leverage()) > params_.leverage() * 0.1)
        {
            GlobalSystem.warning().writes(NAME, "LEVERAGE MISMATCH: config leverage "
                    + Num.round(params_.leverage(), 1) + "x but the venue position is at ~"
                    + Num.round(venueLeverage, 1) + "x -- sizing and pnl_pct assume config, liquidation follows the "
                    + "venue; set <FSTrader leverage> to match the WhiteBIT UI.");
        }
    }

    /** Whether the book's open position is the same side and (within tolerance) the same size as the venue's. */
    private static boolean matches(Position book, VenueState venue)
    {
        double tolerance = Math.max(0.0001, venue.qty() * 0.01);
        return book.side() == venue.side() && Math.abs(book.qty() - venue.qty()) <= tolerance;
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
            boolean closed = close(price, now, "manual_flatten");
            message = closed
                    ? "Flattened " + open.side() + " @ " + Num.round(price, 2) + " pnl " + Num.round(pnl, 2) + " USD."
                    : "Flatten FAILED -- the venue rejected the close; position still open (see log).";
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
            double trailInPct, long maxHoldMillis, long pricePollMillis, long profitGraceMillis,
            long entryMaxNewsAgeMillis, double entryMaxPriceDivergencePct)
    {
    }
}
