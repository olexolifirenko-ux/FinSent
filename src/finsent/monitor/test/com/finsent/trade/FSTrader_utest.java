package com.finsent.trade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.analyse.AnalysisReady;
import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;
import com.finsent.trade.broker.BrokerException;
import com.finsent.trade.broker.Fill;
import com.finsent.trade.broker.IBroker;
import com.finsent.trade.broker.OrderSide;
import com.finsent.trade.broker.PaperBroker;
import com.finsent.trade.broker.VenueState;

/**
 * Drives {@link FSTrader}'s deterministic seams ({@code onSignal} / {@code manage} / {@code flatten})
 * with a paper broker, a scripted price and a temp-dir {@link TradeBook} (no worker thread). Covers
 * the entry gate (only directional + high opens), the single-net-position cap, trailing-stop exits
 * locking in profit on both sides, the initial-stop loss, the max-hold backstop and manual flatten.
 */
public class FSTrader_utest
{
    private static final String DAY = "20260604";
    private static final String KEY = "08:00";
    private static final Instant NOW = Instant.parse("2026-06-04T08:00:00Z");
    private static final long FRESH_5MIN = 300_000L; // entryMaxNewsAgeMillis for the tests
    private static final double DIV_1PCT = 1.0;       // entryMaxPriceDivergencePct for the tests
    private static final double NO_FEE = 0.0;         // feeRatePct: costless fills, so the P&L assertions stay gross
    private static final double NO_LOSS_CAP = 0.0;    // maxDailyLossUsd: daily-loss kill-switch off
    private static final int NO_TRADE_CAP = 0;        // maxTradesPerDay: max-trades kill-switch off
    private static final boolean NO_VENUE_STOP = false; // venueStop: no venue-resting protective stop
    private static final FSTrader.Params PARAMS =
            new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 3_600_000L, 20_000L, 0L, FRESH_5MIN, DIV_1PCT, NO_FEE,
                    NO_LOSS_CAP, NO_TRADE_CAP, NO_VENUE_STOP); // time stop off, no daily limits
    // Same, but with a 10-minute profit-grace time stop enabled and a long max-hold (so only the time stop fires).
    private static final FSTrader.Params GRACE_PARAMS =
            new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 86_400_000L, 20_000L, 600_000L, FRESH_5MIN, DIV_1PCT,
                    NO_FEE, NO_LOSS_CAP, NO_TRADE_CAP, NO_VENUE_STOP);

    private Path dir_;
    private TradeBook book_;
    private FSTrader trader_;
    private double price_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-trader-utest");
        book_ = new TradeBook(dir_);
        trader_ = new FSTrader(book_, new PaperBroker(), target -> price_, PARAMS, false);
    }

    @After
    public void tearDown() throws IOException
    {
        trader_.uninitialize();
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FSTrader_utest::deleteQuietly);
        }
    }

    @Test
    public void neutralOrLowImpactDoesNotOpen()
    {
        price_ = 100.0;
        trader_.onSignal(signal("neutral", "high"), NOW);
        trader_.onSignal(signal("bullish", "low"), NOW);
        assertTrue(trader_.describe(NOW).contains("Flat"));
    }

    @Test
    public void bullishHighOpensLong()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW);
        assertTrue(trader_.describe(NOW).contains("Open LONG entry 100.0"));
    }

    @Test
    public void staleCatalystDoesNotOpen()
    {
        price_ = 100.0;
        // Directional + high, but the catalyst is 10 minutes old -- past the 5-minute entry freshness window.
        trader_.onSignal(signal("bullish", "high", NOW.minusSeconds(600)), NOW);
        assertTrue(trader_.describe(NOW).contains("Flat"));
    }

    @Test
    public void unknownCatalystTimeDoesNotOpen()
    {
        price_ = 100.0;
        // No catalyst time -> freshness cannot be verified -> fail-safe: do not open on real money.
        trader_.onSignal(signal("bullish", "high", null), NOW);
        assertTrue(trader_.describe(NOW).contains("Flat"));
    }

    @Test
    public void priceDivergedFromAnalysisDoesNotOpen()
    {
        // Live price is 10% off the signal's analysis-time anchor (100) -- past the 1% divergence guard.
        price_ = 110.0;
        trader_.onSignal(signal("bullish", "high"), NOW);
        assertTrue(trader_.describe(NOW).contains("Flat"));
    }

    @Test
    public void smallDivergenceWithinGuardStillOpens()
    {
        // 0.5% from the anchor (100) -- within the 1% divergence guard, so the entry proceeds.
        price_ = 100.5;
        trader_.onSignal(signal("bullish", "high"), NOW);
        assertTrue(trader_.describe(NOW).contains("Open LONG entry 100.5"));
    }

    @Test
    public void secondSignalIgnoredWhileAPositionIsOpen()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW);
        trader_.onSignal(signal("bearish", "high"), NOW); // opposite, but we are not flat
        assertTrue(trader_.describe(NOW).contains("Open LONG"));
        assertEquals(0, closed().size());
    }

    @Test
    public void longTrailingStopLocksInProfitOnPullback()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW); // stop 99
        price_ = 105.0;
        trader_.manage(NOW); // best 105 -> stop 103.95
        price_ = 103.0;
        trader_.manage(NOW); // 103 <= 103.95 -> exit in profit

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("trailing_stop", closed.get(0).path("close_reason").asText());
        assertEquals(60.0, closed.get(0).path("pnl_usd").asDouble(), 1e-6); // (103-100)/100 * 1000 * 2
        assertTrue(trader_.describe(NOW).contains("Flat"));
    }

    @Test
    public void shortTrailingStopLocksInProfitOnBounce()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bearish", "high"), NOW); // stop 101
        price_ = 95.0;
        trader_.manage(NOW); // best 95 -> stop 95.95
        price_ = 96.0;
        trader_.manage(NOW); // 96 >= 95.95 -> exit in profit

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals(80.0, closed.get(0).path("pnl_usd").asDouble(), 1e-6); // -(96-100)/100 * 1000 * 2
    }

    @Test
    public void initialStopCapsTheLoss()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW); // stop 99
        price_ = 98.9;
        trader_.manage(NOW); // 98.9 <= 99 -> stopped out at a ~1% loss (x leverage)

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("trailing_stop", closed.get(0).path("close_reason").asText());
        assertEquals(-22.0, closed.get(0).path("pnl_usd").asDouble(), 1e-6); // (98.9-100)/100 * 1000 * 2
    }

    @Test
    public void maxHoldClosesAQuietPosition()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW); // stop 99, never breached
        trader_.manage(NOW.plusMillis(PARAMS.maxHoldMillis())); // held to the cap -> close

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("max_hold", closed.get(0).path("close_reason").asText());
    }

    @Test
    public void dailyLossLimitHaltsNewOpens()
    {
        // $10 daily-loss kill-switch. One stop-out loses ~$22 (past the cap) -> further opens are halted.
        FSTrader.Params capped = new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 3_600_000L, 20_000L, 0L,
                FRESH_5MIN, DIV_1PCT, NO_FEE, 10.0, NO_TRADE_CAP, NO_VENUE_STOP);
        FSTrader trader = new FSTrader(book_, new PaperBroker(), target -> price_, capped, false);

        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW); // open LONG, stop 99
        price_ = 98.9;  trader.manage(NOW);                              // stop out for ~ -22 USD
        assertEquals(1, closed().size());
        assertTrue("first trade lost past the cap", closed().get(0).path("pnl_usd").asDouble() < -10.0);

        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW); // fresh qualifying call, but day is capped
        assertTrue("halted after the daily loss cap", trader.describe(NOW).contains("Flat"));
        assertEquals("no new position opened while halted", 1, closed().size());
    }

    @Test
    public void maxTradesPerDayHaltsNewOpens()
    {
        // At most one round trip per day. The exit (flatten) is never gated; only the next OPEN is.
        FSTrader.Params capped = new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 3_600_000L, 20_000L, 0L,
                FRESH_5MIN, DIV_1PCT, NO_FEE, NO_LOSS_CAP, 1, NO_VENUE_STOP);
        FSTrader trader = new FSTrader(book_, new PaperBroker(), target -> price_, capped, false);

        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW); // trade 1: open
        price_ = 101.0; trader.flatten(NOW);                             // ...and close (the day's one trade)
        assertEquals(1, closed().size());

        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW); // trade 2 blocked by the per-day cap
        assertTrue("halted after max trades/day", trader.describe(NOW).contains("Flat"));
        assertEquals("no second position opened", 1, closed().size());
    }

    @Test
    public void venueStopAttachesTheInitialStopToTheEntry()
    {
        FakeBroker broker = new FakeBroker();
        FSTrader trader = new FSTrader(book_, broker, target -> price_, venueStopParams(), false);
        price_ = 100.0;
        trader.onSignal(signal("bullish", "high"), NOW); // LONG entry at 100 with a 1% initial stop
        assertEquals("entry carries the initial protective stop (1% below)", 99.0, broker.lastProtectiveStop_, 1e-9);
    }

    @Test
    public void closeCancelsTheVenueStopFirst()
    {
        FakeBroker broker = new FakeBroker();
        FSTrader trader = new FSTrader(book_, broker, target -> price_, venueStopParams(), false);
        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW);
        price_ = 101.0; trader.flatten(NOW); // a deliberate close must pull the resting stop first
        assertEquals(1, closed().size());
        assertTrue("the venue stop is cancelled on close", broker.cancelStopCalls_ >= 1);
    }

    @Test
    public void venueStopOffAttachesNoBracketAndCancelsNothing()
    {
        FakeBroker broker = new FakeBroker();
        FSTrader trader = new FSTrader(book_, broker, target -> price_, PARAMS, false); // venueStop off
        price_ = 100.0; trader.onSignal(signal("bullish", "high"), NOW);
        assertEquals("no protective stop attached", 0.0, broker.lastProtectiveStop_, 1e-9);
        price_ = 101.0; trader.flatten(NOW);
        assertEquals("no venue cancel when off", 0, broker.cancelStopCalls_);
    }

    /** News params with the venue-resting stop armed (1% initial stop), for the venue-stop wiring tests. */
    private static FSTrader.Params venueStopParams()
    {
        return new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 3_600_000L, 20_000L, 0L, FRESH_5MIN, DIV_1PCT,
                NO_FEE, NO_LOSS_CAP, NO_TRADE_CAP, true);
    }

    @Test
    public void timeStopClosesAPositionNotInProfitByTheGrace()
    {
        FSTrader trader = new FSTrader(book_, new PaperBroker(), target -> price_, GRACE_PARAMS, false);
        price_ = 100.0;
        trader.onSignal(signal("bullish", "high"), NOW); // stop 99, grace 10m
        price_ = 99.5; // above the stop, but still under water (not in profit)
        trader.manage(NOW.plusMillis(GRACE_PARAMS.profitGraceMillis())); // grace elapsed, still not green

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("no_profit_timeout", closed.get(0).path("close_reason").asText());
    }

    @Test
    public void timeStopSparesAPositionAlreadyInProfit()
    {
        FSTrader trader = new FSTrader(book_, new PaperBroker(), target -> price_, GRACE_PARAMS, false);
        price_ = 100.0;
        trader.onSignal(signal("bullish", "high"), NOW);
        price_ = 100.5; // in profit at the grace deadline -> let it ride
        trader.manage(NOW.plusMillis(GRACE_PARAMS.profitGraceMillis()));

        assertEquals(0, closed().size());
        assertTrue(trader.describe(NOW).contains("Open LONG"));
    }

    @Test
    public void flattenClosesAtTheCurrentPrice()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bullish", "high"), NOW);
        price_ = 101.0;
        String message = trader_.flatten(NOW);

        assertTrue(message.contains("Flattened LONG"));
        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("manual_flatten", closed.get(0).path("close_reason").asText());
        assertEquals(20.0, closed.get(0).path("pnl_usd").asDouble(), 1e-6); // (101-100)/100 * 1000 * 2
    }

    @Test
    public void reconcileAdoptsAnOpenVenuePositionWhenTheBookIsFlat()
    {
        FakeBroker broker = new FakeBroker();
        broker.venue_ = VenueState.open(Side.LONG, 0.5, 200.0, PARAMS.leverage()); // leverage matches config
        FSTrader trader = new FSTrader(book_, broker, target -> price_, PARAMS, false);
        trader.reconcile(NOW);
        assertTrue(trader.describe(NOW).contains("Open LONG entry 200.0"));
    }

    @Test
    public void reconcileClearsTheBookWhenTheVenueIsFlat()
    {
        FakeBroker broker = new FakeBroker();
        FSTrader trader = new FSTrader(book_, broker, target -> price_, PARAMS, false);
        price_ = 100.0;
        trader.onSignal(signal("bullish", "high"), NOW); // book holds an open LONG
        broker.venue_ = VenueState.flat();
        trader.reconcile(NOW);
        assertTrue(trader.describe(NOW).contains("Flat"));
        assertEquals(0, closed().size()); // cleared without a fabricated ledger entry
    }

    @Test
    public void reconcileKeepsTheBookWhenTheVenueIsUnreachable()
    {
        FakeBroker broker = new FakeBroker();
        FSTrader trader = new FSTrader(book_, broker, target -> price_, PARAMS, false);
        price_ = 100.0;
        trader.onSignal(signal("bullish", "high"), NOW);
        broker.unreachable_ = true;
        trader.reconcile(NOW);
        assertTrue(trader.describe(NOW).contains("Open LONG")); // book left intact on a read failure
    }

    @Test
    public void confirmedReversalClosesAMomentumPosition()
    {
        FSTrader trader = momentumTrader();
        price_ = 100.0;
        trader.onFastSignal(fast("bearish", Conviction.FULL), NOW); // opens a momentum SHORT
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        price_ = 99.0; // short in profit
        trader.onFastSignal(fast("bullish", Conviction.FULL), NOW); // confirmed opposite fire -> reversal exit

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("fastmove_reversal", closed.get(0).path("close_reason").asText());
    }

    @Test
    public void skipConvictionOppositeDoesNotReverse()
    {
        FSTrader trader = momentumTrader();
        price_ = 100.0;
        trader.onFastSignal(fast("bearish", Conviction.FULL), NOW);
        price_ = 99.0;
        trader.onFastSignal(fast("bullish", Conviction.SKIP), NOW); // a weak (unwinding) opposite wick -> hold
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        assertEquals(0, closed().size());
    }

    @Test
    public void fastMoveReversalLeavesANewsPositionAlone()
    {
        FSTrader trader = momentumTrader();
        price_ = 100.0;
        trader.onSignal(signal("bearish", "high"), NOW); // a NEWS short
        trader.onFastSignal(fast("bullish", Conviction.FULL), NOW); // momentum reversal must not touch the news thesis
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        assertEquals(0, closed().size());
    }

    @Test
    public void newsReversalClosesANewsPositionOnAFreshOppositeHigh()
    {
        FSTrader trader = newsReversalTrader();
        price_ = 100.0;
        trader.onSignal(signal("bearish", "high"), NOW);   // opens a NEWS short
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        price_ = 99.0;
        trader.onSignal(signal("bullish", "high"), NOW);   // fresh opposite HIGH -> reversal exit (closes, no flip)

        assertTrue("closed flat, not flipped long", trader.describe(NOW).contains("Flat"));
        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("news_reversal", closed.get(0).path("close_reason").asText());
    }

    @Test
    public void newsReversalHoldsOnSameSideOrStaleOpposite()
    {
        FSTrader trader = newsReversalTrader();
        price_ = 100.0;
        trader.onSignal(signal("bearish", "high"), NOW);                        // NEWS short
        trader.onSignal(signal("bearish", "high"), NOW);                        // same side -> not a reversal
        trader.onSignal(signal("bullish", "high", NOW.minusSeconds(600)), NOW); // opposite but stale -> not a reversal
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        assertEquals(0, closed().size());
    }

    @Test
    public void newsReversalDisabledHoldsThroughAnOppositeHigh()
    {
        price_ = 100.0;
        trader_.onSignal(signal("bearish", "high"), NOW);  // default trader: reversalExit off
        trader_.onSignal(signal("bullish", "high"), NOW);  // opposite HIGH -> held (no news reversal)
        assertTrue(trader_.describe(NOW).contains("Open SHORT"));
        assertEquals(0, closed().size());
    }

    @Test
    public void reducedConvictionDoesNotOpenUnderTheFullGate()
    {
        FSTrader trader = momentumTrader(); // minConviction = full (default)
        price_ = 100.0;
        trader.onFastSignal(fast("bearish", Conviction.REDUCED), NOW); // below the gate -> telemetry only, no open
        assertTrue(trader.describe(NOW).contains("Flat"));
    }

    @Test
    public void reducedConvictionOpensWhenTheGateIsLowered()
    {
        FSTrader trader = new FSTrader(book_, new PaperBroker(), target -> price_, PARAMS, PARAMS, true, true,
                false, Conviction.REDUCED, 0L, false);
        price_ = 100.0;
        trader.onFastSignal(fast("bearish", Conviction.REDUCED), NOW);
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
    }

    @Test
    public void reentryCooldownSuppressesTheBounceWhipsawButKeepsTheExit()
    {
        // Replays the 06-25 shape: a cascade SHORT, its reversal EXIT (28m later), then a choppy bounce that
        // re-fires bearish/bullish/bearish within the cooldown. With a 30-min re-entry cooldown the chop
        // re-opens are suppressed -- but the reversal exit still closes the cascade short.
        FSTrader trader = momentumTrader(30 * 60_000L);
        Instant exit = NOW.plusSeconds(28 * 60);

        price_ = 100.0; trader.onFastSignal(fastAt("bearish", Conviction.FULL, 100.0, NOW), NOW);            // open SHORT
        assertTrue(trader.describe(NOW).contains("Open SHORT"));
        price_ = 98.0;  trader.onFastSignal(fastAt("bullish", Conviction.FULL, 98.0, exit), exit);           // reversal exit
        price_ = 97.0;  trader.onFastSignal(fastAt("bearish", Conviction.FULL, 97.0, min(35)), min(35));     // cooldown -> no open
        price_ = 99.0;  trader.onFastSignal(fastAt("bullish", Conviction.FULL, 99.0, min(40)), min(40));     // cooldown
        price_ = 98.5;  trader.onFastSignal(fastAt("bearish", Conviction.FULL, 98.5, min(56)), min(56));     // cooldown (28m<30m)

        assertEquals("only the cascade round trip closed", 1, closed().size());
        assertEquals("fastmove_reversal", closed().get(0).path("close_reason").asText());
        assertTrue("flat -- the chop was sat out", trader.describe(min(56)).contains("Flat"));
    }

    @Test
    public void withoutCooldownTheBounceWhipsawsTheTrader()
    {
        // Same sequence, cooldown OFF (today's behavior): each opposite fire reverse-exits then re-opens,
        // chopping the trader through the bounce -- two round trips and a fresh short left open.
        FSTrader trader = momentumTrader(0L);

        price_ = 100.0; trader.onFastSignal(fastAt("bearish", Conviction.FULL, 100.0, NOW), NOW);            // open SHORT
        price_ = 98.0;  trader.onFastSignal(fastAt("bullish", Conviction.FULL, 98.0, min(28)), min(28));     // reversal exit (1)
        price_ = 97.0;  trader.onFastSignal(fastAt("bearish", Conviction.FULL, 97.0, min(35)), min(35));     // RE-OPEN short
        price_ = 99.0;  trader.onFastSignal(fastAt("bullish", Conviction.FULL, 99.0, min(40)), min(40));     // reversal exit (2)
        price_ = 98.5;  trader.onFastSignal(fastAt("bearish", Conviction.FULL, 98.5, min(56)), min(56));     // RE-OPEN short

        assertEquals("whipsaw: two reversal round trips", 2, closed().size());
        assertTrue("ends in a fresh chop short", trader.describe(min(56)).contains("Open SHORT"));
    }

    private static Instant min(int minutesFromNow)
    {
        return NOW.plusSeconds(minutesFromNow * 60L);
    }

    @Test
    public void aReducedOppositeStillTriggersAReversalExit()
    {
        // Reversal exits ignore minConviction: a momentum SHORT (opened on full) is closed even by a
        // confirmed *reduced* opposite -- exiting a turned position is the conservative action.
        FSTrader trader = momentumTrader();
        price_ = 100.0;
        trader.onFastSignal(fast("bearish", Conviction.FULL), NOW);
        price_ = 99.0;
        trader.onFastSignal(fast("bullish", Conviction.REDUCED), NOW);

        ArrayNode closed = closed();
        assertEquals(1, closed.size());
        assertEquals("fastmove_reversal", closed.get(0).path("close_reason").asText());
    }

    /** A trader with the momentum lane armed (trade on, reversal-exit on, full-only gate), news+fast params identical. */
    private FSTrader momentumTrader()
    {
        return momentumTrader(0L);
    }

    /** As {@link #momentumTrader()} but with a re-entry cooldown (ms) after a momentum exit. */
    private FSTrader momentumTrader(long reentryCooldownMillis)
    {
        return new FSTrader(book_, new PaperBroker(), target -> price_, PARAMS, PARAMS, true, true,
                false, Conviction.FULL, reentryCooldownMillis, false);
    }

    /** A news-lane trader with the news reversal exit armed (momentum lane off). */
    private FSTrader newsReversalTrader()
    {
        return new FSTrader(book_, new PaperBroker(), target -> price_, PARAMS, PARAMS, false, false, true,
                Conviction.FULL, 0L, false);
    }

    private static FastMoveReady fast(String direction, Conviction conviction)
    {
        return new FastMoveReady(DAY, KEY, direction, conviction, 100.0, -1.5, 0.85, 30, 1.0, "", NOW);
    }

    /** A FastMove fire with an explicit anchor (so the divergence rail passes when price == anchor) and fire time. */
    private static FastMoveReady fastAt(String direction, Conviction conviction, double anchor, Instant firedAt)
    {
        return new FastMoveReady(DAY, KEY, direction, conviction, anchor, -1.5, 0.85, 5, 1.0, "", firedAt);
    }

    private ArrayNode closed()
    {
        return book_.closedForDay(DAY);
    }

    private static AnalysisReady signal(String direction, String tier)
    {
        return signal(direction, tier, NOW); // catalyst == NOW -> fresh
    }

    private static AnalysisReady signal(String direction, String tier, Instant catalystAt)
    {
        return new AnalysisReady(DAY, KEY, "news", direction, tier, 100.0, catalystAt, NOW);
    }

    /**
     * A broker with a scriptable venue state for reconciliation tests; fills paper-style at the passed price.
     * Also spies the venue-resting-stop seam: it records the protective-stop price the trader attaches to an
     * entry and counts {@code cancelProtectiveStops} calls.
     */
    private static final class FakeBroker implements IBroker
    {
        private VenueState venue_ = VenueState.untracked();
        private boolean unreachable_;
        private double lastProtectiveStop_ = -1.0; // the stop attached to the most recent entry (-1 = none seen)
        private int cancelStopCalls_;

        @Override
        public Fill marketOrder(OrderSide side, double qty, double price)
        {
            return new Fill(price, qty);
        }

        @Override
        public Fill marketOrder(OrderSide side, double qty, double price, double protectiveStopPrice)
        {
            lastProtectiveStop_ = protectiveStopPrice;
            return marketOrder(side, qty, price);
        }

        @Override
        public void cancelProtectiveStops()
        {
            cancelStopCalls_++;
        }

        @Override
        public String name()
        {
            return "fake";
        }

        @Override
        public VenueState venueState() throws BrokerException
        {
            if (unreachable_)
            {
                throw new BrokerException("venue down");
            }
            return venue_;
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // Best-effort temp cleanup; a leftover temp file must not fail the test.
        }
    }
}
