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
import com.finsent.trade.broker.PaperBroker;

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
    private static final FSTrader.Params PARAMS =
            new FSTrader.Params("high", 1000.0, 2.0, 1.0, 1.0, 3_600_000L, 20_000L);

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

    private ArrayNode closed()
    {
        return book_.closedForDay(DAY);
    }

    private static AnalysisReady signal(String direction, String tier)
    {
        return new AnalysisReady(DAY, KEY, "news", direction, tier, 100.0, NOW);
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
