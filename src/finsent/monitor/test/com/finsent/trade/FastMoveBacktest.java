package com.finsent.trade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;
import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.trade.broker.PaperBroker;

/**
 * Offline FastMove trader backtest. Replays a day's logged FastMove fires ({@code fastmove_<day>.jsonl})
 * against its 1-minute price tape ({@code btc_price_<day>.jsonl}) through the REAL trader path
 * ({@link FSTrader#onFastSignal}/{@link FSTrader#manage}) and reports round-trip P&amp;L. Drives the trader
 * synchronously on this thread (like the unit tests; the worker idles), so the result is exactly what the
 * live trader would have done given those fires and that tape. Use it to A/B the re-entry cooldown (and any
 * FastMove-lane change) over real days before it touches money.
 *
 * <p>Usage: {@code FastMoveBacktest <dataDir> <YYYYMMDD> [cooldownMin,cooldownMin,...]} (default {@code 0,30}).
 */
public final class FastMoveBacktest
{
    // Cost model mirroring the <FSTrader feeRatePct/slippageInPct> defaults so the backtest P&L is NET of
    // the costs the live trader actually pays: a taker fee per side (charged on both entry and exit) and a
    // per-side slippage on each market fill. Tune to your venue fee tier / observed fills.
    private static final double TAKER_FEE_PCT = 0.035;
    private static final double SLIPPAGE_PCT = 0.02;

    // Momentum-lane params mirroring <FastMoveLane> + <FSTrader> (size/stop/trail/grace/max-hold/divergence/fee).
    // pricePoll is set huge so the trader's worker thread never fires manage() under us -- the backtest
    // drives manage() itself, minute by minute, off the replayed tape.
    private static final FSTrader.Params FAST_PARAMS = new FSTrader.Params("", 150.0, 3.0, 1.0, 1.0,
            24 * 3_600_000L, 3_600_000L, 30 * 60_000L, 0L, 1.0, TAKER_FEE_PCT, 0.0, 0, false);

    private FastMoveBacktest()
    {
    }

    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.out.println("Usage: FastMoveBacktest <dataDir> <YYYYMMDD> [cooldownMin,cooldownMin,...]");
            return;
        }
        Path dataDir = Path.of(args[0]);
        String day = args[1];
        int[] cooldowns = args.length > 2 ? parseInts(args[2]) : new int[] {0, 30};

        TreeMap<Long, Double> tape = loadTape(dataDir, day);
        List<FastMoveReady> fires = loadFires(dataDir, day);
        System.out.printf("FastMove backtest %s -- %d 1m bars, %d fire(s)%n", day, tape.size(), fires.size());
        for (int cooldownMin : cooldowns)
        {
            run(day, tape, fires, cooldownMin);
        }
    }

    private static void run(String day, TreeMap<Long, Double> tape, List<FastMoveReady> fires, int cooldownMin)
            throws IOException
    {
        Path tmp = Files.createTempDirectory("fastmove-bt");
        try
        {
            TradeBook book = new TradeBook(tmp);
            ReplayPriceSource price = new ReplayPriceSource(tape);
            FSTrader trader = new FSTrader(book, new PaperBroker(SLIPPAGE_PCT), price, FAST_PARAMS, FAST_PARAMS,
                    true, true, false, Conviction.FULL, cooldownMin * 60_000L, false);
            int next = 0;
            for (Map.Entry<Long, Double> bar : tape.entrySet())
            {
                long ms = bar.getKey();
                price.setNow(ms);
                while (next < fires.size() && fires.get(next).firedAt().toEpochMilli() <= ms)
                {
                    FastMoveReady fire = fires.get(next++);
                    trader.onFastSignal(fire, fire.firedAt());
                }
                trader.manage(Instant.ofEpochMilli(ms));
            }
            trader.uninitialize();
            report(day, book, cooldownMin);
        }
        finally
        {
            deleteTree(tmp);
        }
    }

    private static void report(String day, TradeBook book, int cooldownMin)
    {
        ArrayNode closed = book.closedForDay(day);
        double net = 0.0;
        double gross = 0.0;
        double fees = 0.0;
        for (JsonNode trade : closed)
        {
            net += trade.path("pnl_usd").asDouble();
            gross += trade.path("gross_pnl_usd").asDouble();
            fees += trade.path("fee_usd").asDouble();
        }
        System.out.printf("%n=== cooldown %dm: %d round trip(s), net %+.2f USD (gross %+.2f, fees %.2f) ===%n",
                cooldownMin, closed.size(), net, gross, fees);
        for (JsonNode trade : closed)
        {
            System.out.printf("   %s %-5s %.2f -> %.2f (%s)  net %+.2f USD (fee %.2f)%n",
                    hhmm(trade.path("opened_at").asText()), trade.path("side").asText(),
                    trade.path("entry_price").asDouble(), trade.path("exit_price").asDouble(),
                    trade.path("close_reason").asText(), trade.path("pnl_usd").asDouble(),
                    trade.path("fee_usd").asDouble());
        }
    }

    private static TreeMap<Long, Double> loadTape(Path dataDir, String day) throws IOException
    {
        TreeMap<Long, Double> tape = new TreeMap<>();
        for (String line : Files.readAllLines(dataDir.resolve(day).resolve("btc_price_" + day + ".jsonl")))
        {
            if (!line.isBlank())
            {
                JsonNode bar = Json.parse(line);
                tape.put(Times.parseIso(bar.path("ts").asText()).toEpochMilli(), bar.path("c").asDouble());
            }
        }
        return tape;
    }

    private static List<FastMoveReady> loadFires(Path dataDir, String day) throws IOException
    {
        List<FastMoveReady> fires = new ArrayList<>();
        Path file = dataDir.resolve(day).resolve("fastmove_" + day + ".jsonl");
        if (Files.exists(file))
        {
            for (String line : Files.readAllLines(file))
            {
                if (!line.isBlank())
                {
                    fires.add(parseFire(day, Json.parse(line)));
                }
            }
            fires.sort(Comparator.comparing(FastMoveReady::firedAt));
        }
        return fires;
    }

    private static FastMoveReady parseFire(String day, JsonNode node)
    {
        Double price = node.has("price") ? node.path("price").asDouble() : null;
        return new FastMoveReady(day, node.path("interval_key").asText(), node.path("direction").asText(),
                Conviction.of(node.path("conviction").asText(""), Conviction.REDUCED), price,
                node.path("magnitude_pct").asDouble(), node.path("r2").asDouble(), node.path("span_min").asInt(),
                node.path("velocity_ratio").asDouble(1.0), node.path("setup").asText(""),
                Times.parseIso(node.path("ts").asText()));
    }

    private static String hhmm(String iso)
    {
        return iso.length() >= 16 ? iso.substring(11, 16) : iso;
    }

    private static int[] parseInts(String csv)
    {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static void deleteTree(Path dir) throws IOException
    {
        try (Stream<Path> paths = Files.walk(dir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FastMoveBacktest::deleteQuietly);
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
            // best-effort temp cleanup
        }
    }

    /** Replays the day's 1-minute tape: {@code priceAt} returns the close at (or before) the set replay clock. */
    private static final class ReplayPriceSource implements PriceSource
    {
        private final TreeMap<Long, Double> tape_;
        private long nowMs_;

        private ReplayPriceSource(TreeMap<Long, Double> tape)
        {
            tape_ = tape;
        }

        private void setNow(long ms)
        {
            nowMs_ = ms;
        }

        @Override
        public Double priceAt(Instant target)
        {
            Map.Entry<Long, Double> bar = tape_.floorEntry(nowMs_);
            return bar == null ? null : bar.getValue();
        }
    }
}
